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

package org.elasticsearch.cluster;

import com.carrotsearch.hppc.cursors.IntObjectCursor;
import com.carrotsearch.hppc.cursors.ObjectCursor;
import com.carrotsearch.hppc.cursors.ObjectObjectCursor;

import org.elasticsearch.Version;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.cluster.block.ClusterBlock;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.coordination.CoordinationMetaData;
import org.elasticsearch.cluster.coordination.CoordinationMetaData.VotingConfiguration;
import org.elasticsearch.cluster.coordination.CoordinationMetaData.VotingConfigExclusion;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.routing.IndexShardRoutingTable;
import org.elasticsearch.cluster.routing.RoutingNode;
import org.elasticsearch.cluster.routing.RoutingNodes;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.NamedWriteableAwareStreamInput;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.VersionedNamedWriteable;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ToXContentFragment;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.discovery.Discovery;

import java.io.IOException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.elasticsearch.cluster.coordination.Coordinator.ZEN1_BWC_TERM;

/**
 * Represents the current state of the cluster.
 * <p>
 * The cluster state object is immutable with the exception of the {@link RoutingNodes} structure, which is
 * built on demand from the {@link RoutingTable}.
 * The cluster state can be updated only on the master node. All updates are performed by on a
 * single thread and controlled by the {@link ClusterService}. After every update the
 * {@link Discovery#publish} method publishes a new version of the cluster state to all other nodes in the
 * cluster. The actual publishing mechanism is delegated to the {@link Discovery#publish} method and depends on
 * the type of discovery.
 * <p>
 * The cluster state implements the {@link Diffable} interface in order to support publishing of cluster state
 * differences instead of the entire state on each change. The publishing mechanism should only send differences
 * to a node if this node was present in the previous version of the cluster state. If a node was
 * not present in the previous version of the cluster state, this node is unlikely to have the previous cluster
 * state version and should be sent a complete version. In order to make sure that the differences are applied to the
 * correct version of the cluster state, each cluster state version update generates {@link #stateUUID} that uniquely
 * identifies this version of the state. This uuid is verified by the {@link ClusterStateDiff#apply} method to
 * make sure that the correct diffs are applied. If uuids don’t match, the {@link ClusterStateDiff#apply} method
 * throws the {@link IncompatibleClusterStateVersionException}, which causes the publishing mechanism to send
 * a full version of the cluster state to the node on which this exception was thrown.
 *
 * 通过访问es_http/_cluster/state，可以看到es集群ClusterState详情：
 * https://www.elastic.co/guide/en/elasticsearch/reference/7.6/cluster-stats.html
 *
 * 集群状态在ES中封装为ClusterState类。可以通过cluster/state API来获取集群状态。
 * 参考: https://cloud.tencent.com/developer/article/1860217
 * curl -X GET "localhost: 9200/_cluster/state"   默认情况下，协调节点在收到这个请求后会把请求路由到主节点执行，确保获取最新的集群状态。
 *                                                可以通过在请求中添加local=true参数，让接收请求的节点返回本地的集群状态。例如，在排查问题时如果怀疑节点的集群状态是否最新，则可以用这种方式来验证。
 * 响应信息如下：
 * {
 *     "cluster_name" : "elasticsearch",
 *     "compressed_size_in_bytes" : 1383, //压缩后的字节数
 *     "version" : 5, //当前集群状态的版本号
 *     "state_uuid" : "MMXpwaedThCVDIkzn9vpgA",
 *     "master_node" : " fc6s0S0hRi2yJvMo54qt_g",
 *     "blocks" : { }, //阻塞信息
 *     "nodes" : {
 *         " fc6s0S0hRi2yJvMo54qt_g" : {
 *             //节点名称、监听地址和端口等信息
 *         }
 *     }
 *     "metadata" : {//元数据
 *         "cluster_uuid" : "olrqNUxhTC20VVG8KyXJ_w",
 *         "templates" : {
 *             //全部模板的具体内容
 *         }，
 *         "indices" : {//索引列表
 *             "website" : {
 *                 "state" : "open",
 *                 "settings" : {
 *                     //setting的具体内容
 *                 },
 *                 "mappings": {
 *                     //mapping的具体内容
 *                 }
 *                 "aliases" : [ ],//索引别名
 *                 "primary_ terms" : {
 *                     //某个分片被选为主分片的次数，用于区分新旧主分片(具体请参考数据模型一章)
 *                     "0" : 4,
 *                     "1" : 5
 *                 }
 *                 "in_sync_allocations" : {
 *                     //同步分片列表，代表某个分片中拥有最新数据的分片列表
 *                     "1":[
 *                         "jalbPWjJST2bDPCU008ScQ" //这个值是allocation_id
 *                     ],
 *                     "0":[
 *                         "1EjTXE1CSZ-C1DYlEFRXtw"
 *                     ]
 *                 }
 *             }
 *         },
 *         "repositories" : {
 *             //为存储快照而创建的仓库列表
 *         }，
 *         "index-graveyard" : {
 *             //索引墓碑。记录已删除的索引，并保存一段时间。索引删除是主节点通过下发
 *             //集群状态来执行的
 *             //各节点处理集群状态是异步的过程。例如，索引分片分布在5个节点上，删除
 *             //索引期间，某个节点是“down”掉的，没有执行删除逻辑
 *             //当这个节点恢复的时候，其存储的已删除的索引会被当作孤立资源加入集群,
 *             //索引死而复活。墓碑的作用就是防止发生这种情况
 *             "tombstones" : [
 *                 //已删除的索引列表
 *             ]
 *         }
 *     },
 *     "routing_table" : { //内容路由表。存储某个分片位于哪个节点的信息。通过分片
 *         //找到节点
 *         "indices" : { //全部索引列表
 *             "website" : {//索引名称
 *                 "shards" : {//该索引的全部分片列表 .
 *                     "1" : [//分片 1
 *                         {
 *                             "state" : "STARTED",    //分片可能的状态: UNASSIGNED、INITIALIZING、
 *                                                     //STARTED、RELOCATING
 *                              "primary" : true， //是否是主分片
 *                              "node" : "fc6s0S0hRi2yJvMo54qt_g", //所在分片
 *                              "relocating_node" : null, //正在“relocation”到哪个节点
 *                              "shard" : 1, // 分片1
 *                              "index" : "website", // 索引名
 *                              "allocation_ id" : {
 *                                 "id" : "jalbPWj JST2bDPCUO08ScQ" // 分片唯一的allocation_id配合in_sync_allocations使用
 *                              }
 *                          }
 *                      ]
 *                  }
 *              }
 *          }
 *      },
 *      "routing nodes" : {//存储某个节点存储了哪些分片的信息。通过节点找到分片
 *         "unassigned" : [//未分配的分片列表
 *                 {//某个分片的信息
 *                     "state" : "UNASSIGNED",
 *                     "primary" : true,
 *                     "node" : null,
 *                     "relocating_ node" : null,
 *                     "shard" : 0，
 *                     "index" : "website",
 *                     " recovery_ source" : {
 *                     "type" : "EXISTING_ STORE"
 *                 },
 *                 "unassigned_ info" : {//未 分配的具体信息
 *                     "reason" : "CLUSTER RECOVERED",
 *                     "at" : "2018-05-27T08:17:56.381Z",
 *                     "delayed" : false,
 *                     "allocation status" : "no_ valid_ shard copy"
 *                 }
 *             }
 *         ],
 *         "nodes" : {//节点列表
 *         "fc6s0S0hRi2yJvMo54qt_g" : [//某个节点 上的分片列表
 *             {
 *                 "state" : "STARTED"， //分片信息， 同上
 *                 "primary" : true,
 *                 "node" : " fc6s0S0hRi2yJvMo54qt_g",
 *                 "relocating_ node" : null,
 *                 "shard" : 1,
 *                 "index" : "website",
 *                 "allocation_id" : {
 *                     "id" : "jalbPWjJST2bDPCU008ScQ"
 *                 },
 *                 "snapshot_deletions" : {//请 求删除快照的信息
 *                     "snapshot_deletions" :[ ]
 *                 },
 *                 "snapshots" : {//请求创 建快照的信息
 *                     "snapshots" : [ ]
 *                 },
 *                 "restore" : {//请求恢 复快照的信息
 *                     "snapshots" : [ ]
 *                 }
 *             }
 *         }
 *     }
 * }
 * 由于集群状态需要频繁下发，而且内容较多，从ES 2.0版本开始，主节点发布集群信息时支持在相邻的两个版本号之间只发送增量内容。
 */
//特别说明：集群状态信息包含集群元信息+路由信息（靠节点上报后，动态生成）
    // https://blog.csdn.net/GeekerJava/article/details/139702581
    // ClusterState集群状态的管理在 gatewayService负责，如持久化、从磁盘加载集群状态
public class ClusterState implements ToXContentFragment, Diffable<ClusterState> {

    public static final ClusterState EMPTY_STATE = builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY)).build();

    /**
     * An interface that implementors use when a class requires a client to maybe have a feature.
     */
    public interface FeatureAware {

        /**
         * An optional feature that is required for the client to have.
         *
         * @return an empty optional if no feature is required otherwise a string representing the required feature
         */
        default Optional<String> getRequiredFeature() {
            return Optional.empty();
        }

        /**
         * Tests whether or not the custom should be serialized. The criteria are:
         * <ul>
         * <li>the output stream must be at least the minimum supported version of the custom</li>
         * <li>the output stream must have the feature required by the custom (if any) or not be a transport client</li>
         * </ul>
         * <p>
         * That is, we only serialize customs to clients than can understand the custom based on the version of the client and the features
         * that the client has. For transport clients we can be lenient in requiring a feature in which case we do not send the custom but
         * for connected nodes we always require that the node has the required feature.
         *
         * @param out    the output stream
         * @param custom the custom to serialize
         * @param <T>    the type of the custom
         * @return true if the custom should be serialized and false otherwise
         */
        static <T extends VersionedNamedWriteable & FeatureAware> boolean shouldSerialize(final StreamOutput out, final T custom) {
            if (out.getVersion().before(custom.getMinimalSupportedVersion())) {
                return false;
            }
            if (custom.getRequiredFeature().isPresent()) {
                final String requiredFeature = custom.getRequiredFeature().get();
                // if it is a transport client we are lenient yet for a connected node it must have the required feature
                return out.hasFeature(requiredFeature) || out.hasFeature(TransportClient.TRANSPORT_CLIENT_FEATURE) == false;
            }
            return true;
        }

    }

    public interface Custom extends NamedDiffable<Custom>, ToXContentFragment, FeatureAware {

        /**
         * Returns <code>true</code> iff this {@link Custom} is private to the cluster and should never be send to a client.
         * The default is <code>false</code>;
         */
        default boolean isPrivate() {
            return false;
        }

    }

    private static final NamedDiffableValueSerializer<Custom> CUSTOM_VALUE_SERIALIZER = new NamedDiffableValueSerializer<>(Custom.class);

    public static final String UNKNOWN_UUID = "_na_";

    public static final long UNKNOWN_VERSION = -1;

    //版本号
    private final long version;

    private final String stateUUID;
    //路由表
    //每个索引操作首先会使用routing参数解析到副本组，通常基于文档ID。一旦确定副本组，就会内部转发该操作到分片组的主分片中。主分
    //片负责验证操作和转发它到其他副分片。
    private final RoutingTable routingTable;
    //节点信息
    private final DiscoveryNodes nodes;
    //集群元数据
    private final MetaData metaData;

    private final ClusterBlocks blocks;

    private final ImmutableOpenMap<String, Custom> customs;
    //集群名称
    private final ClusterName clusterName;

    private final boolean wasReadFromDiff;

    private final int minimumMasterNodesOnPublishingMaster;

    // built on demand
    private volatile RoutingNodes routingNodes;

    public ClusterState(long version, String stateUUID, ClusterState state) {
        this(state.clusterName, version, stateUUID, state.metaData(), state.routingTable(), state.nodes(), state.blocks(),
                state.customs(), -1, false);
    }

    public ClusterState(ClusterName clusterName, long version, String stateUUID, MetaData metaData, RoutingTable routingTable,
                        DiscoveryNodes nodes, ClusterBlocks blocks, ImmutableOpenMap<String, Custom> customs,
                        int minimumMasterNodesOnPublishingMaster, boolean wasReadFromDiff) {
        this.version = version;
        this.stateUUID = stateUUID;
        this.clusterName = clusterName;
        this.metaData = metaData;
        this.routingTable = routingTable;
        this.nodes = nodes;
        this.blocks = blocks;
        this.customs = customs;
        this.minimumMasterNodesOnPublishingMaster = minimumMasterNodesOnPublishingMaster;
        this.wasReadFromDiff = wasReadFromDiff;
    }

    public long term() {
        return coordinationMetaData().term();
    }

    public long version() {
        return this.version;
    }

    public long getVersion() {
        return version();
    }

    public long getVersionOrMetaDataVersion() {
        // When following a Zen1 master, the cluster state version is not guaranteed to increase, so instead it is preferable to use the
        // metadata version to determine the freshest node. However when following a Zen2 master the cluster state version should be used.
        return term() == ZEN1_BWC_TERM ? metaData().version() : version();
    }

    /**
     * This stateUUID is automatically generated for for each version of cluster state. It is used to make sure that
     * we are applying diffs to the right previous state.
     */
    public String stateUUID() {
        return this.stateUUID;
    }

    public DiscoveryNodes nodes() {
        return this.nodes;
    }

    public DiscoveryNodes getNodes() {
        return nodes();
    }

    public MetaData metaData() {
        return this.metaData;
    }

    public MetaData getMetaData() {
        return metaData();
    }

    public CoordinationMetaData coordinationMetaData() {
        return metaData.coordinationMetaData();
    }

    public RoutingTable routingTable() {
        return routingTable;
    }

    public RoutingTable getRoutingTable() {
        return routingTable();
    }

    public ClusterBlocks blocks() {
        return this.blocks;
    }

    public ClusterBlocks getBlocks() {
        return blocks;
    }

    public ImmutableOpenMap<String, Custom> customs() {
        return this.customs;
    }

    public ImmutableOpenMap<String, Custom> getCustoms() {
        return this.customs;
    }

    public <T extends Custom> T custom(String type) {
        return (T) customs.get(type);
    }

    public ClusterName getClusterName() {
        return this.clusterName;
    }

    public VotingConfiguration getLastAcceptedConfiguration() {
        return coordinationMetaData().getLastAcceptedConfiguration();
    }

    public VotingConfiguration getLastCommittedConfiguration() {
        return coordinationMetaData().getLastCommittedConfiguration();
    }

    public Set<VotingConfigExclusion> getVotingConfigExclusions() {
        return coordinationMetaData().getVotingConfigExclusions();
    }

    /**
     * The node-level `discovery.zen.minimum_master_nodes` setting on the master node that published this cluster state, for use in rolling
     * upgrades from 6.x to 7.x. Once all the 6.x master-eligible nodes have left the cluster, the 7.x nodes use this value to determine how
     * many master-eligible nodes must be discovered before the cluster can be bootstrapped. Note that this method returns the node-level
     * value of this setting, and ignores any cluster-level override that was set via the API. Callers are expected to combine this value
     * with any value set in the cluster-level settings. This should be removed once we no longer need support for {@link Version#V_6_7_0}.
     */
    public int getMinimumMasterNodesOnPublishingMaster() {
        return minimumMasterNodesOnPublishingMaster;
    }

    // Used for testing and logging to determine how this cluster state was send over the wire
    public boolean wasReadFromDiff() {
        return wasReadFromDiff;
    }

    /**
     * Returns a built (on demand) routing nodes view of the routing table.
     */
    public RoutingNodes getRoutingNodes() {
        if (routingNodes != null) {
            return routingNodes;
        }
        routingNodes = new RoutingNodes(this);
        return routingNodes;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        final String TAB = "   ";
        sb.append("cluster uuid: ").append(metaData.clusterUUID())
            .append(" [committed: ").append(metaData.clusterUUIDCommitted()).append("]").append("\n");
        sb.append("version: ").append(version).append("\n");
        sb.append("state uuid: ").append(stateUUID).append("\n");
        sb.append("from_diff: ").append(wasReadFromDiff).append("\n");
        sb.append("meta data version: ").append(metaData.version()).append("\n");
        sb.append(TAB).append("coordination_metadata:\n");
        sb.append(TAB).append(TAB).append("term: ").append(coordinationMetaData().term()).append("\n");
        sb.append(TAB).append(TAB)
                .append("last_committed_config: ").append(coordinationMetaData().getLastCommittedConfiguration()).append("\n");
        sb.append(TAB).append(TAB)
                .append("last_accepted_config: ").append(coordinationMetaData().getLastAcceptedConfiguration()).append("\n");
        sb.append(TAB).append(TAB)
                .append("voting tombstones: ").append(coordinationMetaData().getVotingConfigExclusions()).append("\n");
        for (IndexMetaData indexMetaData : metaData) {
            sb.append(TAB).append(indexMetaData.getIndex());
            sb.append(": v[").append(indexMetaData.getVersion())
                    .append("], mv[").append(indexMetaData.getMappingVersion())
                    .append("], sv[").append(indexMetaData.getSettingsVersion())
                    .append("], av[").append(indexMetaData.getAliasesVersion())
                    .append("]\n");
            for (int shard = 0; shard < indexMetaData.getNumberOfShards(); shard++) {
                sb.append(TAB).append(TAB).append(shard).append(": ");
                sb.append("p_term [").append(indexMetaData.primaryTerm(shard)).append("], ");
                sb.append("isa_ids ").append(indexMetaData.inSyncAllocationIds(shard)).append("\n");
            }
        }
        if (metaData.customs().isEmpty() == false) {
            sb.append("metadata customs:\n");
            for (final ObjectObjectCursor<String, MetaData.Custom> cursor : metaData.customs()) {
                final String type = cursor.key;
                final MetaData.Custom custom = cursor.value;
                sb.append(TAB).append(type).append(": ").append(custom);
            }
            sb.append("\n");
        }
        sb.append(blocks());
        sb.append(nodes());
        sb.append(routingTable());
        sb.append(getRoutingNodes());
        if (customs.isEmpty() == false) {
            sb.append("customs:\n");
            for (ObjectObjectCursor<String, Custom> cursor : customs) {
                final String type = cursor.key;
                final Custom custom = cursor.value;
                sb.append(TAB).append(type).append(": ").append(custom);
            }
        }
        return sb.toString();
    }

    /**
     * a cluster state supersedes another state if they are from the same master and the version of this state is higher than that of the
     * other state.
     * <p>
     * In essence that means that all the changes from the other cluster state are also reflected by the current one
     */
    public boolean supersedes(ClusterState other) {
        return this.nodes().getMasterNodeId() != null && this.nodes().getMasterNodeId().equals(other.nodes().getMasterNodeId())
            && this.version() > other.version();

    }

    public enum Metric {
        VERSION("version"),
        MASTER_NODE("master_node"),
        BLOCKS("blocks"),
        NODES("nodes"),
        METADATA("metadata"),
        ROUTING_TABLE("routing_table"),
        ROUTING_NODES("routing_nodes"),
        CUSTOMS("customs");

        private static Map<String, Metric> valueToEnum;

        static {
            valueToEnum = new HashMap<>();
            for (Metric metric : Metric.values()) {
                valueToEnum.put(metric.value, metric);
            }
        }

        private final String value;

        Metric(String value) {
            this.value = value;
        }

        public static EnumSet<Metric> parseString(String param, boolean ignoreUnknown) {
            String[] metrics = Strings.splitStringByCommaToArray(param);
            EnumSet<Metric> result = EnumSet.noneOf(Metric.class);
            for (String metric : metrics) {
                if ("_all".equals(metric)) {
                    result = EnumSet.allOf(Metric.class);
                    break;
                }
                Metric m = valueToEnum.get(metric);
                if (m == null) {
                    if (!ignoreUnknown) {
                        throw new IllegalArgumentException("Unknown metric [" + metric + "]");
                    }
                } else {
                    result.add(m);
                }
            }
            return result;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        EnumSet<Metric> metrics = Metric.parseString(params.param("metric", "_all"), true);

        // always provide the cluster_uuid as part of the top-level response (also part of the metadata response)
        builder.field("cluster_uuid", metaData().clusterUUID());

        if (metrics.contains(Metric.VERSION)) {
            builder.field("version", version);
            builder.field("state_uuid", stateUUID);
        }

        if (metrics.contains(Metric.MASTER_NODE)) {
            builder.field("master_node", nodes().getMasterNodeId());
        }

        if (metrics.contains(Metric.BLOCKS)) {
            builder.startObject("blocks");

            if (!blocks().global().isEmpty()) {
                builder.startObject("global");
                for (ClusterBlock block : blocks().global()) {
                    block.toXContent(builder, params);
                }
                builder.endObject();
            }

            if (!blocks().indices().isEmpty()) {
                builder.startObject("indices");
                for (ObjectObjectCursor<String, Set<ClusterBlock>> entry : blocks().indices()) {
                    builder.startObject(entry.key);
                    for (ClusterBlock block : entry.value) {
                        block.toXContent(builder, params);
                    }
                    builder.endObject();
                }
                builder.endObject();
            }

            builder.endObject();
        }

        // nodes
        if (metrics.contains(Metric.NODES)) {
            builder.startObject("nodes");
            for (DiscoveryNode node : nodes) {
                node.toXContent(builder, params);
            }
            builder.endObject();
        }

        // meta data
        if (metrics.contains(Metric.METADATA)) {
            builder.startObject("metadata");
            builder.field("cluster_uuid", metaData().clusterUUID());

            builder.startObject("cluster_coordination");
            coordinationMetaData().toXContent(builder, params);
            builder.endObject();

            builder.startObject("templates");
            for (ObjectCursor<IndexTemplateMetaData> cursor : metaData().templates().values()) {
                IndexTemplateMetaData templateMetaData = cursor.value;
                builder.startObject(templateMetaData.name());

                builder.field("index_patterns", templateMetaData.patterns());
                builder.field("order", templateMetaData.order());

                builder.startObject("settings");
                Settings settings = templateMetaData.settings();
                settings.toXContent(builder, params);
                builder.endObject();

                builder.startObject("mappings");
                for (ObjectObjectCursor<String, CompressedXContent> cursor1 : templateMetaData.mappings()) {
                    Map<String, Object> mapping = XContentHelper.convertToMap(new BytesArray(cursor1.value.uncompressed()), false).v2();
                    if (mapping.size() == 1 && mapping.containsKey(cursor1.key)) {
                        // the type name is the root value, reduce it
                        mapping = (Map<String, Object>) mapping.get(cursor1.key);
                    }
                    builder.field(cursor1.key);
                    builder.map(mapping);
                }
                builder.endObject();


                builder.endObject();
            }
            builder.endObject();

            builder.startObject("indices");
            for (IndexMetaData indexMetaData : metaData()) {
                builder.startObject(indexMetaData.getIndex().getName());

                builder.field("state", indexMetaData.getState().toString().toLowerCase(Locale.ENGLISH));

                builder.startObject("settings");
                Settings settings = indexMetaData.getSettings();
                settings.toXContent(builder, params);
                builder.endObject();

                builder.startObject("mappings");
                for (ObjectObjectCursor<String, MappingMetaData> cursor : indexMetaData.getMappings()) {
                    Map<String, Object> mapping = XContentHelper
                            .convertToMap(new BytesArray(cursor.value.source().uncompressed()), false).v2();
                    if (mapping.size() == 1 && mapping.containsKey(cursor.key)) {
                        // the type name is the root value, reduce it
                        mapping = (Map<String, Object>) mapping.get(cursor.key);
                    }
                    builder.field(cursor.key);
                    builder.map(mapping);
                }
                builder.endObject();

                builder.startArray("aliases");
                for (ObjectCursor<String> cursor : indexMetaData.getAliases().keys()) {
                    builder.value(cursor.value);
                }
                builder.endArray();

                builder.startObject(IndexMetaData.KEY_PRIMARY_TERMS);
                for (int shard = 0; shard < indexMetaData.getNumberOfShards(); shard++) {
                    builder.field(Integer.toString(shard), indexMetaData.primaryTerm(shard));
                }
                builder.endObject();

                builder.startObject(IndexMetaData.KEY_IN_SYNC_ALLOCATIONS);
                for (IntObjectCursor<Set<String>> cursor : indexMetaData.getInSyncAllocationIds()) {
                    builder.startArray(String.valueOf(cursor.key));
                    for (String allocationId : cursor.value) {
                        builder.value(allocationId);
                    }
                    builder.endArray();
                }
                builder.endObject();

                // index metadata
                builder.endObject();
            }
            builder.endObject();

            for (ObjectObjectCursor<String, MetaData.Custom> cursor : metaData.customs()) {
                builder.startObject(cursor.key);
                cursor.value.toXContent(builder, params);
                builder.endObject();
            }

            builder.endObject();
        }

        // routing table
        if (metrics.contains(Metric.ROUTING_TABLE)) {
            builder.startObject("routing_table");
            builder.startObject("indices");
            for (IndexRoutingTable indexRoutingTable : routingTable()) {
                builder.startObject(indexRoutingTable.getIndex().getName());
                builder.startObject("shards");
                for (IndexShardRoutingTable indexShardRoutingTable : indexRoutingTable) {
                    builder.startArray(Integer.toString(indexShardRoutingTable.shardId().id()));
                    for (ShardRouting shardRouting : indexShardRoutingTable) {
                        shardRouting.toXContent(builder, params);
                    }
                    builder.endArray();
                }
                builder.endObject();
                builder.endObject();
            }
            builder.endObject();
            builder.endObject();
        }

        // routing nodes
        if (metrics.contains(Metric.ROUTING_NODES)) {
            builder.startObject("routing_nodes");
            builder.startArray("unassigned");
            for (ShardRouting shardRouting : getRoutingNodes().unassigned()) {
                shardRouting.toXContent(builder, params);
            }
            builder.endArray();

            builder.startObject("nodes");
            for (RoutingNode routingNode : getRoutingNodes()) {
                builder.startArray(routingNode.nodeId() == null ? "null" : routingNode.nodeId());
                for (ShardRouting shardRouting : routingNode) {
                    shardRouting.toXContent(builder, params);
                }
                builder.endArray();
            }
            builder.endObject();

            builder.endObject();
        }
        if (metrics.contains(Metric.CUSTOMS)) {
            for (ObjectObjectCursor<String, Custom> cursor : customs) {
                builder.startObject(cursor.key);
                cursor.value.toXContent(builder, params);
                builder.endObject();
            }
        }

        return builder;
    }

    public static Builder builder(ClusterName clusterName) {
        return new Builder(clusterName);
    }

    public static Builder builder(ClusterState state) {
        return new Builder(state);
    }

    public static class Builder {

        private final ClusterName clusterName;
        private long version = 0;
        private String uuid = UNKNOWN_UUID;
        private MetaData metaData = MetaData.EMPTY_META_DATA;
        private RoutingTable routingTable = RoutingTable.EMPTY_ROUTING_TABLE;
        private DiscoveryNodes nodes = DiscoveryNodes.EMPTY_NODES;
        private ClusterBlocks blocks = ClusterBlocks.EMPTY_CLUSTER_BLOCK;
        private final ImmutableOpenMap.Builder<String, Custom> customs;
        private boolean fromDiff;
        private int minimumMasterNodesOnPublishingMaster = -1;

        public Builder(ClusterState state) {
            this.clusterName = state.clusterName;
            this.version = state.version();
            this.uuid = state.stateUUID();
            this.nodes = state.nodes();
            this.routingTable = state.routingTable();
            this.metaData = state.metaData();
            this.blocks = state.blocks();
            this.customs = ImmutableOpenMap.builder(state.customs());
            this.minimumMasterNodesOnPublishingMaster = state.minimumMasterNodesOnPublishingMaster;
            this.fromDiff = false;
        }

        public Builder(ClusterName clusterName) {
            customs = ImmutableOpenMap.builder();
            this.clusterName = clusterName;
        }

        public Builder nodes(DiscoveryNodes.Builder nodesBuilder) {
            return nodes(nodesBuilder.build());
        }

        public Builder nodes(DiscoveryNodes nodes) {
            this.nodes = nodes;
            return this;
        }

        public DiscoveryNodes nodes() {
            return nodes;
        }

        public Builder routingTable(RoutingTable routingTable) {
            this.routingTable = routingTable;
            return this;
        }

        public Builder metaData(MetaData.Builder metaDataBuilder) {
            return metaData(metaDataBuilder.build());
        }

        public Builder metaData(MetaData metaData) {
            this.metaData = metaData;
            return this;
        }

        public Builder blocks(ClusterBlocks.Builder blocksBuilder) {
            return blocks(blocksBuilder.build());
        }

        public Builder blocks(ClusterBlocks blocks) {
            this.blocks = blocks;
            return this;
        }

        public Builder version(long version) {
            this.version = version;
            return this;
        }

        public Builder incrementVersion() {
            this.version = version + 1;
            this.uuid = UNKNOWN_UUID;
            return this;
        }

        public Builder stateUUID(String uuid) {
            this.uuid = uuid;
            return this;
        }

        public Builder minimumMasterNodesOnPublishingMaster(int minimumMasterNodesOnPublishingMaster) {
            this.minimumMasterNodesOnPublishingMaster = minimumMasterNodesOnPublishingMaster;
            return this;
        }

        public Builder putCustom(String type, Custom custom) {
            customs.put(type, Objects.requireNonNull(custom, type));
            return this;
        }

        public Builder removeCustom(String type) {
            customs.remove(type);
            return this;
        }

        public Builder customs(ImmutableOpenMap<String, Custom> customs) {
            StreamSupport.stream(customs.spliterator(), false).forEach(cursor -> Objects.requireNonNull(cursor.value, cursor.key));
            this.customs.putAll(customs);
            return this;
        }

        public Builder fromDiff(boolean fromDiff) {
            this.fromDiff = fromDiff;
            return this;
        }

        public ClusterState build() {
            if (UNKNOWN_UUID.equals(uuid)) {
                uuid = UUIDs.randomBase64UUID();
            }
            return new ClusterState(clusterName, version, uuid, metaData, routingTable, nodes, blocks, customs.build(),
                minimumMasterNodesOnPublishingMaster, fromDiff);
        }

        public static byte[] toBytes(ClusterState state) throws IOException {
            BytesStreamOutput os = new BytesStreamOutput();
            state.writeTo(os);
            return BytesReference.toBytes(os.bytes());
        }

        /**
         * @param data      input bytes
         * @param localNode used to set the local node in the cluster state.
         */
        public static ClusterState fromBytes(byte[] data, DiscoveryNode localNode, NamedWriteableRegistry registry) throws IOException {
            StreamInput in = new NamedWriteableAwareStreamInput(StreamInput.wrap(data), registry);
            return readFrom(in, localNode);

        }
    }

    @Override
    public Diff<ClusterState> diff(ClusterState previousState) {
        return new ClusterStateDiff(previousState, this);
    }

    public static Diff<ClusterState> readDiffFrom(StreamInput in, DiscoveryNode localNode) throws IOException {
        return new ClusterStateDiff(in, localNode);
    }

    public static ClusterState readFrom(StreamInput in, DiscoveryNode localNode) throws IOException {
        ClusterName clusterName = new ClusterName(in);
        Builder builder = new Builder(clusterName);
        builder.version = in.readLong();
        builder.uuid = in.readString();
        builder.metaData = MetaData.readFrom(in);
        builder.routingTable = RoutingTable.readFrom(in);
        builder.nodes = DiscoveryNodes.readFrom(in, localNode);
        builder.blocks = new ClusterBlocks(in);
        int customSize = in.readVInt();
        for (int i = 0; i < customSize; i++) {
            Custom customIndexMetaData = in.readNamedWriteable(Custom.class);
            builder.putCustom(customIndexMetaData.getWriteableName(), customIndexMetaData);
        }
        builder.minimumMasterNodesOnPublishingMaster = in.getVersion().onOrAfter(Version.V_6_7_0) ? in.readVInt() : -1;
        return builder.build();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        clusterName.writeTo(out);
        out.writeLong(version);
        out.writeString(stateUUID);
        metaData.writeTo(out);
        routingTable.writeTo(out);
        nodes.writeTo(out);
        blocks.writeTo(out);
        // filter out custom states not supported by the other node
        int numberOfCustoms = 0;
        for (final ObjectCursor<Custom> cursor : customs.values()) {
            if (FeatureAware.shouldSerialize(out, cursor.value)) {
                numberOfCustoms++;
            }
        }
        out.writeVInt(numberOfCustoms);
        for (final ObjectCursor<Custom> cursor : customs.values()) {
            if (FeatureAware.shouldSerialize(out, cursor.value)) {
                out.writeNamedWriteable(cursor.value);
            }
        }
        if (out.getVersion().onOrAfter(Version.V_6_7_0)) {
            out.writeVInt(minimumMasterNodesOnPublishingMaster);
        }
    }

    private static class ClusterStateDiff implements Diff<ClusterState> {

        private final long toVersion;

        private final String fromUuid;

        private final String toUuid;

        private final ClusterName clusterName;

        private final Diff<RoutingTable> routingTable;

        private final Diff<DiscoveryNodes> nodes;

        private final Diff<MetaData> metaData;

        private final Diff<ClusterBlocks> blocks;

        private final Diff<ImmutableOpenMap<String, Custom>> customs;

        private final int minimumMasterNodesOnPublishingMaster;

        ClusterStateDiff(ClusterState before, ClusterState after) {
            fromUuid = before.stateUUID;
            toUuid = after.stateUUID;
            toVersion = after.version;
            clusterName = after.clusterName;
            routingTable = after.routingTable.diff(before.routingTable);
            nodes = after.nodes.diff(before.nodes);
            metaData = after.metaData.diff(before.metaData);
            blocks = after.blocks.diff(before.blocks);
            customs = DiffableUtils.diff(before.customs, after.customs, DiffableUtils.getStringKeySerializer(), CUSTOM_VALUE_SERIALIZER);
            minimumMasterNodesOnPublishingMaster = after.minimumMasterNodesOnPublishingMaster;
        }

        ClusterStateDiff(StreamInput in, DiscoveryNode localNode) throws IOException {
            clusterName = new ClusterName(in);
            fromUuid = in.readString();
            toUuid = in.readString();
            toVersion = in.readLong();
            routingTable = RoutingTable.readDiffFrom(in);
            nodes = DiscoveryNodes.readDiffFrom(in, localNode);
            metaData = MetaData.readDiffFrom(in);
            blocks = ClusterBlocks.readDiffFrom(in);
            customs = DiffableUtils.readImmutableOpenMapDiff(in, DiffableUtils.getStringKeySerializer(), CUSTOM_VALUE_SERIALIZER);
            minimumMasterNodesOnPublishingMaster = in.getVersion().onOrAfter(Version.V_6_7_0) ? in.readVInt() : -1;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            clusterName.writeTo(out);
            out.writeString(fromUuid);
            out.writeString(toUuid);
            out.writeLong(toVersion);
            routingTable.writeTo(out);
            nodes.writeTo(out);
            metaData.writeTo(out);
            blocks.writeTo(out);
            customs.writeTo(out);
            if (out.getVersion().onOrAfter(Version.V_6_7_0)) {
                out.writeVInt(minimumMasterNodesOnPublishingMaster);
            }
        }

        @Override
        public ClusterState apply(ClusterState state) {
            Builder builder = new Builder(clusterName);
            if (toUuid.equals(state.stateUUID)) {
                // no need to read the rest - cluster state didn't change
                return state;
            }
            if (fromUuid.equals(state.stateUUID) == false) {
                throw new IncompatibleClusterStateVersionException(state.version, state.stateUUID, toVersion, fromUuid);
            }
            builder.stateUUID(toUuid);
            builder.version(toVersion);
            builder.routingTable(routingTable.apply(state.routingTable));
            builder.nodes(nodes.apply(state.nodes));
            builder.metaData(metaData.apply(state.metaData));
            builder.blocks(blocks.apply(state.blocks));
            builder.customs(customs.apply(state.customs));
            builder.minimumMasterNodesOnPublishingMaster(minimumMasterNodesOnPublishingMaster);
            builder.fromDiff(true);
            return builder.build();
        }
    }
}
