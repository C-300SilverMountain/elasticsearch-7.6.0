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

package org.elasticsearch.node;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.util.Constants;
import org.apache.lucene.util.SetOnce;
import org.elasticsearch.Assertions;
import org.elasticsearch.Build;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchTimeoutException;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionModule;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.action.admin.cluster.snapshots.status.TransportNodesSnapshotsStatus;
import org.elasticsearch.action.search.SearchExecutionStatsCollector;
import org.elasticsearch.action.search.SearchPhaseController;
import org.elasticsearch.action.search.SearchTransportService;
import org.elasticsearch.action.support.TransportAction;
import org.elasticsearch.action.update.UpdateHelper;
import org.elasticsearch.bootstrap.BootstrapCheck;
import org.elasticsearch.bootstrap.BootstrapContext;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.cluster.ClusterInfoService;
import org.elasticsearch.cluster.ClusterModule;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateObserver;
import org.elasticsearch.cluster.InternalClusterInfoService;
import org.elasticsearch.cluster.NodeConnectionsService;
import org.elasticsearch.cluster.action.index.MappingUpdatedAction;
import org.elasticsearch.cluster.metadata.AliasValidator;
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.metadata.MetaDataCreateIndexService;
import org.elasticsearch.cluster.metadata.MetaDataIndexUpgradeService;
import org.elasticsearch.cluster.metadata.TemplateUpgradeService;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodeRole;
import org.elasticsearch.cluster.routing.BatchedRerouteService;
import org.elasticsearch.cluster.routing.RerouteService;
import org.elasticsearch.cluster.routing.allocation.DiskThresholdMonitor;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.StopWatch;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.component.Lifecycle;
import org.elasticsearch.common.component.LifecycleComponent;
import org.elasticsearch.common.inject.*;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.lease.Releasables;
import org.elasticsearch.common.logging.DeprecationLogger;
import org.elasticsearch.common.logging.NodeAndClusterIdStateListener;
import org.elasticsearch.common.network.NetworkAddress;
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.ConsistentSettingsService;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.SettingUpgrader;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsModule;
import org.elasticsearch.common.transport.BoundTransportAddress;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.PageCacheRecycler;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.core.internal.io.IOUtils;
import org.elasticsearch.discovery.Discovery;
import org.elasticsearch.discovery.DiscoveryModule;
import org.elasticsearch.discovery.DiscoverySettings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.env.NodeMetaData;
import org.elasticsearch.gateway.GatewayAllocator;
import org.elasticsearch.gateway.GatewayMetaState;
import org.elasticsearch.gateway.GatewayModule;
import org.elasticsearch.gateway.GatewayService;
import org.elasticsearch.gateway.PersistedClusterStateService;
import org.elasticsearch.gateway.MetaStateService;
import org.elasticsearch.http.HttpServerTransport;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AnalysisRegistry;
import org.elasticsearch.index.engine.EngineFactory;
import org.elasticsearch.indices.IndicesModule;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.indices.analysis.AnalysisModule;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.indices.breaker.HierarchyCircuitBreakerService;
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService;
import org.elasticsearch.indices.cluster.IndicesClusterStateService;
import org.elasticsearch.indices.recovery.PeerRecoverySourceService;
import org.elasticsearch.indices.recovery.PeerRecoveryTargetService;
import org.elasticsearch.indices.recovery.RecoverySettings;
import org.elasticsearch.indices.store.IndicesStore;
import org.elasticsearch.ingest.IngestService;
import org.elasticsearch.monitor.MonitorService;
import org.elasticsearch.monitor.jvm.JvmInfo;
import org.elasticsearch.persistent.PersistentTasksClusterService;
import org.elasticsearch.persistent.PersistentTasksExecutor;
import org.elasticsearch.persistent.PersistentTasksExecutorRegistry;
import org.elasticsearch.persistent.PersistentTasksService;
import org.elasticsearch.plugins.ActionPlugin;
import org.elasticsearch.plugins.AnalysisPlugin;
import org.elasticsearch.plugins.ClusterPlugin;
import org.elasticsearch.plugins.DiscoveryPlugin;
import org.elasticsearch.plugins.EnginePlugin;
import org.elasticsearch.plugins.IndexStorePlugin;
import org.elasticsearch.plugins.IngestPlugin;
import org.elasticsearch.plugins.MapperPlugin;
import org.elasticsearch.plugins.MetaDataUpgrader;
import org.elasticsearch.plugins.NetworkPlugin;
import org.elasticsearch.plugins.PersistentTaskPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.PluginsService;
import org.elasticsearch.plugins.RepositoryPlugin;
import org.elasticsearch.plugins.ScriptPlugin;
import org.elasticsearch.plugins.SearchPlugin;
import org.elasticsearch.repositories.RepositoriesModule;
import org.elasticsearch.repositories.RepositoriesService;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.script.ScriptModule;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.search.SearchModule;
import org.elasticsearch.search.SearchService;
import org.elasticsearch.search.fetch.FetchPhase;
import org.elasticsearch.snapshots.RestoreService;
import org.elasticsearch.snapshots.SnapshotShardsService;
import org.elasticsearch.snapshots.SnapshotsService;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.tasks.TaskResultsService;
import org.elasticsearch.threadpool.ExecutorBuilder;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.Transport;
import org.elasticsearch.transport.TransportInterceptor;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.usage.UsageService;
import org.elasticsearch.watcher.ResourceWatcherService;

import javax.net.ssl.SNIHostName;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

/**
 * A node represent a node within a cluster ({@code cluster.name}). The {@link #client()} can be used
 * in order to use a {@link Client} to perform actions/operations against the cluster.
 * Closeable接口优雅方便的自动关闭资源：执行完try-catch后，自动执行close方法进行资源释放
 * https://blog.csdn.net/nuomizhende45/article/details/105924637
 */
public class Node implements Closeable {
    public static final Setting<Boolean> WRITE_PORTS_FILE_SETTING =
        Setting.boolSetting("node.portsfile", false, Property.NodeScope);
    public static final Setting<Boolean> NODE_DATA_SETTING = Setting.boolSetting("node.data", true, Property.NodeScope);
    public static final Setting<Boolean> NODE_MASTER_SETTING =
        Setting.boolSetting("node.master", true, Property.NodeScope);

//     * ingest节点说明：
//     * https://juejin.cn/post/6844903873153335309
//     * https://www.lishuo.net/read/elasticsearch/date-2023.05.23.09.08.54
//     * 主要的节点类型如下。
//     * 主节点（Master）。主节点在整个集群是唯一的，Master 从有资格进行选举的节点（Master Eligible）中选举出来。主节点主要负责管理集群变更、元数据的更改。如果要类比的话，主节点像是公司的总经办。
//     * 数据节点（Data Node）。其负责保存数据，要扩充存储时候需要扩展这类节点。数据节点还负责执行数据相关的操作，如：搜索、聚合、CURD 等。所以对节点机器的 CPU、内存、I/O 要求都比较高。
//     * 协调节点（Coordinating Node）。负责接受客户端的请求，将请求路由到对应的节点进行处理，并且把最终结果汇总到一起返回给客户端。因为需要处理结果集和对其进行排序，需要较高的 CPU 和内存资源。
//     * 预处理节点（Ingest Node）。预处理操作允许在写入文档前通过定义好的一些 processors（处理器）和管道对数据进行转换。默认情况下节点启动后就是预处理节点。
//     * Hot & Warm Node。不同硬件配置的 Data Node，用来实现 Hot & Warm 架构的节点，有利于降低集群部署成本。例如，在硬件资源好的机器中部署 Hot 类型的数据节点，而在硬件资源一般的机器上部署 Warm Node 节点。
//     * 在生产环境中建议将每个节点设置为单一角色。如果业务量或者并发量不大的情况下，为了节省成本可以调整为一个节点多种角色的集群。在开发环境中的话，为了节省资源，一个节点可以设置多种角色
//

    public static final Setting<Boolean> NODE_INGEST_SETTING =
        Setting.boolSetting("node.ingest", true, Property.NodeScope);

    /**
    * controls whether the node is allowed to persist things like metadata to disk
    * Note that this does not control whether the node stores actual indices (see
    * {@link #NODE_DATA_SETTING}). However, if this is false, {@link #NODE_DATA_SETTING}
    * and {@link #NODE_MASTER_SETTING} must also be false.
    *
    */
    public static final Setting<Boolean> NODE_LOCAL_STORAGE_SETTING = Setting.boolSetting("node.local_storage", true, Property.NodeScope);
    public static final Setting<String> NODE_NAME_SETTING = Setting.simpleString("node.name", Property.NodeScope);
    public static final Setting.AffixSetting<String> NODE_ATTRIBUTES = Setting.prefixKeySetting("node.attr.", (key) ->
        new Setting<>(key, "", (value) -> {
            if (value.length() > 0
                && (Character.isWhitespace(value.charAt(0)) || Character.isWhitespace(value.charAt(value.length() - 1)))) {
                throw new IllegalArgumentException(key + " cannot have leading or trailing whitespace " +
                    "[" + value + "]");
            }
            if (value.length() > 0 && "node.attr.server_name".equals(key)) {
                try {
                    new SNIHostName(value);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("invalid node.attr.server_name [" + value + "]", e );
                }
            }
            return value;
        }, Property.NodeScope));
    public static final Setting<String> BREAKER_TYPE_KEY = new Setting<>("indices.breaker.type", "hierarchy", (s) -> {
        switch (s) {
            //层次断路器
            case "hierarchy":
            case "none":
                return s;
            default:
                throw new IllegalArgumentException("indices.breaker.type must be one of [hierarchy, none] but was: " + s);
        }
    }, Setting.Property.NodeScope);

    private static final String CLIENT_TYPE = "node";
    //设置节点的生命周期
    private final Lifecycle lifecycle = new Lifecycle();

    /**
     * Logger initialized in the ctor because if it were initialized statically
     * then it wouldn't get the node name.
     */
    private final Logger logger;
    private final Injector injector;
    private final Environment environment;
    private final NodeEnvironment nodeEnvironment;
    private final PluginsService pluginsService;
    private final NodeClient client;
    private final Collection<LifecycleComponent> pluginLifecycleComponents;
    private final LocalNodeFactory localNodeFactory;
    private final NodeService nodeService;

    public Node(Environment environment) {
        this(environment, Collections.emptyList(), true);
    }

    /**
     * Constructs a node
     *
     * @param environment                the environment for this node
     * @param classpathPlugins           the plugins to be loaded from the classpath
     * @param forbidPrivateIndexSettings whether or not private index settings are forbidden when creating an index; this is used in the
     *                                   test framework for tests that rely on being able to set private settings
     */
    protected Node(
            final Environment environment, Collection<Class<? extends Plugin>> classpathPlugins, boolean forbidPrivateIndexSettings) {
        //重要：Lifecycle代表节点生命周期，一共有四个状态值
        logger = LogManager.getLogger(Node.class);
        //当发生【异常】，需执行资源释放的【实例列表】
        final List<Closeable> resourcesToClose = new ArrayList<>(); // register everything we need to release in the case of an error
        boolean success = false;
        try {
            Settings tmpSettings = Settings.builder().put(environment.settings())
                .put(Client.CLIENT_TYPE_SETTING_S.getKey(), CLIENT_TYPE).build();

            //准备创建节点所需的参数值，且对数据目录加锁
            //创建 NodeEnvironment,此过程会生成 NodeId
            nodeEnvironment = new NodeEnvironment(tmpSettings, environment);
            resourcesToClose.add(nodeEnvironment);
            logger.info("node name [{}], node ID [{}], cluster name [{}]",
                    NODE_NAME_SETTING.get(tmpSettings), nodeEnvironment.nodeId(),
                    ClusterName.CLUSTER_NAME_SETTING.get(tmpSettings).value());
            //各种信息的打印输出和已经丢弃的旧版配置项的检查与提示
            //打印 JVM 相关的信息
            final JvmInfo jvmInfo = JvmInfo.jvmInfo();
            logger.info(
                "version[{}], pid[{}], build[{}/{}/{}/{}], OS[{}/{}/{}], JVM[{}/{}/{}/{}]",
                Build.CURRENT.getQualifiedVersion(),
                jvmInfo.pid(),
                Build.CURRENT.flavor().displayName(),
                Build.CURRENT.type().displayName(),
                Build.CURRENT.hash(),
                Build.CURRENT.date(),
                Constants.OS_NAME,
                Constants.OS_VERSION,
                Constants.OS_ARCH,
                Constants.JVM_VENDOR,
                Constants.JVM_NAME,
                Constants.JAVA_VERSION,
                Constants.JVM_VERSION);
            logger.info("JVM home [{}]", System.getProperty("java.home"));
            logger.info("JVM arguments {}", Arrays.toString(jvmInfo.getInputArguments()));
            if (Build.CURRENT.isProductionRelease() == false) {
                logger.warn(
                    "version [{}] is a pre-release version of Elasticsearch and is not suitable for production",
                    Build.CURRENT.getQualifiedVersion());
            }

            if (logger.isDebugEnabled()) {
                logger.debug("using config [{}], data [{}], logs [{}], plugins [{}]",
                    environment.configFile(), Arrays.toString(environment.dataFiles()), environment.logsFile(), environment.pluginsFile());
            }
            // 三个存放插件的 【路径】：1、位于classpath下  2、module目录下（es内置）  3、plugins目录下
            // 平台插件化主要靠他，基本流程：1、读取插件配置信息  2、从jar包加载class，执行初始化
            // 创建插件服务。包含modulesDirectory（内置）和pluginsDirectory（用户自定义）
            // PluginsService 实例化的过程中主要是加载 modules 目录中的模块和加载 plugins 目录中已经安装的插件
            this.pluginsService = new PluginsService(tmpSettings, environment.configFile(), environment.modulesFile(),
                environment.pluginsFile(), classpathPlugins);

            final Settings settings = pluginsService.updatedSettings();
            //插件自定义的节点角色,插件的Plugin.getRoles方法可以自定义角色
            final Set<DiscoveryNodeRole> possibleRoles = Stream.concat(
                    DiscoveryNodeRole.BUILT_IN_ROLES.stream(),
                    pluginsService.filterPlugins(Plugin.class)
                            .stream()
                            .map(Plugin::getRoles)
                            .flatMap(Set::stream))
                    .collect(Collectors.toSet());
            DiscoveryNode.setPossibleRoles(possibleRoles);
            localNodeFactory = new LocalNodeFactory(settings, nodeEnvironment.nodeId());

            // create the environment based on the finalized (processed) view of the settings
            // this is just to makes sure that people get the same settings, no matter where they ask them from
            this.environment = new Environment(settings, environment.configFile()); //创建节点运行需要的运行环境
            //通过 Environment.assertEquivalent 函数来保证启动过程中配置没有被更改。
            Environment.assertEquivalent(environment, this.environment);
            // 插件自定义的线程池
            final List<ExecutorBuilder<?>> executorBuilders = pluginsService.getExecutorBuilders(settings);
            //ES 线程池。 ThreadPool 中定义了 4 中线程池类型
            //线程池的类型有：
            //direct，执行器不支持关闭的线程。
            //fixed，线程池拥有固定数量的线程，当一个任务无法分配一条线程时会被排队处理。
            //fixed_auto_queue_size，和 fixed 类似，但是任务队列会根据 Little’s Law 自动调整。8.0 后将被移除。
            //scaling， 线程池中线程的数量可变，线程的数量在 core 和 max 间变化，使用 keep_alive 参数可以控制线程在线程池中的空闲时间。
            // see: https://www.elastic.co/guide/en/elasticsearch/reference/7.13/modules-threadpool.html#fixed-thread-pool
            final ThreadPool threadPool = new ThreadPool(settings, executorBuilders.toArray(new ExecutorBuilder[0]));
            resourcesToClose.add(() -> ThreadPool.terminate(threadPool, 10, TimeUnit.SECONDS));
            // adds the context to the DeprecationLogger so that it does not need to be injected everywhere
            DeprecationLogger.setThreadContext(threadPool.getThreadContext());
            resourcesToClose.add(() -> DeprecationLogger.removeThreadContext(threadPool.getThreadContext()));

            final List<Setting<?>> additionalSettings = new ArrayList<>(pluginsService.getPluginSettings());
            final List<String> additionalSettingsFilter = new ArrayList<>(pluginsService.getPluginSettingsFilter());
            for (final ExecutorBuilder<?> builder : threadPool.builders()) {
                additionalSettings.addAll(builder.getRegisteredSettings());
            }
            //NodeClient 用于执行本地的 actions，并且各个节点间的交互是通过 NodeClient 来进行
            //action 的类型定义在 ActionType
            client = new NodeClient(settings, threadPool);
            final ResourceWatcherService resourceWatcherService = new ResourceWatcherService(settings, threadPool);

            //脚本插件模块，通常实现接口：ScriptPlugin
            final ScriptModule scriptModule = new ScriptModule(settings, pluginsService.filterPlugins(ScriptPlugin.class));
            //语义理解插件
            AnalysisModule analysisModule = new AnalysisModule(this.environment, pluginsService.filterPlugins(AnalysisPlugin.class));
            // this is as early as we can validate settings at this point. we already pass them to ScriptModule as well as ThreadPool
            // so we might be late here already

            final Set<SettingUpgrader<?>> settingsUpgraders = pluginsService.filterPlugins(Plugin.class)
                    .stream()
                    .map(Plugin::getSettingUpgraders)
                    .flatMap(List::stream)
                    .collect(Collectors.toSet());

            final SettingsModule settingsModule =
                    new SettingsModule(settings, additionalSettings, additionalSettingsFilter, settingsUpgraders);
            scriptModule.registerClusterSettingsListeners(settingsModule.getClusterSettings());
            resourcesToClose.add(resourceWatcherService);
            final NetworkService networkService = new NetworkService(
                getCustomNameResolvers(pluginsService.filterPlugins(DiscoveryPlugin.class)));

            //集群管理插件
            List<ClusterPlugin> clusterPlugins = pluginsService.filterPlugins(ClusterPlugin.class);
            final ClusterService clusterService = new ClusterService(settings, settingsModule.getClusterSettings(), threadPool);
            clusterService.addStateApplier(scriptModule.getScriptService());
            resourcesToClose.add(clusterService);
            clusterService.addLocalNodeMasterListener(
                    new ConsistentSettingsService(settings, clusterService, settingsModule.getConsistentSettings())
                            .newHashPublisher());
            //写入数据时：数据预处理服务
            final IngestService ingestService = new IngestService(clusterService, threadPool, this.environment,
                scriptModule.getScriptService(), analysisModule.getAnalysisRegistry(),
                pluginsService.filterPlugins(IngestPlugin.class), client);
            final ClusterInfoService clusterInfoService = newClusterInfoService(settings, clusterService, threadPool, client);
            final UsageService usageService = new UsageService();

            ModulesBuilder modules = new ModulesBuilder();
            // plugin modules must be added here, before others or we can get crazy injection errors...
            for (Module pluginModule : pluginsService.createGuiceModules()) {
                modules.add(pluginModule);
            }
            //系统监控服务，如监控jvm、操作系统资源等使用情况
            final MonitorService monitorService = new MonitorService(settings, nodeEnvironment, threadPool, clusterInfoService);
            ClusterModule clusterModule = new ClusterModule(settings, clusterService, clusterPlugins, clusterInfoService);
            modules.add(clusterModule);
            IndicesModule indicesModule = new IndicesModule(pluginsService.filterPlugins(MapperPlugin.class));
            modules.add(indicesModule);
            //搜索模块
            SearchModule searchModule = new SearchModule(settings, false, pluginsService.filterPlugins(SearchPlugin.class));
            //重点：创建断路器；用于预防OOM，注：并不能百分百预防，因为断路器并不是实时计算堆剩余大小，而是通过估算值进行计算。
            CircuitBreakerService circuitBreakerService = createCircuitBreakerService(settingsModule.getSettings(),
                settingsModule.getClusterSettings());
            resourcesToClose.add(circuitBreakerService);
            modules.add(new GatewayModule());

            // 缓存收集器
            PageCacheRecycler pageCacheRecycler = createPageCacheRecycler(settings);
            BigArrays bigArrays = createBigArrays(pageCacheRecycler, circuitBreakerService);
            modules.add(settingsModule);
            List<NamedWriteableRegistry.Entry> namedWriteables = Stream.of(
                NetworkModule.getNamedWriteables().stream(),
                indicesModule.getNamedWriteables().stream(),
                searchModule.getNamedWriteables().stream(),
                pluginsService.filterPlugins(Plugin.class).stream()
                    .flatMap(p -> p.getNamedWriteables().stream()),
                ClusterModule.getNamedWriteables().stream())
                .flatMap(Function.identity()).collect(Collectors.toList());
            final NamedWriteableRegistry namedWriteableRegistry = new NamedWriteableRegistry(namedWriteables);
            NamedXContentRegistry xContentRegistry = new NamedXContentRegistry(Stream.of(
                NetworkModule.getNamedXContents().stream(),
                IndicesModule.getNamedXContents().stream(),
                searchModule.getNamedXContents().stream(),
                pluginsService.filterPlugins(Plugin.class).stream()
                    .flatMap(p -> p.getNamedXContent().stream()),
                ClusterModule.getNamedXWriteables().stream())
                .flatMap(Function.identity()).collect(toList()));

            final MetaStateService metaStateService = new MetaStateService(nodeEnvironment, xContentRegistry);
            //集群状态持久化服务
            final PersistedClusterStateService lucenePersistedStateFactory
                = new PersistedClusterStateService(nodeEnvironment, xContentRegistry, bigArrays, clusterService.getClusterSettings(),
                threadPool::relativeTimeInMillis);

            // collect engine factory providers from server and from plugins
            final Collection<EnginePlugin> enginePlugins = pluginsService.filterPlugins(EnginePlugin.class);
            final Collection<Function<IndexSettings, Optional<EngineFactory>>> engineFactoryProviders =
                    Stream.concat(
                            indicesModule.getEngineFactories().stream(),
                            enginePlugins.stream().map(plugin -> plugin::getEngineFactory))
                    .collect(Collectors.toList());

            final Map<String, IndexStorePlugin.DirectoryFactory> indexStoreFactories =
                    pluginsService.filterPlugins(IndexStorePlugin.class)
                            .stream()
                            .map(IndexStorePlugin::getDirectoryFactories)
                            .flatMap(m -> m.entrySet().stream())
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            final IndicesService indicesService =
                new IndicesService(settings, pluginsService, nodeEnvironment, xContentRegistry, analysisModule.getAnalysisRegistry(),
                    clusterModule.getIndexNameExpressionResolver(), indicesModule.getMapperRegistry(), namedWriteableRegistry,
                    threadPool, settingsModule.getIndexScopedSettings(), circuitBreakerService, bigArrays, scriptModule.getScriptService(),
                    clusterService, client, metaStateService, engineFactoryProviders, indexStoreFactories);

            final AliasValidator aliasValidator = new AliasValidator();

            final MetaDataCreateIndexService metaDataCreateIndexService = new MetaDataCreateIndexService(
                    settings,
                    clusterService,
                    indicesService,
                    clusterModule.getAllocationService(),
                    aliasValidator,
                    environment,
                    settingsModule.getIndexScopedSettings(),
                    threadPool,
                    xContentRegistry,
                    forbidPrivateIndexSettings);

            Collection<Object> pluginComponents = pluginsService.filterPlugins(Plugin.class).stream()
                .flatMap(p -> p.createComponents(client, clusterService, threadPool, resourceWatcherService,
                                                 scriptModule.getScriptService(), xContentRegistry, environment, nodeEnvironment,
                                                 namedWriteableRegistry).stream())
                .collect(Collectors.toList());
            // 此构造函数会: new RestController实例
            ActionModule actionModule = new ActionModule(false, settings, clusterModule.getIndexNameExpressionResolver(),
                settingsModule.getIndexScopedSettings(), settingsModule.getClusterSettings(), settingsModule.getSettingsFilter(),
                threadPool, pluginsService.filterPlugins(ActionPlugin.class), client, circuitBreakerService, usageService, clusterService);
            modules.add(actionModule);

            //请求总分发器：处理请求的类名以Action结尾：如 *Action，这些类都会注册到RestController，key为路径，value为*Action；
            final RestController restController = actionModule.getRestController();
            final NetworkModule networkModule = new NetworkModule(settings, false, pluginsService.filterPlugins(NetworkPlugin.class),
                threadPool, bigArrays, pageCacheRecycler, circuitBreakerService, namedWriteableRegistry, xContentRegistry,
                networkService, restController);
            Collection<UnaryOperator<Map<String, IndexTemplateMetaData>>> indexTemplateMetaDataUpgraders =
                pluginsService.filterPlugins(Plugin.class).stream()
                    .map(Plugin::getIndexTemplateMetaDataUpgrader)
                    .collect(Collectors.toList());
            final MetaDataUpgrader metaDataUpgrader = new MetaDataUpgrader(indexTemplateMetaDataUpgraders);
            final MetaDataIndexUpgradeService metaDataIndexUpgradeService = new MetaDataIndexUpgradeService(settings, xContentRegistry,
                indicesModule.getMapperRegistry(), settingsModule.getIndexScopedSettings());
            new TemplateUpgradeService(client, clusterService, threadPool, indexTemplateMetaDataUpgraders);
            final Transport transport = networkModule.getTransportSupplier().get();
            Set<String> taskHeaders = Stream.concat(
                pluginsService.filterPlugins(ActionPlugin.class).stream().flatMap(p -> p.getTaskHeaders().stream()),
                Stream.of(Task.X_OPAQUE_ID)
            ).collect(Collectors.toSet());
            //初始化请求客户端，类似httpclient，用于召回
            final TransportService transportService = newTransportService(settings, transport, threadPool,
                networkModule.getTransportInterceptor(), localNodeFactory, settingsModule.getClusterSettings(), taskHeaders);
            final GatewayMetaState gatewayMetaState = new GatewayMetaState();
            final ResponseCollectorService responseCollectorService = new ResponseCollectorService(clusterService);
            final SearchTransportService searchTransportService =  new SearchTransportService(transportService,
                SearchExecutionStatsCollector.makeWrapper(responseCollectorService));
            //初始化服务端，监听端口，接收此端口的请求
            final HttpServerTransport httpServerTransport = newHttpTransport(networkModule);


            RepositoriesModule repositoriesModule = new RepositoriesModule(this.environment,
                pluginsService.filterPlugins(RepositoryPlugin.class), transportService, clusterService, threadPool, xContentRegistry);
            RepositoriesService repositoryService = repositoriesModule.getRepositoryService();
            SnapshotsService snapshotsService = new SnapshotsService(settings, clusterService,
                clusterModule.getIndexNameExpressionResolver(), repositoryService, threadPool);
            SnapshotShardsService snapshotShardsService = new SnapshotShardsService(settings, clusterService, repositoryService,
                threadPool, transportService, indicesService, actionModule.getActionFilters(),
                clusterModule.getIndexNameExpressionResolver());
            TransportNodesSnapshotsStatus nodesSnapshotsStatus = new TransportNodesSnapshotsStatus(threadPool, clusterService,
                transportService, snapshotShardsService, actionModule.getActionFilters());
            RestoreService restoreService = new RestoreService(clusterService, repositoryService, clusterModule.getAllocationService(),
                metaDataCreateIndexService, metaDataIndexUpgradeService, clusterService.getClusterSettings());

            final RerouteService rerouteService
                = new BatchedRerouteService(clusterService, clusterModule.getAllocationService()::reroute);
            final DiskThresholdMonitor diskThresholdMonitor = new DiskThresholdMonitor(settings, clusterService::state,
                clusterService.getClusterSettings(), client, threadPool::relativeTimeInMillis, rerouteService);
            clusterInfoService.addListener(diskThresholdMonitor::onNewInfo);

            //Discovery模块负责发现集群中的节点，以及选择主节点。
            // ES支持多种不同Discovery类型选择，内置的实现有两种：Zen Discovery和Coordinator，其他的包括公有云平台亚马逊的EC2、谷歌的GCE等。
            //AzureDiscoreyPlugin：微软提供发现插件
            //Ec2DiscoveryPlugin：亚马逊提供的发现插件
            //GceDiscoveryPlugin：谷歌提供的发现插件
            //链接：https://www.jianshu.com/p/d3ad414ed4f7
            final DiscoveryModule discoveryModule = new DiscoveryModule(settings, threadPool, transportService, namedWriteableRegistry,
                networkService, clusterService.getMasterService(), clusterService.getClusterApplierService(),
                clusterService.getClusterSettings(), pluginsService.filterPlugins(DiscoveryPlugin.class),
                clusterModule.getAllocationService(), environment.configFile(), gatewayMetaState, rerouteService);

            this.nodeService = new NodeService(settings, threadPool, monitorService, discoveryModule.getDiscovery(),
                transportService, indicesService, pluginsService, circuitBreakerService, scriptModule.getScriptService(),
                httpServerTransport, ingestService, clusterService, settingsModule.getSettingsFilter(), responseCollectorService,
                searchTransportService);
            //搜索服务
            final SearchService searchService = newSearchService(clusterService, indicesService,
                threadPool, scriptModule.getScriptService(), bigArrays, searchModule.getFetchPhase(),
                responseCollectorService);

            final List<PersistentTasksExecutor<?>> tasksExecutors = pluginsService
                .filterPlugins(PersistentTaskPlugin.class).stream()
                .map(p -> p.getPersistentTasksExecutor(clusterService, threadPool, client, settingsModule))
                .flatMap(List::stream)
                .collect(toList());

            final PersistentTasksExecutorRegistry registry = new PersistentTasksExecutorRegistry(tasksExecutors);
            final PersistentTasksClusterService persistentTasksClusterService =
                new PersistentTasksClusterService(settings, registry, clusterService, threadPool);
            resourcesToClose.add(persistentTasksClusterService);
            final PersistentTasksService persistentTasksService = new PersistentTasksService(clusterService, threadPool, client);

            //绑定对应的对象到 Guice
            //ES 用到了 Guice 这个谷歌提供的轻量级 IOC 库，bind 和 createInjector 是其提供的基本功能。
            modules.add(b -> {
                    b.bind(Node.class).toInstance(this);
                    b.bind(NodeService.class).toInstance(nodeService);
                    b.bind(NamedXContentRegistry.class).toInstance(xContentRegistry);
                    b.bind(PluginsService.class).toInstance(pluginsService);
                    b.bind(Client.class).toInstance(client);
                    b.bind(NodeClient.class).toInstance(client);
                    b.bind(Environment.class).toInstance(this.environment);
                    b.bind(ThreadPool.class).toInstance(threadPool);
                    b.bind(NodeEnvironment.class).toInstance(nodeEnvironment);
                    b.bind(ResourceWatcherService.class).toInstance(resourceWatcherService);
                    b.bind(CircuitBreakerService.class).toInstance(circuitBreakerService);
                    b.bind(BigArrays.class).toInstance(bigArrays);
                    b.bind(PageCacheRecycler.class).toInstance(pageCacheRecycler);
                    b.bind(ScriptService.class).toInstance(scriptModule.getScriptService());
                    b.bind(AnalysisRegistry.class).toInstance(analysisModule.getAnalysisRegistry());
                    b.bind(IngestService.class).toInstance(ingestService);
                    b.bind(UsageService.class).toInstance(usageService);
                    b.bind(NamedWriteableRegistry.class).toInstance(namedWriteableRegistry);
                    b.bind(MetaDataUpgrader.class).toInstance(metaDataUpgrader);
                    b.bind(MetaStateService.class).toInstance(metaStateService);
                    b.bind(PersistedClusterStateService.class).toInstance(lucenePersistedStateFactory);
                    b.bind(IndicesService.class).toInstance(indicesService);
                    b.bind(AliasValidator.class).toInstance(aliasValidator);
                    b.bind(MetaDataCreateIndexService.class).toInstance(metaDataCreateIndexService);
                    b.bind(SearchService.class).toInstance(searchService);
                    b.bind(SearchTransportService.class).toInstance(searchTransportService);
                    b.bind(SearchPhaseController.class).toInstance(new SearchPhaseController(searchService::createReduceContext));
                    b.bind(Transport.class).toInstance(transport);
                    b.bind(TransportService.class).toInstance(transportService);
                    b.bind(NetworkService.class).toInstance(networkService);
                    b.bind(UpdateHelper.class).toInstance(new UpdateHelper(scriptModule.getScriptService()));
                    b.bind(MetaDataIndexUpgradeService.class).toInstance(metaDataIndexUpgradeService);
                    b.bind(ClusterInfoService.class).toInstance(clusterInfoService);
                    b.bind(GatewayMetaState.class).toInstance(gatewayMetaState);
                    b.bind(Discovery.class).toInstance(discoveryModule.getDiscovery());
                    {
                        RecoverySettings recoverySettings = new RecoverySettings(settings, settingsModule.getClusterSettings());
                        processRecoverySettings(settingsModule.getClusterSettings(), recoverySettings);
                        b.bind(PeerRecoverySourceService.class).toInstance(new PeerRecoverySourceService(transportService,
                                indicesService, recoverySettings));
                        b.bind(PeerRecoveryTargetService.class).toInstance(new PeerRecoveryTargetService(threadPool,
                                transportService, recoverySettings, clusterService));
                    }
                    b.bind(HttpServerTransport.class).toInstance(httpServerTransport);
                    pluginComponents.stream().forEach(p -> b.bind((Class) p.getClass()).toInstance(p));
                    b.bind(PersistentTasksService.class).toInstance(persistentTasksService);
                    b.bind(PersistentTasksClusterService.class).toInstance(persistentTasksClusterService);
                    b.bind(PersistentTasksExecutorRegistry.class).toInstance(registry);
                    b.bind(RepositoriesService.class).toInstance(repositoryService);
                    b.bind(SnapshotsService.class).toInstance(snapshotsService);
                    b.bind(SnapshotShardsService.class).toInstance(snapshotShardsService);
                    b.bind(TransportNodesSnapshotsStatus.class).toInstance(nodesSnapshotsStatus);
                    b.bind(RestoreService.class).toInstance(restoreService);
                    b.bind(RerouteService.class).toInstance(rerouteService);
                }
            );
            //将已经实例化的对象绑定到 ModulesBuilder中，最后调用 modules.createInjector 创建 injector（注入器）。
            injector = modules.createInjector();

            // TODO hack around circular dependencies problems in AllocationService
            clusterModule.getAllocationService().setGatewayAllocator(injector.getInstance(GatewayAllocator.class));

            List<LifecycleComponent> pluginLifecycleComponents = pluginComponents.stream()
                .filter(p -> p instanceof LifecycleComponent)
                .map(p -> (LifecycleComponent) p).collect(Collectors.toList());
            pluginLifecycleComponents.addAll(pluginsService.getGuiceServiceClasses().stream()
                .map(injector::getInstance).collect(Collectors.toList()));
            resourcesToClose.addAll(pluginLifecycleComponents);
            resourcesToClose.add(injector.getInstance(PeerRecoverySourceService.class));
            this.pluginLifecycleComponents = Collections.unmodifiableList(pluginLifecycleComponents);
            client.initialize(injector.getInstance(new Key<Map<ActionType, TransportAction>>() {}), transportService.getTaskManager(),
                    () -> clusterService.localNode().getId(), transportService.getRemoteClusterService());

            //重点：初始化 HTTP Handler
            //在这里主要是注册一堆 RestHandler，例如获取节点状态的 API 是在 RestNodesStatsAction 中定义的：
            //将action注册到RestController(分发器)
            //一个地址对应一个action，如http://localhost:9200/_cat/health 交给 RestHealthActiov处理。。。
            logger.debug("initializing HTTP handlers ...");
            actionModule.initRestHandlers(() -> clusterService.state().nodes());
            logger.info("initialized");

            success = true;
        } catch (IOException ex) {
            throw new ElasticsearchException("failed to bind service", ex);
        } finally {
            //启动失败，回收 “服务” 的资源
            if (!success) {
                IOUtils.closeWhileHandlingException(resourcesToClose);
            }
        }
    }

    protected TransportService newTransportService(Settings settings, Transport transport, ThreadPool threadPool,
                                                   TransportInterceptor interceptor,
                                                   Function<BoundTransportAddress, DiscoveryNode> localNodeFactory,
                                                   ClusterSettings clusterSettings, Set<String> taskHeaders) {
        return new TransportService(settings, transport, threadPool, interceptor, localNodeFactory, clusterSettings, taskHeaders);
    }

    protected void processRecoverySettings(ClusterSettings clusterSettings, RecoverySettings recoverySettings) {
        // Noop in production, overridden by tests
    }

    /**
     * The settings that are used by this node. Contains original settings as well as additional settings provided by plugins.
     */
    public Settings settings() {
        return this.environment.settings();
    }

    /**
     * A client that can be used to execute actions (operations) against the cluster.
     */
    public Client client() {
        return client;
    }

    /**
     * Returns the environment of the node
     */
    public Environment getEnvironment() {
        return environment;
    }

    /**
     * Returns the {@link NodeEnvironment} instance of this node
     */
    public NodeEnvironment getNodeEnvironment() {
        return nodeEnvironment;
    }


    /**
     * Start the node. If the node is already started, this method is no-op.
     */
    public Node start() throws NodeValidationException {
        if (!lifecycle.moveToStarted()) {
            return this;
        }
        //injector: 谷歌提供的轻量级 IOC 库
        logger.info("starting ...");
        // 启动生命周期组件
        pluginLifecycleComponents.forEach(LifecycleComponent::start);
        // see: https://www.lishuo.net/read/elasticsearch/date-2023.05.24.16.58.21
        injector.getInstance(MappingUpdatedAction.class).setClient(client);
        injector.getInstance(IndicesService.class).start(); //负责索引管理，如创建、删除等操作。
        injector.getInstance(IndicesClusterStateService.class).start(); //负责根据各种集群索引状态信息进行相应的操作，如创建或者恢复索引（这些实际的操作会交给具体的模块实现）等。
        injector.getInstance(SnapshotsService.class).start(); //负责创建快照，在执行快照创建和删除的时候，所有的执行步骤都在主节点上=进行。
        injector.getInstance(SnapshotShardsService.class).start(); //此服务在 data node 上运行，并且控制此节点上运行中的分片快照。其负责开启和停止分片级别的快照。
        injector.getInstance(RepositoriesService.class).start(); // 负责维护节点快照存储仓库和提供对存储仓库的访问。
        injector.getInstance(SearchService.class).start(); //提供搜索支持的服务。
        //启动定期监控系统资源使用情况
        nodeService.getMonitorService().start(); //负责提供操作系统、进程、JVM、文件系统级别的监控服务

        final ClusterService clusterService = injector.getInstance(ClusterService.class); // 集群管理服务，负责管理集群状态、处理集群任务、发布集群状态等。
        //该组件负责维护从该节点到集群状态中列出的所有节点的连接，并在节点从集群状态中删除后断开与节点的连接。
        // 并且会定期检查所有链接是否在打开状态，并且在需要的时候恢复它们。需要注意的是此组件不负责移除节点！
        final NodeConnectionsService nodeConnectionsService = injector.getInstance(NodeConnectionsService.class);
        nodeConnectionsService.start();
        clusterService.setNodeConnectionsService(nodeConnectionsService); //将 NodeConnectionsService 绑定到 ClusterService 中去
        //通用的资源监控服务
        injector.getInstance(ResourceWatcherService.class).start();
        injector.getInstance(GatewayService.class).start(); //网关服务，负责集群元数据的持久化和恢复。
        //节点发现模块是一个可插拔的模块，其负责发现集群中其他的节点，发布集群状态到所有节点，选举主节点和发布集群状态变更事件。
        Discovery discovery = injector.getInstance(Discovery.class);
        clusterService.getMasterService().setClusterStatePublisher(discovery::publish);

        // Start the transport service now so the publish address will be added to the local disco node in ClusterService
        //tcp port:9300，监听节点之间请求
        TransportService transportService = injector.getInstance(TransportService.class); // 负责节点间通讯。
        transportService.getTaskManager().setTaskResultsService(injector.getInstance(TaskResultsService.class));
        transportService.start();
        assert localNodeFactory.getNode() != null;
        assert transportService.getLocalNode().equals(localNodeFactory.getNode())
            : "transportService has a different local node than the factory provided";
        injector.getInstance(PeerRecoverySourceService.class).start();//负责处理对等分片的恢复请求，并且开启从这个源分片到目标分片的恢复流程。

        // Load (and maybe upgrade) the metadata stored on disk
        final GatewayMetaState gatewayMetaState = injector.getInstance(GatewayMetaState.class);
        gatewayMetaState.start(settings(), transportService, clusterService, injector.getInstance(MetaStateService.class),
            injector.getInstance(MetaDataIndexUpgradeService.class), injector.getInstance(MetaDataUpgrader.class),
            injector.getInstance(PersistedClusterStateService.class));
        if (Assertions.ENABLED) {
            try {
                if (DiscoveryModule.DISCOVERY_TYPE_SETTING.get(environment.settings()).equals(
                    DiscoveryModule.ZEN_DISCOVERY_TYPE) == false) {
                    assert injector.getInstance(MetaStateService.class).loadFullState().v1().isEmpty();
                }
                final NodeMetaData nodeMetaData = NodeMetaData.FORMAT.loadLatestState(logger, NamedXContentRegistry.EMPTY,
                    nodeEnvironment.nodeDataPaths());
                assert nodeMetaData != null;
                assert nodeMetaData.nodeVersion().equals(Version.CURRENT);
                assert nodeMetaData.nodeId().equals(localNodeFactory.getNode().getId());
            } catch (IOException e) {
                assert false : e;
            }
        }
        // we load the global state here (the persistent part of the cluster state stored on disk) to
        // pass it to the bootstrap checks to allow plugins to enforce certain preconditions based on the recovered state.
        final MetaData onDiskMetadata = gatewayMetaState.getPersistedState().getLastAcceptedState().metaData();
        assert onDiskMetadata != null : "metadata is null but shouldn't"; // this is never null
        validateNodeBeforeAcceptingRequests(new BootstrapContext(environment, onDiskMetadata), transportService.boundAddress(),
            pluginsService.filterPlugins(Plugin.class).stream()
                .flatMap(p -> p.getBootstrapChecks().stream()).collect(Collectors.toList()));

        clusterService.addStateApplier(transportService.getTaskManager());
        // start after transport service so the local disco is known
        discovery.start(); // start before cluster service so that it can set initial state on ClusterApplierService
        clusterService.start();
        assert clusterService.localNode().equals(localNodeFactory.getNode())
            : "clusterService has a different local node than the factory provided";
        transportService.acceptIncomingRequests(); //说明TransportService已准备好，可接收请求。
        // 选举流程：
        // https://blog.csdn.net/weixin_40318210/article/details/81515809
        // https://blog.csdn.net/kissfox220/article/details/119956861
        // 默认调用Coordinator的startInitialJoin()方法开始加入集群并准备进行参与选举。
        discovery.startInitialJoin();
        final TimeValue initialStateTimeout = DiscoverySettings.INITIAL_STATE_TIMEOUT_SETTING.get(settings());
        configureNodeAndClusterIdStateListener(clusterService);
        //开启线程去检查是否有开源加入的集群：
        if (initialStateTimeout.millis() > 0) {
            final ThreadPool thread = injector.getInstance(ThreadPool.class);
            ClusterState clusterState = clusterService.state();
            ClusterStateObserver observer =
                new ClusterStateObserver(clusterState, clusterService, null, logger, thread.getThreadContext());

            if (clusterState.nodes().getMasterNodeId() == null) {
                logger.debug("waiting to join the cluster. timeout [{}]", initialStateTimeout);
                final CountDownLatch latch = new CountDownLatch(1); //这里使用一个 CountDownLatch 来等待加入集群的结果。
                observer.waitForNextChange(new ClusterStateObserver.Listener() {
                    @Override
                    public void onNewClusterState(ClusterState state) { latch.countDown(); }

                    @Override
                    public void onClusterServiceClose() {
                        latch.countDown();
                    }

                    @Override
                    public void onTimeout(TimeValue timeout) {
                        logger.warn("timed out while waiting for initial discovery state - timeout: {}",
                            initialStateTimeout);
                        latch.countDown();
                    }
                }, state -> state.nodes().getMasterNodeId() != null, initialStateTimeout);

                try {
                    latch.await();
                } catch (InterruptedException e) {
                    throw new ElasticsearchTimeoutException("Interrupted while waiting for initial discovery state");
                }
            }
        }
        //提供 REST 接口服务。开启 HttpServerTransport，并且绑定监听地址，接收 REST 请求，（即用户请求）
        //实际执行以下两个类之一：Netty4HttpServerTransport 或 SecurityNetty4HttpServerTransport
        //真正处理用户请求的类：Netty4HttpRequestHandler
        //http port:9200，监听用户请求
        injector.getInstance(HttpServerTransport.class).start();

        if (WRITE_PORTS_FILE_SETTING.get(settings())) {
            TransportService transport = injector.getInstance(TransportService.class);
            writePortsFile("transport", transport.boundAddress());
            HttpServerTransport http = injector.getInstance(HttpServerTransport.class);
            writePortsFile("http", http.boundAddress());
        }

        logger.info("started"); //好了，到此整个节点的启动已经完成了，如果顺利，将会看到以下的输出：
        //[2022-05-16T11:56:32,972][INFO ][o.e.n.Node] [node-1] started
        //see: https://www.lishuo.net/read/elasticsearch/date-2023.05.24.16.58.21
        pluginsService.filterPlugins(ClusterPlugin.class).forEach(ClusterPlugin::onNodeStarted);

        return this;
    }

    protected void configureNodeAndClusterIdStateListener(ClusterService clusterService) {
        NodeAndClusterIdStateListener.getAndSetNodeIdAndClusterId(clusterService,
            injector.getInstance(ThreadPool.class).getThreadContext());
    }

    private Node stop() {
        if (!lifecycle.moveToStopped()) {
            return this;
        }
        logger.info("stopping ...");

        injector.getInstance(ResourceWatcherService.class).stop();
        injector.getInstance(HttpServerTransport.class).stop();

        injector.getInstance(SnapshotsService.class).stop();
        injector.getInstance(SnapshotShardsService.class).stop();
        injector.getInstance(RepositoriesService.class).stop();
        // stop any changes happening as a result of cluster state changes
        injector.getInstance(IndicesClusterStateService.class).stop();
        // close discovery early to not react to pings anymore.
        // This can confuse other nodes and delay things - mostly if we're the master and we're running tests.
        injector.getInstance(Discovery.class).stop();
        // we close indices first, so operations won't be allowed on it
        injector.getInstance(ClusterService.class).stop();
        injector.getInstance(NodeConnectionsService.class).stop();
        nodeService.getMonitorService().stop();
        injector.getInstance(GatewayService.class).stop();
        injector.getInstance(SearchService.class).stop();
        injector.getInstance(TransportService.class).stop();

        pluginLifecycleComponents.forEach(LifecycleComponent::stop);
        // we should stop this last since it waits for resources to get released
        // if we had scroll searchers etc or recovery going on we wait for to finish.
        injector.getInstance(IndicesService.class).stop();
        logger.info("stopped");

        return this;
    }

    // During concurrent close() calls we want to make sure that all of them return after the node has completed it's shutdown cycle.
    // If not, the hook that is added in Bootstrap#setup() will be useless:
    // close() might not be executed, in case another (for example api) call to close() has already set some lifecycles to stopped.
    // In this case the process will be terminated even if the first call to close() has not finished yet.
    @Override
    public synchronized void close() throws IOException {
        synchronized (lifecycle) {
            if (lifecycle.started()) {
                stop();
            }
            if (!lifecycle.moveToClosed()) {
                return;
            }
        }
        logger.info("closing ...");
        List<Closeable> toClose = new ArrayList<>();
        StopWatch stopWatch = new StopWatch("node_close");
        toClose.add(() -> stopWatch.start("node_service"));
        toClose.add(nodeService);
        toClose.add(() -> stopWatch.stop().start("http"));
        toClose.add(injector.getInstance(HttpServerTransport.class));
        toClose.add(() -> stopWatch.stop().start("snapshot_service"));
        toClose.add(injector.getInstance(SnapshotsService.class));
        toClose.add(injector.getInstance(SnapshotShardsService.class));
        toClose.add(() -> stopWatch.stop().start("client"));
        Releasables.close(injector.getInstance(Client.class));
        toClose.add(() -> stopWatch.stop().start("indices_cluster"));
        toClose.add(injector.getInstance(IndicesClusterStateService.class));
        toClose.add(() -> stopWatch.stop().start("indices"));
        toClose.add(injector.getInstance(IndicesService.class));
        // close filter/fielddata caches after indices
        toClose.add(injector.getInstance(IndicesStore.class));
        toClose.add(injector.getInstance(PeerRecoverySourceService.class));
        toClose.add(() -> stopWatch.stop().start("cluster"));
        toClose.add(injector.getInstance(ClusterService.class));
        toClose.add(() -> stopWatch.stop().start("node_connections_service"));
        toClose.add(injector.getInstance(NodeConnectionsService.class));
        toClose.add(() -> stopWatch.stop().start("discovery"));
        toClose.add(injector.getInstance(Discovery.class));
        toClose.add(() -> stopWatch.stop().start("monitor"));
        toClose.add(nodeService.getMonitorService());
        toClose.add(() -> stopWatch.stop().start("gateway"));
        toClose.add(injector.getInstance(GatewayService.class));
        toClose.add(() -> stopWatch.stop().start("search"));
        toClose.add(injector.getInstance(SearchService.class));
        toClose.add(() -> stopWatch.stop().start("transport"));
        toClose.add(injector.getInstance(TransportService.class));

        for (LifecycleComponent plugin : pluginLifecycleComponents) {
            toClose.add(() -> stopWatch.stop().start("plugin(" + plugin.getClass().getName() + ")"));
            toClose.add(plugin);
        }
        toClose.addAll(pluginsService.filterPlugins(Plugin.class));

        toClose.add(() -> stopWatch.stop().start("script"));
        toClose.add(injector.getInstance(ScriptService.class));

        toClose.add(() -> stopWatch.stop().start("thread_pool"));
        toClose.add(() -> injector.getInstance(ThreadPool.class).shutdown());
        // Don't call shutdownNow here, it might break ongoing operations on Lucene indices.
        // See https://issues.apache.org/jira/browse/LUCENE-7248. We call shutdownNow in
        // awaitClose if the node doesn't finish closing within the specified time.

        toClose.add(() -> stopWatch.stop().start("gateway_meta_state"));
        toClose.add(injector.getInstance(GatewayMetaState.class));

        toClose.add(() -> stopWatch.stop().start("node_environment"));
        toClose.add(injector.getInstance(NodeEnvironment.class));
        toClose.add(stopWatch::stop);

        if (logger.isTraceEnabled()) {
            toClose.add(() -> logger.trace("Close times for each service:\n{}", stopWatch.prettyPrint()));
        }
        IOUtils.close(toClose);
        logger.info("closed");
    }

    /**
     * Wait for this node to be effectively closed.
     */
    // synchronized to prevent running concurrently with close()
    public synchronized boolean awaitClose(long timeout, TimeUnit timeUnit) throws InterruptedException {
        if (lifecycle.closed() == false) {
            // We don't want to shutdown the threadpool or interrupt threads on a node that is not
            // closed yet.
            throw new IllegalStateException("Call close() first");
        }


        ThreadPool threadPool = injector.getInstance(ThreadPool.class);
        final boolean terminated = ThreadPool.terminate(threadPool, timeout, timeUnit);
        if (terminated) {
            // All threads terminated successfully. Because search, recovery and all other operations
            // that run on shards run in the threadpool, indices should be effectively closed by now.
            if (nodeService.awaitClose(0, TimeUnit.MILLISECONDS) == false) {
                throw new IllegalStateException("Some shards are still open after the threadpool terminated. " +
                        "Something is leaking index readers or store references.");
            }
        }
        return terminated;
    }

    /**
     * Returns {@code true} if the node is closed.
     */
    public boolean isClosed() {
        return lifecycle.closed();
    }

    public Injector injector() {
        return this.injector;
    }

    /**
     * Hook for validating the node after network
     * services are started but before the cluster service is started
     * and before the network service starts accepting incoming network
     * requests.
     *
     * @param context               the bootstrap context for this node
     * @param boundTransportAddress the network addresses the node is
     *                              bound and publishing to
     */
    @SuppressWarnings("unused")
    protected void validateNodeBeforeAcceptingRequests(
        final BootstrapContext context,
        final BoundTransportAddress boundTransportAddress, List<BootstrapCheck> bootstrapChecks) throws NodeValidationException {
    }

    /** Writes a file to the logs dir containing the ports for the given transport type */
    private void writePortsFile(String type, BoundTransportAddress boundAddress) {
        Path tmpPortsFile = environment.logsFile().resolve(type + ".ports.tmp");
        try (BufferedWriter writer = Files.newBufferedWriter(tmpPortsFile, Charset.forName("UTF-8"))) {
            for (TransportAddress address : boundAddress.boundAddresses()) {
                InetAddress inetAddress = InetAddress.getByName(address.getAddress());
                writer.write(NetworkAddress.format(new InetSocketAddress(inetAddress, address.getPort())) + "\n");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to write ports file", e);
        }
        Path portsFile = environment.logsFile().resolve(type + ".ports");
        try {
            Files.move(tmpPortsFile, portsFile, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new RuntimeException("Failed to rename ports file", e);
        }
    }

    /**
     * The {@link PluginsService} used to build this node's components.
     */
    protected PluginsService getPluginsService() {
        return pluginsService;
    }

    /**
     * Creates a new {@link CircuitBreakerService} based on the settings provided.
     * @see #BREAKER_TYPE_KEY
     */
    public static CircuitBreakerService createCircuitBreakerService(Settings settings, ClusterSettings clusterSettings) {
        String type = BREAKER_TYPE_KEY.get(settings);
        if (type.equals("hierarchy")) {
            return new HierarchyCircuitBreakerService(settings, clusterSettings);
        } else if (type.equals("none")) {
            return new NoneCircuitBreakerService();
        } else {
            throw new IllegalArgumentException("Unknown circuit breaker type [" + type + "]");
        }
    }

    /**
     * Creates a new {@link BigArrays} instance used for this node.
     * This method can be overwritten by subclasses to change their {@link BigArrays} implementation for instance for testing
     */
    BigArrays createBigArrays(PageCacheRecycler pageCacheRecycler, CircuitBreakerService circuitBreakerService) {
        return new BigArrays(pageCacheRecycler, circuitBreakerService, CircuitBreaker.REQUEST);
    }

    /**
     * Creates a new {@link BigArrays} instance used for this node.
     * This method can be overwritten by subclasses to change their {@link BigArrays} implementation for instance for testing
     */
    PageCacheRecycler createPageCacheRecycler(Settings settings) {
        return new PageCacheRecycler(settings);
    }

    /**
     * Creates a new the SearchService. This method can be overwritten by tests to inject mock implementations.
     */
    protected SearchService newSearchService(ClusterService clusterService, IndicesService indicesService,
                                             ThreadPool threadPool, ScriptService scriptService, BigArrays bigArrays,
                                             FetchPhase fetchPhase, ResponseCollectorService responseCollectorService) {
        return new SearchService(clusterService, indicesService, threadPool,
            scriptService, bigArrays, fetchPhase, responseCollectorService);
    }

    /**
     * Get Custom Name Resolvers list based on a Discovery Plugins list
     * @param discoveryPlugins Discovery plugins list
     */
    private List<NetworkService.CustomNameResolver> getCustomNameResolvers(List<DiscoveryPlugin> discoveryPlugins) {
        List<NetworkService.CustomNameResolver> customNameResolvers = new ArrayList<>();
        for (DiscoveryPlugin discoveryPlugin : discoveryPlugins) {
            NetworkService.CustomNameResolver customNameResolver = discoveryPlugin.getCustomNameResolver(settings());
            if (customNameResolver != null) {
                customNameResolvers.add(customNameResolver);
            }
        }
        return customNameResolvers;
    }

    /** Constructs a ClusterInfoService which may be mocked for tests. */
    protected ClusterInfoService newClusterInfoService(Settings settings, ClusterService clusterService,
                                                       ThreadPool threadPool, NodeClient client) {
        return new InternalClusterInfoService(settings, clusterService, threadPool, client);
    }

    /** Constructs a {@link org.elasticsearch.http.HttpServerTransport} which may be mocked for tests. */
    protected HttpServerTransport newHttpTransport(NetworkModule networkModule) {
        return networkModule.getHttpServerTransportSupplier().get();
    }

    private static class LocalNodeFactory implements Function<BoundTransportAddress, DiscoveryNode> {
        private final SetOnce<DiscoveryNode> localNode = new SetOnce<>();
        private final String persistentNodeId;
        private final Settings settings;

        private LocalNodeFactory(Settings settings, String persistentNodeId) {
            this.persistentNodeId = persistentNodeId;
            this.settings = settings;
        }

        @Override
        public DiscoveryNode apply(BoundTransportAddress boundTransportAddress) {
            localNode.set(DiscoveryNode.createLocal(settings, boundTransportAddress.publishAddress(), persistentNodeId));
            return localNode.get();
        }

        DiscoveryNode getNode() {
            assert localNode.get() != null;
            return localNode.get();
        }
    }
}
