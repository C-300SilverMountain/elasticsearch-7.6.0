/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.bootstrap;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.lucene.util.Constants;
import org.elasticsearch.core.internal.io.IOUtils;
import org.apache.lucene.util.StringHelper;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.Version;
import org.elasticsearch.cli.UserException;
import org.elasticsearch.common.PidFile;
import org.elasticsearch.common.SuppressForbidden;
import org.elasticsearch.common.inject.CreationException;
import org.elasticsearch.common.logging.DeprecationLogger;
import org.elasticsearch.common.logging.LogConfigurator;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.network.IfConfig;
import org.elasticsearch.common.settings.KeyStoreWrapper;
import org.elasticsearch.common.settings.SecureSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.BoundTransportAddress;
import org.elasticsearch.env.Environment;
import org.elasticsearch.monitor.jvm.JvmInfo;
import org.elasticsearch.monitor.os.OsProbe;
import org.elasticsearch.monitor.process.ProcessProbe;
import org.elasticsearch.node.InternalSettingsPreparer;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.NodeValidationException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Internal startup code.
 */
final class Bootstrap {

    private static volatile Bootstrap INSTANCE;
    private volatile Node node;
    private final CountDownLatch keepAliveLatch = new CountDownLatch(1);
    private final Thread keepAliveThread;
    private final Spawner spawner = new Spawner();

    /** creates a new instance */
    Bootstrap() {
        keepAliveThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    //一直等待，直到keepAliveLatch.countDown()被调用
                    //目测便于检测当前Node节点是否存活
                    keepAliveLatch.await();
                } catch (InterruptedException e) {
                    // bail out
                }
            }
        }, "elasticsearch[keepAlive/" + Version.CURRENT + "]");
        keepAliveThread.setDaemon(false);
        // keep this thread alive (non daemon thread) until we shutdown
        // 一句话概括就是： ShutdownHook允许开发人员在JVM关闭时执行相关的代码。
        //see: https://blog.csdn.net/yangshangwei/article/details/102583944
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                keepAliveLatch.countDown();
            }
        });
    }

    /** initialize native resources */
    public static void initializeNatives(Path tmpFile, boolean mlockAll, boolean systemCallFilter, boolean ctrlHandler) {
        final Logger logger = LogManager.getLogger(Bootstrap.class);

        //不能以 root 运行。
        // check if the user is running as root, and bail
        if (Natives.definitelyRunningAsRoot()) {
            throw new RuntimeException("can not run elasticsearch as root");
        }

        //尝试启用系统调用过滤器。
        // enable system call filter
        if (systemCallFilter) {
            Natives.tryInstallSystemCallFilter(tmpFile);
        }

        //尝试调用 mlockall，mlockall 会将进程使用的部分或者全部的地址空间锁定在物理内存中，防止其被交换到swap空间。
        // mlockall if requested
        if (mlockAll) {
            if (Constants.WINDOWS) {
               Natives.tryVirtualLock();
            } else {
               Natives.tryMlockall();
            }
        }
        //如果是运行在 Windows 的话，关闭事件的监听器。
        // listener for windows close event
        if (ctrlHandler) {
            Natives.addConsoleCtrlHandler(new ConsoleCtrlHandler() {
                @Override
                public boolean handle(int code) {
                    if (CTRL_CLOSE_EVENT == code) {
                        logger.info("running graceful exit on windows");
                        try {
                            Bootstrap.stop();
                        } catch (IOException e) {
                            throw new ElasticsearchException("failed to stop node", e);
                        }
                        return true;
                    }
                    return false;
                }
            });
        }

        // force remainder of JNA to be loaded (if available).
        try {
            JNAKernel32Library.getInstance();
        } catch (Exception ignored) {
            // we've already logged this.
        }
        //尝试设置最大线程数量、最大虚拟内存、最大文件 size。
        Natives.trySetMaxNumberOfThreads();
        Natives.trySetMaxSizeVirtualMemory();
        Natives.trySetMaxFileSize();
        //为 lucene 设置一个随机的 seed。
        // init lucene random seed. it will use /dev/urandom where available:
        StringHelper.randomId();
    }

    static void initializeProbes() {
        // Force probes to be loaded
        ProcessProbe.getInstance();
        OsProbe.getInstance();
        JvmInfo.jvmInfo();
    }

    /**
     * Environment: 执行初始化时所需的参数，或参数文件
     * @param addShutdownHook 是否需要JVM关闭时，执行相关的代码
     * @param environment 略
     * @throws BootstrapException 略
     */
    private void setup(boolean addShutdownHook, Environment environment) throws BootstrapException {
        Settings settings = environment.settings();
        //See: https://www.lishuo.net/read/elasticsearch/date-2023.05.24.16.58.21
        //spawnNativeControllers 的作用主要是尝试为每个模块（modules 目录下的模块）生成 native 控制器守护进程的。
        // 生成的进程将通过其 stdin、stdout 和 stderr 流保持与此 JVM 的连接，这个进程不应该写入任何数据到其 stdout 和 stderr，
        // 否则如果没有其他线程读取这些 output 数据的话，这个进程将会被阻塞，
        // 为了避免这种情况发生，可以继承 JVM 的 stdout 和 stderr（在标准安装中，它们会被重定向到文件）
        try {
            spawner.spawnNativeControllers(environment);
        } catch (IOException e) {
            throw new BootstrapException(e);
        }

        //本地资源初始化
        initializeNatives(
                environment.tmpFile(),
                BootstrapSettings.MEMORY_LOCK_SETTING.get(settings),
                BootstrapSettings.SYSTEM_CALL_FILTER_SETTING.get(settings),
                BootstrapSettings.CTRLHANDLER_SETTING.get(settings));
        //调用 initializeProbes() 进行初始化探针操作，主要用于操作系统负载监控、jvm 信息获取、进程相关信息获取。
        // initialize probes before the security manager is installed
        initializeProbes();
        //注册关闭资源的 ShutdownHook
        if (addShutdownHook) {
            // 一句话概括就是： ShutdownHook允许开发人员在JVM关闭时，执行相关的代码。
            //see: https://blog.csdn.net/yangshangwei/article/details/102583944
            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run() {
                    try {
                        IOUtils.close(node, spawner);//注册看一个 ShutdownHook，用于在系统关闭的时候关闭相关的 IO 流、日志上下文。
                        LoggerContext context = (LoggerContext) LogManager.getContext(false);
                        Configurator.shutdown(context);
                        if (node != null && node.awaitClose(10, TimeUnit.SECONDS) == false) {
                            throw new IllegalStateException("Node didn't stop within 10 seconds. " +
                                    "Any outstanding requests or tasks might get killed.");
                        }
                    } catch (IOException ex) {
                        throw new ElasticsearchException("failed to stop node", ex);
                    } catch (InterruptedException e) {
                        LogManager.getLogger(Bootstrap.class).warn("Thread got interrupted while waiting for the node to shutdown.");
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }

        try {
            // look for jar hell
            final Logger logger = LogManager.getLogger(JarHell.class);
            JarHell.checkJarHell(logger::debug); //通过调用 JarHell.checkJarHell 检查是否有重复的类
        } catch (IOException | URISyntaxException e) {
            throw new BootstrapException(e);
        }

        // Log ifconfig output before SecurityManager is installed
        IfConfig.logIfNecessary(); //在Debug 模式下以 ifconfig 格式输出网络信息

        // install SM after natives, shutdown hooks, etc.
        try {//加载安全管理器，进行权限认证 通过调用 Security.configure 函数进行安全管理器加载，进行权限认证操作：
            Security.configure(environment, BootstrapSettings.SECURITY_FILTER_BAD_DEFAULTS_SETTING.get(settings));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new BootstrapException(e);
        }

        //创建节点，代表一个lucene实例，并执行初始化
        //这里初始化具体处理用户请求的服务，开始根据配置 实例化对应的服务实例，并注册到 【Guice】 谷歌提供的轻量级 IOC 库
        node = new Node(environment) {
            //节点校验，仅初始化时，执行校验一次，校验内容：如内存大小校验
            @Override
            protected void validateNodeBeforeAcceptingRequests(
                final BootstrapContext context,
                final BoundTransportAddress boundTransportAddress, List<BootstrapCheck> checks) throws NodeValidationException {
                //接收外来请求之前，做一下检查工作：如堆内存大小检查
                BootstrapChecks.check(context, boundTransportAddress, checks);
            }
        };
    }

    static SecureSettings loadSecureSettings(Environment initialEnv) throws BootstrapException {
        final KeyStoreWrapper keystore;
        try {
            keystore = KeyStoreWrapper.load(initialEnv.configFile());
        } catch (IOException e) {
            throw new BootstrapException(e);
        }

        // loadSecureSettings 函数中进行加载 elasticsearch.keystore 中的安全配置，
        // 如果 elasticsearch.keystore 不存在，则进行创建并且保存相关信息，
        // 如果 elasticsearch.keystore 存在，则更新配置信息。
        try {
            if (keystore == null) {
                final KeyStoreWrapper keyStoreWrapper = KeyStoreWrapper.create();
                keyStoreWrapper.save(initialEnv.configFile(), new char[0]);
                return keyStoreWrapper;
            } else {
                keystore.decrypt(new char[0] /* TODO: read password from stdin */);
                KeyStoreWrapper.upgrade(keystore, initialEnv.configFile(), new char[0]);
            }
        } catch (Exception e) {
            throw new BootstrapException(e);
        }
        return keystore;
    }

    private static Environment createEnvironment(
            final Path pidFile,
            final SecureSettings secureSettings,
            final Settings initialSettings,
            final Path configPath) {
        Settings.Builder builder = Settings.builder();
        if (pidFile != null) {
            builder.put(Environment.NODE_PIDFILE_SETTING.getKey(), pidFile);
        }
        builder.put(initialSettings);
        if (secureSettings != null) {
            builder.setSecureSettings(secureSettings);
        }
        return InternalSettingsPreparer.prepareEnvironment(builder.build(), Collections.emptyMap(), configPath,
                // HOSTNAME is set by elasticsearch-env and elasticsearch-env.bat so it is always available
                () -> System.getenv("HOSTNAME"));
    }

    private void start() throws NodeValidationException {
        node.start();//Node.start 主要负责启动各个生命周期组件（LifecycleComponent）和从 Guice（ 也就是 injector）中的获取各个需要启动的服务类实例，然后调用它们的 start 方法。
        keepAliveThread.start();
    }

    static void stop() throws IOException {
        try {
            IOUtils.close(INSTANCE.node, INSTANCE.spawner);
            if (INSTANCE.node != null && INSTANCE.node.awaitClose(10, TimeUnit.SECONDS) == false) {
                throw new IllegalStateException("Node didn't stop within 10 seconds. Any outstanding requests or tasks might get killed.");
            }
        } catch (InterruptedException e) {
            LogManager.getLogger(Bootstrap.class).warn("Thread got interrupted while waiting for the node to shutdown.");
            Thread.currentThread().interrupt();
        } finally {
            INSTANCE.keepAliveLatch.countDown();
        }
    }

    /**
     * This method is invoked by {@link Elasticsearch#main(String[])} to startup elasticsearch.
     */
    static void init(
            final boolean foreground,
            final Path pidFile,
            final boolean quiet,
            final Environment initialEnv) throws BootstrapException, NodeValidationException, UserException {
        // force the class initializer for BootstrapInfo to run before
        // the security manager is installed
        BootstrapInfo.init();

        //初始化流程：起一个后台线程，证明实例存活，便于工具分析吧
        INSTANCE = new Bootstrap();

        //在 config 目录会生成一个 elasticsearch.keystore 文件，这个文件是用来保存一些敏感配置的
        //ES 大多数配置都是明文保存的，但是像 X-Pack 中的 security 配置需要进行加密保存，所以这些配置信息就是保存在 elasticsearch.keystore 中
        final SecureSettings keystore = loadSecureSettings(initialEnv);
        //为啥重新又生成？大概是因为配置隔离。而且执行初始化所需部分参数，initialEnv未读入
        final Environment environment = createEnvironment(pidFile, keystore, initialEnv.settings(), initialEnv.configFile());

        LogConfigurator.setNodeName(Node.NODE_NAME_SETTING.get(environment.settings()));
        try {
            //日志插件初始化。 调用 LogConfigurator.configure 加载 log4j2.properties 文件中的相关配置，然后配置 log4j 的属性。
            LogConfigurator.configure(environment);
        } catch (IOException e) {
            throw new BootstrapException(e);
        }
        if (JavaVersion.current().compareTo(JavaVersion.parse("11")) < 0) {
            final String message = String.format(
                            Locale.ROOT,
                            "future versions of Elasticsearch will require Java 11; " +
                                    "your Java version from [%s] does not meet this requirement",
                            System.getProperty("java.home"));
            new DeprecationLogger(LogManager.getLogger(Bootstrap.class)).deprecated(message);
        }
        if (environment.pidFile() != null) {
            try {
                //创建一个临时文件，保存进程ID，进程退出时，自动删除该文件
                PidFile.create(environment.pidFile(), true);
            } catch (IOException e) {
                throw new BootstrapException(e);
            }
        }

        final boolean closeStandardStreams = (foreground == false) || quiet;
        try {
            if (closeStandardStreams) {
                final Logger rootLogger = LogManager.getRootLogger();
                final Appender maybeConsoleAppender = Loggers.findAppender(rootLogger, ConsoleAppender.class);
                if (maybeConsoleAppender != null) {
                    Loggers.removeAppender(rootLogger, maybeConsoleAppender);
                }
                closeSystOut();
            }

            // fail if somebody replaced the lucene jars
            //检查lucene版本信息. 该函数通过版本号来检查 lucene 是否被替换了，如果 lucene 被替换将无法启动。
            checkLucene();
            //setDefaultUncaughtExceptionHandler 相当于全局的 catch ，用于捕获程序未捕获的异常，以避免程序终止。
            // install the default uncaught exception handler; must be done before security is
            // initialized as we do not want to grant the runtime permission
            // setDefaultUncaughtExceptionHandler
            //通过 Thread.setDefaultUncaughtExceptionHandler 设置了一个 ElasticsearchUncaughtExceptionHandler 未捕获异常处理程序。
            // Thread.UncaughtExceptionHandler 是当线程由于未捕获的异常而突然终止时调用的处理程序接口。
            // 在多线程的环境下，主线程无法捕捉其他线程产生的异常，这时需要通过实现 UncaughtExceptionHandler 来捕获其他线程产生但又未被捕获的异常。
            Thread.setDefaultUncaughtExceptionHandler(new ElasticsearchUncaughtExceptionHandler());

            //重点：
            //创建一个节点node，代表一个lucene实例
            //各种信息的打印输出和已经丢弃的旧版配置项的检查与提示。
            //启动插件服务，加载各个插件和模块。
            //创建节点的运行环境。
            //创建线程池和 NodeClient 来执行各个Action。
            //初始化 HTTP Handlers 来处理 REST 请求。
            INSTANCE.setup(true, environment);

            try {
                // any secure settings must be read during node construction
                IOUtils.close(keystore);
            } catch (IOException e) {
                throw new BootstrapException(e);
            }

            //创建好node，即初始化本地服务后，再对外暴露端口接收请求！！！
            //通讯协议初始化，这里默认是：netty
            //监听端口，开始接收用户请求
            INSTANCE.start();

            // We don't close stderr if `--quiet` is passed, because that
            // hides fatal startup errors. For example, if Elasticsearch is
            // running via systemd, the init script only specifies
            // `--quiet`, not `-d`, so we want users to be able to see
            // startup errors via journalctl.
            if (foreground == false) {
                closeSysError();
            }
        } catch (NodeValidationException | RuntimeException e) {
            // disable console logging, so user does not see the exception twice (jvm will show it already)
            final Logger rootLogger = LogManager.getRootLogger();
            final Appender maybeConsoleAppender = Loggers.findAppender(rootLogger, ConsoleAppender.class);
            if (foreground && maybeConsoleAppender != null) {
                Loggers.removeAppender(rootLogger, maybeConsoleAppender);
            }
            Logger logger = LogManager.getLogger(Bootstrap.class);
            // HACK, it sucks to do this, but we will run users out of disk space otherwise
            if (e instanceof CreationException) {
                // guice: log the shortened exc to the log file
                ByteArrayOutputStream os = new ByteArrayOutputStream();
                PrintStream ps = null;
                try {
                    ps = new PrintStream(os, false, "UTF-8");
                } catch (UnsupportedEncodingException uee) {
                    assert false;
                    e.addSuppressed(uee);
                }
                new StartupException(e).printStackTrace(ps);
                ps.flush();
                try {
                    logger.error("Guice Exception: {}", os.toString("UTF-8"));
                } catch (UnsupportedEncodingException uee) {
                    assert false;
                    e.addSuppressed(uee);
                }
            } else if (e instanceof NodeValidationException) {
                logger.error("node validation exception\n{}", e.getMessage());
            } else {
                // full exception
                logger.error("Exception", e);
            }
            // re-enable it if appropriate, so they can see any logging during the shutdown process
            if (foreground && maybeConsoleAppender != null) {
                Loggers.addAppender(rootLogger, maybeConsoleAppender);
            }

            throw e;
        }
    }

    @SuppressForbidden(reason = "System#out")
    private static void closeSystOut() {
        System.out.close();
    }

    @SuppressForbidden(reason = "System#err")
    private static void closeSysError() {
        System.err.close();
    }

    private static void checkLucene() {
        if (Version.CURRENT.luceneVersion.equals(org.apache.lucene.util.Version.LATEST) == false) {
            throw new AssertionError("Lucene version mismatch this version of Elasticsearch requires lucene version ["
                + Version.CURRENT.luceneVersion + "]  but the current lucene version is [" + org.apache.lucene.util.Version.LATEST + "]");
        }
    }

}
