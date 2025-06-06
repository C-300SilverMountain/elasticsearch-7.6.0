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

package org.elasticsearch.cluster.routing;

import org.apache.logging.log4j.LogManager;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.allocation.decider.AwarenessAllocationDecider;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.logging.DeprecationLogger;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.node.ResponseCollectorService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.elasticsearch.common.Booleans.parseBoolean;

public class OperationRouting {

    public static final Setting<Boolean> USE_ADAPTIVE_REPLICA_SELECTION_SETTING =
            Setting.boolSetting("cluster.routing.use_adaptive_replica_selection", true,
                    Setting.Property.Dynamic, Setting.Property.NodeScope);

    private static final DeprecationLogger deprecationLogger = new DeprecationLogger(LogManager.getLogger(OperationRouting.class));
    private static final String IGNORE_AWARENESS_ATTRIBUTES_PROPERTY = "es.search.ignore_awareness_attributes";
    static final String IGNORE_AWARENESS_ATTRIBUTES_DEPRECATION_MESSAGE =
        "searches will not be routed based on awareness attributes starting in version 8.0.0; " +
            "to opt into this behaviour now please set the system property [" + IGNORE_AWARENESS_ATTRIBUTES_PROPERTY + "] to [true]";

    private List<String> awarenessAttributes;
    private boolean useAdaptiveReplicaSelection;

    public OperationRouting(Settings settings, ClusterSettings clusterSettings) {
        // whether to ignore awareness attributes when routing requests
        boolean ignoreAwarenessAttr = parseBoolean(System.getProperty(IGNORE_AWARENESS_ATTRIBUTES_PROPERTY), false);
        if (ignoreAwarenessAttr == false) {
            awarenessAttributes = AwarenessAllocationDecider.CLUSTER_ROUTING_ALLOCATION_AWARENESS_ATTRIBUTE_SETTING.get(settings);
            if (awarenessAttributes.isEmpty() == false) {
                deprecationLogger.deprecated(IGNORE_AWARENESS_ATTRIBUTES_DEPRECATION_MESSAGE);
            }
            clusterSettings.addSettingsUpdateConsumer(AwarenessAllocationDecider.CLUSTER_ROUTING_ALLOCATION_AWARENESS_ATTRIBUTE_SETTING,
                this::setAwarenessAttributes);
        } else {
            awarenessAttributes = Collections.emptyList();
        }

        this.useAdaptiveReplicaSelection = USE_ADAPTIVE_REPLICA_SELECTION_SETTING.get(settings);
        clusterSettings.addSettingsUpdateConsumer(USE_ADAPTIVE_REPLICA_SELECTION_SETTING, this::setUseAdaptiveReplicaSelection);
    }

    void setUseAdaptiveReplicaSelection(boolean useAdaptiveReplicaSelection) {
        this.useAdaptiveReplicaSelection = useAdaptiveReplicaSelection;
    }

    List<String> getAwarenessAttributes() {
        return awarenessAttributes;
    }

    private void setAwarenessAttributes(List<String> awarenessAttributes) {
        boolean ignoreAwarenessAttr = parseBoolean(System.getProperty(IGNORE_AWARENESS_ATTRIBUTES_PROPERTY), false);
        if (ignoreAwarenessAttr == false) {
            if (this.awarenessAttributes.isEmpty() && awarenessAttributes.isEmpty() == false) {
                deprecationLogger.deprecated(IGNORE_AWARENESS_ATTRIBUTES_DEPRECATION_MESSAGE);
            }
            this.awarenessAttributes = awarenessAttributes;
        }
    }

    public ShardIterator indexShards(ClusterState clusterState, String index, String id, @Nullable String routing) {
        return shards(clusterState, index, id, routing).shardsIt();
    }
    //getShards 方法中主要调用了 shards 来获取 shardId，然后再根据计算出来的 shardId 从集群元数据的 routing table 中获取存储此文档的 Shard 路由表信息。
    // 最后再调用 preferenceActiveShardIterator 方法根据 preference（优先级） 参数是否存在分别获取对应的 Shard 列表。
    public ShardIterator getShards(ClusterState clusterState, String index, String id, @Nullable String routing,
                                   @Nullable String preference) {
        //调用 preferenceActiveShardIterator 方法根据 preference（优先级） 参数是否存在分别获取对应的 Shard 列表
        return preferenceActiveShardIterator(shards(clusterState, index, id, routing), clusterState.nodes().getLocalNodeId(),
            clusterState.nodes(), preference, null, null);
    }

    public ShardIterator getShards(ClusterState clusterState, String index, int shardId, @Nullable String preference) {
        final IndexShardRoutingTable indexShard = clusterState.getRoutingTable().shardRoutingTable(index, shardId);
        return preferenceActiveShardIterator(indexShard, clusterState.nodes().getLocalNodeId(), clusterState.nodes(),
            preference, null, null);
    }

    public GroupShardsIterator<ShardIterator> searchShards(ClusterState clusterState,
                                                           String[] concreteIndices,
                                                           @Nullable Map<String, Set<String>> routing,
                                                           @Nullable String preference) {
        return searchShards(clusterState, concreteIndices, routing, preference, null, null);
    }


    public GroupShardsIterator<ShardIterator> searchShards(ClusterState clusterState,
                                                           String[] concreteIndices,
                                                           @Nullable Map<String, Set<String>> routing,
                                                           @Nullable String preference,
                                                           @Nullable ResponseCollectorService collectorService,
                                                           @Nullable Map<String, Long> nodeCounts) {
        final Set<IndexShardRoutingTable> shards = computeTargetedShards(clusterState, concreteIndices, routing);
        final Set<ShardIterator> set = new HashSet<>(shards.size());
        for (IndexShardRoutingTable shard : shards) {
            ShardIterator iterator = preferenceActiveShardIterator(shard,
                    clusterState.nodes().getLocalNodeId(), clusterState.nodes(), preference, collectorService, nodeCounts);
            if (iterator != null) {
                set.add(iterator);
            }
        }
        return new GroupShardsIterator<>(new ArrayList<>(set));
    }

    private static final Map<String, Set<String>> EMPTY_ROUTING = Collections.emptyMap();

    private Set<IndexShardRoutingTable> computeTargetedShards(ClusterState clusterState, String[] concreteIndices,
                                                              @Nullable Map<String, Set<String>> routing) {
        routing = routing == null ? EMPTY_ROUTING : routing; // just use an empty map
        final Set<IndexShardRoutingTable> set = new HashSet<>();
        // we use set here and not list since we might get duplicates
        for (String index : concreteIndices) {
            final IndexRoutingTable indexRouting = indexRoutingTable(clusterState, index);
            final IndexMetaData indexMetaData = indexMetaData(clusterState, index);
            final Set<String> effectiveRouting = routing.get(index);
            if (effectiveRouting != null) {
                for (String r : effectiveRouting) {
                    final int routingPartitionSize = indexMetaData.getRoutingPartitionSize();
                    for (int partitionOffset = 0; partitionOffset < routingPartitionSize; partitionOffset++) {
                        set.add(RoutingTable.shardRoutingTable(indexRouting, calculateScaledShardId(indexMetaData, r, partitionOffset)));
                    }
                }
            } else {
                for (IndexShardRoutingTable indexShard : indexRouting) {
                    set.add(indexShard);
                }
            }
        }
        return set;
    }

    private ShardIterator preferenceActiveShardIterator(IndexShardRoutingTable indexShard, String localNodeId,
                                                        DiscoveryNodes nodes, @Nullable String preference,
                                                        @Nullable ResponseCollectorService collectorService,
                                                        @Nullable Map<String, Long> nodeCounts) {
        if (preference == null || preference.isEmpty()) {
            return shardRoutings(indexShard, nodes, collectorService, nodeCounts);
        }
        if (preference.charAt(0) == '_') {
            Preference preferenceType = Preference.parse(preference);
            if (preferenceType == Preference.SHARDS) {
                // starts with _shards, so execute on specific ones
                int index = preference.indexOf('|');

                String shards;
                if (index == -1) {
                    shards = preference.substring(Preference.SHARDS.type().length() + 1);
                } else {
                    shards = preference.substring(Preference.SHARDS.type().length() + 1, index);
                }
                String[] ids = Strings.splitStringByCommaToArray(shards);
                boolean found = false;
                for (String id : ids) {
                    if (Integer.parseInt(id) == indexShard.shardId().id()) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return null;
                }
                // no more preference
                if (index == -1 || index == preference.length() - 1) {
                    return shardRoutings(indexShard, nodes, collectorService, nodeCounts);
                } else {
                    // update the preference and continue
                    preference = preference.substring(index + 1);
                }
            }
            preferenceType = Preference.parse(preference);
            switch (preferenceType) {
                case PREFER_NODES:
                    final Set<String> nodesIds =
                            Arrays.stream(
                                    preference.substring(Preference.PREFER_NODES.type().length() + 1).split(",")
                            ).collect(Collectors.toSet());
                    return indexShard.preferNodeActiveInitializingShardsIt(nodesIds);
                case LOCAL:
                    return indexShard.preferNodeActiveInitializingShardsIt(Collections.singleton(localNodeId));
                case ONLY_LOCAL:
                    return indexShard.onlyNodeActiveInitializingShardsIt(localNodeId);
                case ONLY_NODES:
                    String nodeAttributes = preference.substring(Preference.ONLY_NODES.type().length() + 1);
                    return indexShard.onlyNodeSelectorActiveInitializingShardsIt(nodeAttributes.split(","), nodes);
                default:
                    throw new IllegalArgumentException("unknown preference [" + preferenceType + "]");
            }
        }
        // if not, then use it as the index
        int routingHash = Murmur3HashFunction.hash(preference);
        if (nodes.getMinNodeVersion().onOrAfter(Version.V_6_0_0_alpha1)) {
            // The AllocationService lists shards in a fixed order based on nodes
            // so earlier versions of this class would have a tendency to
            // select the same node across different shardIds.
            // Better overall balancing can be achieved if each shardId opts
            // for a different element in the list by also incorporating the
            // shard ID into the hash of the user-supplied preference key.
            routingHash = 31 * routingHash + indexShard.shardId.hashCode();
        }
        if (awarenessAttributes.isEmpty()) {
            return indexShard.activeInitializingShardsIt(routingHash);
        } else {
            return indexShard.preferAttributesActiveInitializingShardsIt(awarenessAttributes, nodes, routingHash);
        }
    }

    private ShardIterator shardRoutings(IndexShardRoutingTable indexShard, DiscoveryNodes nodes,
            @Nullable ResponseCollectorService collectorService, @Nullable Map<String, Long> nodeCounts) {
        if (awarenessAttributes.isEmpty()) {
            if (useAdaptiveReplicaSelection) {
                return indexShard.activeInitializingShardsRankedIt(collectorService, nodeCounts);
            } else {
                return indexShard.activeInitializingShardsRandomIt();
            }
        } else {
            return indexShard.preferAttributesActiveInitializingShardsIt(awarenessAttributes, nodes);
        }
    }

    protected IndexRoutingTable indexRoutingTable(ClusterState clusterState, String index) {
        IndexRoutingTable indexRouting = clusterState.routingTable().index(index);
        if (indexRouting == null) {
            throw new IndexNotFoundException(index);
        }
        return indexRouting;
    }

    protected IndexMetaData indexMetaData(ClusterState clusterState, String index) {
        IndexMetaData indexMetaData = clusterState.metaData().index(index);
        if (indexMetaData == null) {
            throw new IndexNotFoundException(index);
        }
        return indexMetaData;
    }

    protected IndexShardRoutingTable shards(ClusterState clusterState, String index, String id, String routing) {
        //计算得到 shardId
        int shardId = generateShardId(indexMetaData(clusterState, index), id, routing);
        //根据计算出来的 shardId 从集群元数据的 routing table 中获取存储此文档的 Shard 路由表信息
        return clusterState.getRoutingTable().shardRoutingTable(index, shardId);
    }

    public ShardId shardId(ClusterState clusterState, String index, String id, @Nullable String routing) {
        IndexMetaData indexMetaData = indexMetaData(clusterState, index);
        return new ShardId(indexMetaData.getIndex(), generateShardId(indexMetaData, id, routing));
    }

    public static int generateShardId(IndexMetaData indexMetaData, @Nullable String id, @Nullable String routing) {
        final String effectiveRouting;
        //partitionOffset 变量需要注意，当我们写入文档的时候，如果使用 ES 的随机 id 和 hash 算法的话，可以保证文档被均匀分配到各个主分片中，避免出现数据倾斜。
        // 但如果我们使用自定义的文档 id 或者 routing key 的时候，是没法保证的
        // https://www.elastic.co/guide/en/elasticsearch/reference/7.13/index-modules.html#routing-partition-size
        final int partitionOffset;
        //当 routing 请求参数没有设置的时候，effectiveRouting 的值为文档 id，否则为 routing。
        if (routing == null) {
            assert(indexMetaData.isRoutingPartitionedIndex() == false) : "A routing value is required for gets from a partitioned index";
            effectiveRouting = id;
        } else {
            effectiveRouting = routing;
        }
        //这里还有一个 partitionOffset 变量需要注意，当我们写入文档的时候，如果使用 ES 的随机 id 和 hash 算法的话，可以保证文档被均匀分配到各个主分片中，避免出现数据倾斜。
        // 但如果我们使用自定义的文档 id 或者 routing key 的时候，是没法保证的。
        // 这个时候可以使用 index.routing_partition_size 配置来降低出现数据倾斜的风险，其值越大，数据分布就越均匀
        // see: https://www.elastic.co/guide/en/elasticsearch/reference/7.13/index-modules.html#routing-partition-size
        //index.routing_partition_size 取值应大于 1 且小于 index.number_of_shards 的值（请参考文档）
        //partitionOffset = hash(id) % routing_partition_size

        //为什么增加了 partitionOffset 可以降低出现数据倾斜的风险呢？
        //partitionOffset 改变了是否会改变计算出的 shardId，或者说 routing_partition_size 改变后会改变 shardId 吗？
        if (indexMetaData.isRoutingPartitionedIndex()) {
            partitionOffset = Math.floorMod(Murmur3HashFunction.hash(id), indexMetaData.getRoutingPartitionSize());
        } else {
            // we would have still got 0 above but this check just saves us an unnecessary hash calculation
            partitionOffset = 0;
        }
        //实际上 ES 是通过文档 Id 或者 routing key 计算出到底要到哪个Shard 上获取数据的。
        return calculateScaledShardId(indexMetaData, effectiveRouting, partitionOffset);
    }

    /**
     * 实际上 ES 是通过文档 Id 或者 routing key 计算出到底要到哪个Shard 上获取数据的。
     */
    private static int calculateScaledShardId(IndexMetaData indexMetaData, String effectiveRouting, int partitionOffset) {
        //根据一下代码，最终整理成公式如下：
        //A:
        //# partitionOffset = 0 的时候
        //# effectiveRouting 为文档id 或者 routing key
        //1、shardId = (hash(effectiveRouting) % num_primary_shards) / RoutingFactor

        //B:
        //# partitionOffset = hash(id) % routing_partition_size
        //2、shardId = ((hash(effectiveRouting) + partitionOffset) % num_primary_shards) / RoutingFactor

        final int hash = Murmur3HashFunction.hash(effectiveRouting) + partitionOffset;

        //为什么增加了 partitionOffset 可以降低出现数据倾斜的风险呢？
        //partitionOffset 改变了是否会改变计算出的 shardId，或者说 routing_partition_size 改变后会改变 shardId 吗？

        // we don't use IMD#getNumberOfShards since the index might have been shrunk such that we need to use the size
        // of original index to hash documents
        return Math.floorMod(hash, indexMetaData.getRoutingNumShards()) / indexMetaData.getRoutingFactor();
    }

}
