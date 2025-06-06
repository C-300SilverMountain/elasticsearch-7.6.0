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
package org.elasticsearch.cluster.coordination;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.cluster.coordination.CoordinationMetaData.VotingConfiguration;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.discovery.DiscoveryModule;
import org.elasticsearch.node.Node;
import org.elasticsearch.threadpool.ThreadPool.Names;
import org.elasticsearch.transport.TransportService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableSet;
import static org.elasticsearch.discovery.DiscoveryModule.DISCOVERY_SEED_PROVIDERS_SETTING;
import static org.elasticsearch.discovery.DiscoveryModule.LEGACY_DISCOVERY_HOSTS_PROVIDER_SETTING;
import static org.elasticsearch.discovery.SettingsBasedSeedHostsProvider.DISCOVERY_SEED_HOSTS_SETTING;
import static org.elasticsearch.discovery.SettingsBasedSeedHostsProvider.LEGACY_DISCOVERY_ZEN_PING_UNICAST_HOSTS_SETTING;

public class ClusterBootstrapService {

    public static final Setting<List<String>> INITIAL_MASTER_NODES_SETTING =
        Setting.listSetting("cluster.initial_master_nodes", emptyList(), Function.identity(), Property.NodeScope);

    public static final Setting<TimeValue> UNCONFIGURED_BOOTSTRAP_TIMEOUT_SETTING =
        Setting.timeSetting("discovery.unconfigured_bootstrap_timeout",
            TimeValue.timeValueSeconds(3), TimeValue.timeValueMillis(1), Property.NodeScope);

    static final String BOOTSTRAP_PLACEHOLDER_PREFIX = "{bootstrap-placeholder}-";

    private static final Logger logger = LogManager.getLogger(ClusterBootstrapService.class);
    private final Set<String> bootstrapRequirements;
    @Nullable // null if discoveryIsConfigured()
    private final TimeValue unconfiguredBootstrapTimeout;
    private final TransportService transportService;
    private final Supplier<Iterable<DiscoveryNode>> discoveredNodesSupplier;
    private final BooleanSupplier isBootstrappedSupplier;
    private final Consumer<VotingConfiguration> votingConfigurationConsumer;
    private final AtomicBoolean bootstrappingPermitted = new AtomicBoolean(true);

    public ClusterBootstrapService(Settings settings, TransportService transportService,
                                   Supplier<Iterable<DiscoveryNode>> discoveredNodesSupplier, BooleanSupplier isBootstrappedSupplier,
                                   Consumer<VotingConfiguration> votingConfigurationConsumer) {
        if (DiscoveryModule.SINGLE_NODE_DISCOVERY_TYPE.equals(DiscoveryModule.DISCOVERY_TYPE_SETTING.get(settings))) {
            if (INITIAL_MASTER_NODES_SETTING.exists(settings)) {
                throw new IllegalArgumentException("setting [" + INITIAL_MASTER_NODES_SETTING.getKey() +
                    "] is not allowed when [" + DiscoveryModule.DISCOVERY_TYPE_SETTING.getKey() + "] is set to [" +
                    DiscoveryModule.SINGLE_NODE_DISCOVERY_TYPE + "]");
            }
            if (DiscoveryNode.isMasterNode(settings) == false) {
                throw new IllegalArgumentException("node with [" + DiscoveryModule.DISCOVERY_TYPE_SETTING.getKey() + "] set to [" +
                    DiscoveryModule.SINGLE_NODE_DISCOVERY_TYPE +  "] must be master-eligible");
            }
            bootstrapRequirements = Collections.singleton(Node.NODE_NAME_SETTING.get(settings));
            unconfiguredBootstrapTimeout = null;
        } else {
            final List<String> initialMasterNodes = INITIAL_MASTER_NODES_SETTING.get(settings);
            bootstrapRequirements = unmodifiableSet(new LinkedHashSet<>(initialMasterNodes));
            if (bootstrapRequirements.size() != initialMasterNodes.size()) {
                throw new IllegalArgumentException(
                    "setting [" + INITIAL_MASTER_NODES_SETTING.getKey() + "] contains duplicates: " + initialMasterNodes);
            }
            unconfiguredBootstrapTimeout = discoveryIsConfigured(settings) ? null : UNCONFIGURED_BOOTSTRAP_TIMEOUT_SETTING.get(settings);
        }

        this.transportService = transportService;
        this.discoveredNodesSupplier = discoveredNodesSupplier;
        this.isBootstrappedSupplier = isBootstrappedSupplier;
        this.votingConfigurationConsumer = votingConfigurationConsumer;
    }

    public static boolean discoveryIsConfigured(Settings settings) {
        return Stream.of(DISCOVERY_SEED_PROVIDERS_SETTING, LEGACY_DISCOVERY_HOSTS_PROVIDER_SETTING,
            DISCOVERY_SEED_HOSTS_SETTING, LEGACY_DISCOVERY_ZEN_PING_UNICAST_HOSTS_SETTING,
            INITIAL_MASTER_NODES_SETTING).anyMatch(s -> s.exists(settings));
    }

    void onFoundPeersUpdated() {
        final Set<DiscoveryNode> nodes = getDiscoveredNodes();
        if (bootstrappingPermitted.get() && transportService.getLocalNode().isMasterNode() && bootstrapRequirements.isEmpty() == false
            && isBootstrappedSupplier.getAsBoolean() == false && nodes.stream().noneMatch(Coordinator::isZen1Node)) {

            final Tuple<Set<DiscoveryNode>,List<String>> requirementMatchingResult;
            try {
                requirementMatchingResult = checkRequirements(nodes);
            } catch (IllegalStateException e) {
                logger.warn("bootstrapping cancelled", e);
                bootstrappingPermitted.set(false);
                return;
            }

            final Set<DiscoveryNode> nodesMatchingRequirements = requirementMatchingResult.v1();
            final List<String> unsatisfiedRequirements = requirementMatchingResult.v2();
            logger.trace("nodesMatchingRequirements={}, unsatisfiedRequirements={}, bootstrapRequirements={}",
                nodesMatchingRequirements, unsatisfiedRequirements, bootstrapRequirements);

            if (nodesMatchingRequirements.contains(transportService.getLocalNode()) == false) {
                logger.info("skipping cluster bootstrapping as local node does not match bootstrap requirements: {}",
                    bootstrapRequirements);
                bootstrappingPermitted.set(false);
                return;
            }

            if (nodesMatchingRequirements.size() * 2 > bootstrapRequirements.size()) {
                startBootstrap(nodesMatchingRequirements, unsatisfiedRequirements);
            }
        }
    }

    void scheduleUnconfiguredBootstrap() {
        //unconfiguredBootstrapTimeout作用
        //定义无主集群的引导超时时间：
        //当节点启动时，若未发现任何已有集群的主节点（Master Node），它会尝试通过 自动引导（Unconfigured Bootstrap） 创建一个新的集群。
        //discovery.unconfigured_bootstrap_timeout 决定了节点在此过程中等待其他节点加入的最长时间。
        //1、超时前：若收到其他节点的加入请求，则共同完成集群初始化。
        //2、超时后：当前节点将强制成为主节点，并记录警告日志（可能引发脑裂风险）。

        if (unconfiguredBootstrapTimeout == null) {
            return;
        }
        // Master角色权限校验
        if (transportService.getLocalNode().isMasterNode() == false) {
            return;
        }

        logger.info("no discovery configuration found, will perform best-effort cluster bootstrapping after [{}] " +
            "unless existing master is discovered", unconfiguredBootstrapTimeout);
        // 执行启动任务
        transportService.getThreadPool().scheduleUnlessShuttingDown(unconfiguredBootstrapTimeout, Names.GENERIC, new Runnable() {
            @Override
            public void run() {
                // 获取集群中发现的节点
                final Set<DiscoveryNode> discoveredNodes = getDiscoveredNodes();
                // 判断集群当中是否有节点的版本是 7.0 之前
                final List<DiscoveryNode> zen1Nodes = discoveredNodes.stream().filter(Coordinator::isZen1Node).collect(Collectors.toList());
                if (zen1Nodes.isEmpty()) {
                    logger.debug("performing best-effort cluster bootstrapping with {}", discoveredNodes);
                    // 启动选举
                    startBootstrap(discoveredNodes, emptyList());
                } else {
                    logger.info("avoiding best-effort cluster bootstrapping due to discovery of pre-7.0 nodes {}", zen1Nodes);
                }
            }

            @Override
            public String toString() {
                return "unconfigured-discovery delayed bootstrap";
            }
        });
    }

    private Set<DiscoveryNode> getDiscoveredNodes() {
        return Stream.concat(Stream.of(transportService.getLocalNode()),
            StreamSupport.stream(discoveredNodesSupplier.get().spliterator(), false)).collect(Collectors.toSet());
    }

    private void startBootstrap(Set<DiscoveryNode> discoveryNodes, List<String> unsatisfiedRequirements) {
        // 判断发现的节点是否有Master角色权限
        assert discoveryNodes.stream().allMatch(DiscoveryNode::isMasterNode) : discoveryNodes;
        assert discoveryNodes.stream().noneMatch(Coordinator::isZen1Node) : discoveryNodes;
        assert unsatisfiedRequirements.size() < discoveryNodes.size() : discoveryNodes + " smaller than " + unsatisfiedRequirements;
        if (bootstrappingPermitted.compareAndSet(true, false)) {
            doBootstrap(new VotingConfiguration(Stream.concat(discoveryNodes.stream().map(DiscoveryNode::getId),
                unsatisfiedRequirements.stream().map(s -> BOOTSTRAP_PLACEHOLDER_PREFIX + s))
                .collect(Collectors.toSet())));
        }
    }

    public static boolean isBootstrapPlaceholder(String nodeId) {
        return nodeId.startsWith(BOOTSTRAP_PLACEHOLDER_PREFIX);
    }

    private void doBootstrap(VotingConfiguration votingConfiguration) {
        assert transportService.getLocalNode().isMasterNode();

        try {
            // 触发投票
            // DiscoveryModule初始化Coordinator时，连带初始化ClusterBootstrapService，在ClusterBootstrapService初始化时，指定votingConfigurationConsumer
            votingConfigurationConsumer.accept(votingConfiguration);
        } catch (Exception e) {
            logger.warn(new ParameterizedMessage("exception when bootstrapping with {}, rescheduling", votingConfiguration), e);
            // 如果出现异常，进行重试
            transportService.getThreadPool().scheduleUnlessShuttingDown(TimeValue.timeValueSeconds(10), Names.GENERIC,
                new Runnable() {
                    @Override
                    public void run() {
                        doBootstrap(votingConfiguration);
                    }

                    @Override
                    public String toString() {
                        return "retry of failed bootstrapping with " + votingConfiguration;
                    }
                }
            );
        }
    }

    private static boolean matchesRequirement(DiscoveryNode discoveryNode, String requirement) {
        return discoveryNode.getName().equals(requirement)
            || discoveryNode.getAddress().toString().equals(requirement)
            || discoveryNode.getAddress().getAddress().equals(requirement);
    }

    private Tuple<Set<DiscoveryNode>,List<String>> checkRequirements(Set<DiscoveryNode> nodes) {
        final Set<DiscoveryNode> selectedNodes = new HashSet<>();
        final List<String> unmatchedRequirements = new ArrayList<>();
        for (final String bootstrapRequirement : bootstrapRequirements) {
            final Set<DiscoveryNode> matchingNodes
                = nodes.stream().filter(n -> matchesRequirement(n, bootstrapRequirement)).collect(Collectors.toSet());

            if (matchingNodes.size() == 0) {
                unmatchedRequirements.add(bootstrapRequirement);
            }

            if (matchingNodes.size() > 1) {
                throw new IllegalStateException("requirement [" + bootstrapRequirement + "] matches multiple nodes: " + matchingNodes);
            }

            for (final DiscoveryNode matchingNode : matchingNodes) {
                if (selectedNodes.add(matchingNode) == false) {
                    throw new IllegalStateException("node [" + matchingNode + "] matches multiple requirements: " +
                        bootstrapRequirements.stream().filter(r -> matchesRequirement(matchingNode, r)).collect(Collectors.toList()));
                }
            }
        }

        return Tuple.tuple(selectedNodes, unmatchedRequirements);
    }
}
