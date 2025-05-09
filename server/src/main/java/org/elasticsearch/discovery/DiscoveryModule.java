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

package org.elasticsearch.discovery;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.coordination.Coordinator;
import org.elasticsearch.cluster.coordination.ElectionStrategy;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.RerouteService;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.cluster.service.ClusterApplier;
import org.elasticsearch.cluster.service.MasterService;
import org.elasticsearch.common.Randomness;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.discovery.zen.ZenDiscovery;
import org.elasticsearch.gateway.GatewayMetaState;
import org.elasticsearch.plugins.DiscoveryPlugin;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.elasticsearch.node.Node.NODE_NAME_SETTING;

/**
 * A module for loading classes for node discovery.
 */
public class DiscoveryModule {
    private static final Logger logger = LogManager.getLogger(DiscoveryModule.class);

    public static final String ZEN_DISCOVERY_TYPE = "legacy-zen"; // 旧版，7.6之前使用：ZenDiscovery
    public static final String ZEN2_DISCOVERY_TYPE = "zen"; //默认使用: Coordinator

    public static final String SINGLE_NODE_DISCOVERY_TYPE = "single-node";

    public static final Setting<String> DISCOVERY_TYPE_SETTING =
        new Setting<>("discovery.type", ZEN2_DISCOVERY_TYPE, Function.identity(), Property.NodeScope);
    //discovery.zen.hosts_provider作用：
    //功能：指定节点发现（Node Discovery）的提供者（Provider），即告诉 Elasticsearch 如何找到集群中的其他节点。
    //场景：在分布式环境中，节点需要自动发现彼此以形成集群。此参数决定节点如何获取初始的主节点列表（master-eligible nodes）。
    public static final Setting<List<String>> LEGACY_DISCOVERY_HOSTS_PROVIDER_SETTING =
        Setting.listSetting("discovery.zen.hosts_provider", Collections.emptyList(), Function.identity(),
            Property.NodeScope, Property.Deprecated);
    //discovery.seed_hosts 是 Elasticsearch 7.x 及更高版本 中用于集群节点发现的核心配置参数，替代了早期版本中的 discovery.zen.hosts_provider。以下是详细解析：
    //作用
    //功能：指定一组种子节点（Seed Hosts），新加入集群的节点通过这些种子节点发现集群中的其他节点（尤其是主节点）。
    //核心目的：确保节点能快速、可靠地找到集群入口，完成分布式组网

    //关键特性
    //1、种子节点的作用：
    //种子节点是集群中已知的稳定节点列表，新节点启动时会尝试连接这些种子节点，进而发现整个集群。
    //种子节点本身可以是主节点（master-eligible）或数据节点，但通常建议配置为专用的主节点。
    //2、动态发现：
    //节点通过种子节点获取集群中其他节点的地址，无需手动维护所有节点的列表。
    //支持动态更新，例如节点故障时自动剔除不可达节点。
    //3、与 cluster.initial_master_nodes 的配合：
    //在首次启动集群时，需同时配置 discovery.seed_hosts 和 cluster.initial_master_nodes，前者用于节点发现，后者用于选举初始主节点。
    public static final Setting<List<String>> DISCOVERY_SEED_PROVIDERS_SETTING =
        Setting.listSetting("discovery.seed_providers", Collections.emptyList(), Function.identity(),
            Property.NodeScope);

    public static final String DEFAULT_ELECTION_STRATEGY = "default";

    public static final Setting<String> ELECTION_STRATEGY_SETTING =
        new Setting<>("cluster.election.strategy", DEFAULT_ELECTION_STRATEGY, Function.identity(), Property.NodeScope);

    //重点关注属性
    private final Discovery discovery;

    public DiscoveryModule(Settings settings, ThreadPool threadPool, TransportService transportService,
                           NamedWriteableRegistry namedWriteableRegistry, NetworkService networkService, MasterService masterService,
                           ClusterApplier clusterApplier, ClusterSettings clusterSettings, List<DiscoveryPlugin> plugins,
                           AllocationService allocationService, Path configFile, GatewayMetaState gatewayMetaState,
                           RerouteService rerouteService) {
        final Collection<BiConsumer<DiscoveryNode, ClusterState>> joinValidators = new ArrayList<>();
        final Map<String, Supplier<SeedHostsProvider>> hostProviders = new HashMap<>();
        hostProviders.put("settings", () -> new SettingsBasedSeedHostsProvider(settings, transportService));
        hostProviders.put("file", () -> new FileBasedSeedHostsProvider(configFile));
        final Map<String, ElectionStrategy> electionStrategies = new HashMap<>();
        electionStrategies.put(DEFAULT_ELECTION_STRATEGY, ElectionStrategy.DEFAULT_INSTANCE);
        for (DiscoveryPlugin plugin : plugins) {
            plugin.getSeedHostProviders(transportService, networkService).forEach((key, value) -> {
                if (hostProviders.put(key, value) != null) {
                    throw new IllegalArgumentException("Cannot register seed provider [" + key + "] twice");
                }
            });
            BiConsumer<DiscoveryNode, ClusterState> joinValidator = plugin.getJoinValidator();
            if (joinValidator != null) {
                joinValidators.add(joinValidator);
            }
            plugin.getElectionStrategies().forEach((key, value) -> {
                if (electionStrategies.put(key, value) != null) {
                    throw new IllegalArgumentException("Cannot register election strategy [" + key + "] twice");
                }
            });
        }
        // 种子主机：集群初始启动时，并不知道具备选举资格的节点的信息，所以需要手动配置
        // 读取配置文件中，discovery.seed_providers的值
        List<String> seedProviderNames = getSeedProviderNames(settings);
        // for bwc purposes, add settings provider even if not explicitly specified
        if (seedProviderNames.contains("settings") == false) {
            List<String> extendedSeedProviderNames = new ArrayList<>();
            extendedSeedProviderNames.add("settings");
            extendedSeedProviderNames.addAll(seedProviderNames);
            seedProviderNames = extendedSeedProviderNames;
        }

        final Set<String> missingProviderNames = new HashSet<>(seedProviderNames);
        missingProviderNames.removeAll(hostProviders.keySet());
        if (missingProviderNames.isEmpty() == false) {
            throw new IllegalArgumentException("Unknown seed providers " + missingProviderNames);
        }

        List<SeedHostsProvider> filteredSeedProviders = seedProviderNames.stream()
            .map(hostProviders::get).map(Supplier::get).collect(Collectors.toList());

        String discoveryType = DISCOVERY_TYPE_SETTING.get(settings);

        final SeedHostsProvider seedHostsProvider = hostsResolver -> {
            final List<TransportAddress> addresses = new ArrayList<>();
            for (SeedHostsProvider provider : filteredSeedProviders) {
                addresses.addAll(provider.getSeedAddresses(hostsResolver));
            }
            return Collections.unmodifiableList(addresses);
        };

        final ElectionStrategy electionStrategy = electionStrategies.get(ELECTION_STRATEGY_SETTING.get(settings));
        if (electionStrategy == null) {
            throw new IllegalArgumentException("Unknown election strategy " + ELECTION_STRATEGY_SETTING.get(settings));
        }

        if (ZEN2_DISCOVERY_TYPE.equals(discoveryType) || SINGLE_NODE_DISCOVERY_TYPE.equals(discoveryType)) {
            //ES 7.x 重构了一个新的集群协调层Coordinator，他实际上是 Raft 的实现，但并非严格按照 Raft 论文实现，而是做了一些调整。
            //默认使用：Coordinator
            // 请关注seedHostsProvider的来源，此参数在选举流程中用到
            discovery = new Coordinator(NODE_NAME_SETTING.get(settings),
                settings, clusterSettings,
                transportService, namedWriteableRegistry, allocationService, masterService, gatewayMetaState::getPersistedState,
                seedHostsProvider, clusterApplier, joinValidators, new Random(Randomness.get().nextLong()), rerouteService,
                electionStrategy);
        } else if (ZEN_DISCOVERY_TYPE.equals(discoveryType)) {
            //采用Bully算法
            //它假定所有节点都有一个唯一的ID，使用该ID对节点进行排序。任何时候的当前Leader都是参与集群的最高ID节点。该算法的优点是易于实现。但是，当拥有最大ID的节点处于不稳定状态的场景下会有问题。例如，Master负载过重而假死，集群拥有第二大ID的节点被选为新主，这时原来的Master恢复，再次被选为新主，然后又假死
            //ES 通过推迟选举，直到当前的 Master 失效来解决上述问题，只要当前主节点不挂掉，就不重新选主。但是容易产生脑裂（双主），为此，再通过“法定得票人数过半”解决脑裂问题
            //链接：https://www.jianshu.com/p/d3ad414ed4f7
            discovery = new ZenDiscovery(settings, threadPool, transportService, namedWriteableRegistry, masterService, clusterApplier,
                clusterSettings, seedHostsProvider, allocationService, joinValidators, rerouteService);
        } else {
            throw new IllegalArgumentException("Unknown discovery type [" + discoveryType + "]");
        }

        logger.info("using discovery type [{}] and seed hosts providers {}", discoveryType, seedProviderNames);
    }

    private List<String> getSeedProviderNames(Settings settings) {
        if (LEGACY_DISCOVERY_HOSTS_PROVIDER_SETTING.exists(settings)) {
            if (DISCOVERY_SEED_PROVIDERS_SETTING.exists(settings)) {
                throw new IllegalArgumentException("it is forbidden to set both [" + DISCOVERY_SEED_PROVIDERS_SETTING.getKey() + "] and ["
                    + LEGACY_DISCOVERY_HOSTS_PROVIDER_SETTING.getKey() + "]");
            }
            return LEGACY_DISCOVERY_HOSTS_PROVIDER_SETTING.get(settings);
        }
        return DISCOVERY_SEED_PROVIDERS_SETTING.get(settings);
    }

    public Discovery getDiscovery() {
        return discovery;
    }
}
