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

import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.OptionSpecBuilder;
import joptsimple.util.PathConverter;
import org.elasticsearch.Build;
import org.elasticsearch.cli.EnvironmentAwareCommand;
import org.elasticsearch.cli.ExitCodes;
import org.elasticsearch.cli.Terminal;
import org.elasticsearch.cli.UserException;
import org.elasticsearch.common.logging.LogConfigurator;
import org.elasticsearch.env.Environment;
import org.elasticsearch.monitor.jvm.JvmInfo;
import org.elasticsearch.node.NodeValidationException;

import java.io.IOException;
import java.nio.file.Path;
import java.security.Permission;
import java.security.Security;
import java.util.Arrays;
import java.util.Locale;

/**
 * This class starts elasticsearch.
 * Elasticsearch 类继承了 EnvironmentAwareCommand，而 EnvironmentAwareCommand 继承了 Command
 * 所以 Elasticsearch 可以解析命令行参数，同时 Elasticsearch 还负责加载配置等工作
 */
class Elasticsearch extends EnvironmentAwareCommand {

    private final OptionSpecBuilder versionOption;
    private final OptionSpecBuilder daemonizeOption;
    private final OptionSpec<Path> pidfileOption;
    private final OptionSpecBuilder quietOption;

    // visible for testing
    Elasticsearch() {
//        super("Starts Elasticsearch", new Runnable() {
//            @Override
//            public void run() {
//                //等同下面代码，啥也不干
//            }
//        });
        //调用 Elasticsearch 构造方法时，主要解析了一些 命令行传入的参数，如 V（版本）、d（后台运行）、p（pid文件）、q（退出）等，
        // 需要注意的是此处的 super 函数是啥都没有干的(大括号)。而在 super（EnvironmentAwareCommand构造函数）里，主要是设置 settingOption，所以命令行的 ES 参数需要以 -Ees.path.home=/es/home 这样子来设定。
        super("Starts Elasticsearch", () -> {}); // we configure logging later so we override the base class from configuring logging
        //调用parser.acceptsAll将 “命令行的参数” 保存到 parser的属性里
        //沿着以上的super(),可追溯到 Command类的构造函数，即可看到parser的初始化过程

        //java 命令行解析工具
        //链接：https://www.javacodegeeks.com/2017/07/java-command-line-interfaces-part-6-jopt-simple.html#google_vignette
        //官网链接：http://jopt-simple.github.io/jopt-simple/examples.html
        versionOption = parser.acceptsAll(Arrays.asList("V", "version"),
            "Prints Elasticsearch version information and exits");
        daemonizeOption = parser.acceptsAll(Arrays.asList("d", "daemonize"),
            "Starts Elasticsearch in the background")
            .availableUnless(versionOption);
        pidfileOption = parser.acceptsAll(Arrays.asList("p", "pidfile"),
            "Creates a pid file in the specified path on start")
            .availableUnless(versionOption)
            .withRequiredArg()
            .withValuesConvertedBy(new PathConverter());
        quietOption = parser.acceptsAll(Arrays.asList("q", "quiet"),
            "Turns off standard output/error streams logging in console")
            .availableUnless(versionOption)
            .availableUnless(daemonizeOption);
    }

    /**
     * Main entry point for starting elasticsearch //一个 REST 请求最终会在对应的 Transport*Action 的类中处理
     */
    public static void main(final String[] args) throws Exception {
        //“格物”致知
        overrideDnsCachePolicyProperties(); //重写 DNS 缓存时间
        /*
         * We want the JVM to think there is a security manager installed so that if internal policy decisions that would be based on the
         * presence of a security manager or lack thereof act as if there is a security manager present (e.g., DNS cache policy). This
         * forces such policies to take effect immediately.
         */
        //创建空的 SecurityManager 安全管理器,授予所有权限，在后续执行中再为这个安全管理器设置所需要的权限
        System.setSecurityManager(new SecurityManager() {
            @Override
            public void checkPermission(Permission perm) {
                // grant all permissions so that we can later set the security manager to the one that we want
            }

        });
        //注册错误日志监听器,安装监听器可以记录 在加载 log4j 配置前的启动错误信息，如果启动有错误将会停止启动并且显示出来
        LogConfigurator.registerErrorListener();
        //猜想：创建一个实例，调用main，目的是适配单机环境下启动多个实例吧！！
        final Elasticsearch elasticsearch = new Elasticsearch();
        int status = main(args, elasticsearch, Terminal.DEFAULT);
        if (status != ExitCodes.OK) {
            final String basePath = System.getProperty("es.logs.base_path");
            // It's possible to fail before logging has been configured, in which case there's no point
            // suggesting that the user look in the log file.
            if (basePath != null) {
                Terminal.DEFAULT.errorPrintln(
                    "ERROR: Elasticsearch did not exit normally - check the logs at "
                        + basePath
                        + System.getProperty("file.separator")
                        + System.getProperty("es.logs.cluster_name") + ".log"
                );
            }
            exit(status);
        }
    }

    private static void overrideDnsCachePolicyProperties() {
        for (final String property : new String[] {"networkaddress.cache.ttl", "networkaddress.cache.negative.ttl" }) {
            final String overrideProperty = "es." + property;
            final String overrideValue = System.getProperty(overrideProperty);
            if (overrideValue != null) {
                try {
                    // round-trip the property to an integer and back to a string to ensure that it parses properly
                    Security.setProperty(property, Integer.toString(Integer.valueOf(overrideValue)));
                } catch (final NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "failed to parse [" + overrideProperty + "] with value [" + overrideValue + "]", e);
                }
            }
        }
    }

    static int main(final String[] args, final Elasticsearch elasticsearch, final Terminal terminal) throws Exception {
        return elasticsearch.main(args, terminal);
    }

    /**
     * Environment 其实保存的是：配置文件路径，以及配置文件的内容，重点是配置参数！！！
     * 重点关注Environment.settings属性即可
     * @param terminal 略
     * @param options 略
     * @param env 配置参数值，通过此对象可以拿到任意配置参数值
     * @throws UserException 略
     */
    @Override
    protected void execute(Terminal terminal, OptionSet options, Environment env) throws UserException {
        if (options.nonOptionArguments().isEmpty() == false) {
            throw new UserException(ExitCodes.USAGE, "Positional arguments not allowed, found " + options.nonOptionArguments());
        }
        //1、输出版本信息. program arguments: 添加 --version，即会触发
        if (options.has(versionOption)) {
            final String versionOutput = String.format(
                Locale.ROOT,
                "Version: %s, Build: %s/%s/%s/%s, JVM: %s",
                Build.CURRENT.getQualifiedVersion(),
                Build.CURRENT.flavor().displayName(),
                Build.CURRENT.type().displayName(),
                Build.CURRENT.hash(),
                Build.CURRENT.date(),
                JvmInfo.jvmInfo().version()
            );
            terminal.println(versionOutput);
            return;
        }
        //是否后台运行. program arguments: 添加 -d 或 -daemonize，即会触发
        final boolean daemonize = options.has(daemonizeOption);
        final Path pidFile = pidfileOption.value(options);//当前实例ID存储路径
        final boolean quiet = options.has(quietOption);

        // a misconfigured java.io.tmpdir can cause hard-to-diagnose problems later, so reject it immediately
        try {//检测临时目录是否存在
            env.validateTmpFile();
        } catch (IOException e) {
            throw new UserException(ExitCodes.CONFIG, e.getMessage());
        }

        try {
            init(daemonize, pidFile, quiet, env);
        } catch (NodeValidationException e) {
            throw new UserException(ExitCodes.CONFIG, e.getMessage());
        }
    }

    void init(final boolean daemonize, final Path pidFile, final boolean quiet, Environment initialEnv)
        throws NodeValidationException, UserException {
        try {
            //环境准备好后，开始执行es初始化工作
            Bootstrap.init(!daemonize, pidFile, quiet, initialEnv);
        } catch (BootstrapException | RuntimeException e) {
            // format exceptions to the console in a special way
            // to avoid 2MB stacktraces from guice, etc.
            throw new StartupException(e);
        }
    }

    /**
     * Required method that's called by Apache Commons procrun when
     * running as a service on Windows, when the service is stopped.
     *
     * window版本es才生效，目标：资源释放
     *
     * http://commons.apache.org/proper/commons-daemon/procrun.html
     *
     * NOTE: If this method is renamed and/or moved, make sure to
     * update elasticsearch-service.bat!
     */
    static void close(String[] args) throws IOException {
        Bootstrap.stop();
    }

}
