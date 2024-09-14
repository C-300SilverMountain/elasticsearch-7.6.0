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

package org.elasticsearch.cluster.routing.allocation.decider;

import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodeFilters;
import org.elasticsearch.cluster.routing.RecoverySource;
import org.elasticsearch.cluster.routing.RoutingNode;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.allocation.RoutingAllocation;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;

import java.util.Map;

import static org.elasticsearch.cluster.node.DiscoveryNodeFilters.IP_VALIDATOR;
import static org.elasticsearch.cluster.node.DiscoveryNodeFilters.OpType.AND;
import static org.elasticsearch.cluster.node.DiscoveryNodeFilters.OpType.OR;

/**
 * This {@link AllocationDecider} control shard allocation by include and
 * exclude filters via dynamic cluster and index routing settings.
 * <p>
 * This filter is used to make explicit decision on which nodes certain shard
 * can / should be allocated. The decision if a shard can be allocated, must not
 * be allocated or should be allocated is based on either cluster wide dynamic
 * settings ({@code cluster.routing.allocation.*}) or index specific dynamic
 * settings ({@code index.routing.allocation.*}). All of those settings can be
 * changed at runtime via the cluster or the index update settings API.
 * </p>
 * Note: Cluster settings are applied first and will override index specific
 * settings such that if a shard can be allocated according to the index routing
 * settings it wont be allocated on a node if the cluster specific settings
 * would disallow the allocation. Filters are applied in the following order:
 * <ol>
 * <li>{@code required} - filters required allocations.
 * If any {@code required} filters are set the allocation is denied if the index is <b>not</b> in the set of {@code required} to allocate
 * on the filtered node</li>
 * <li>{@code include} - filters "allowed" allocations.
 * If any {@code include} filters are set the allocation is denied if the index is <b>not</b> in the set of {@code include} filters for
 * the filtered node</li>
 * <li>{@code exclude} - filters "prohibited" allocations.
 * If any {@code exclude} filters are set the allocation is denied if the index is in the set of {@code exclude} filters for the
 * filtered node</li>
 * </ol>
 *
 * FilterAllocationDecider ：定义了Allocate策略，明确指定是否允许分片分配到指定Node上，分为index级别和cluster级别
 * index.routing.allocation.require.{attribute}
 * index.routing.allocation.include{attribute}
 * index.routing.allocation.exclude.{attribute}
 * cluster.routing.allocation.require.{attribute}
 * cluster.routing.allocation.include.{attribute}
 * cluster.routing.allocation.exclude.{attribute}

 * require表示必须分配到指定node，include表示可以分配到指定node，exclude表示不允许分配到指定Node，
 * cluster的配置会覆盖index级别的配置，比如index include某个node，cluster exclude某个node，最后的结果是exclude某个node，

 * 上面{attribute}表示node的匹配方式有：
 * _name 匹配node名称，多个node名称用逗号隔开
 * _ip 匹配node ip，多个ip用逗号隔开
 * _host 匹配node的host name 多个host name用逗号隔开
 * 举例子：
 * PUT _cluster/settings
 * {
 *   "transient" : {
 *     "cluster.routing.allocation.exclude._ip" : “10.0.0.1,10.0.0.2"
 *   }
 * }
 * 以上命令：分片不允许分配到：10.0.0.1,10.0.0.2机器上
 * https://cloud.tencent.com/developer/article/1361266
 */
public class FilterAllocationDecider extends AllocationDecider {

    public static final String NAME = "filter";

    private static final String CLUSTER_ROUTING_REQUIRE_GROUP_PREFIX = "cluster.routing.allocation.require";
    private static final String CLUSTER_ROUTING_INCLUDE_GROUP_PREFIX = "cluster.routing.allocation.include";
    private static final String CLUSTER_ROUTING_EXCLUDE_GROUP_PREFIX = "cluster.routing.allocation.exclude";
    public static final Setting.AffixSetting<String> CLUSTER_ROUTING_REQUIRE_GROUP_SETTING =
        Setting.prefixKeySetting(CLUSTER_ROUTING_REQUIRE_GROUP_PREFIX + ".", key ->
            Setting.simpleString(key, value -> IP_VALIDATOR.accept(key, value), Property.Dynamic, Property.NodeScope));
    public static final Setting.AffixSetting<String> CLUSTER_ROUTING_INCLUDE_GROUP_SETTING =
        Setting.prefixKeySetting(CLUSTER_ROUTING_INCLUDE_GROUP_PREFIX + ".", key ->
            Setting.simpleString(key, value -> IP_VALIDATOR.accept(key, value), Property.Dynamic, Property.NodeScope));
    public static final Setting.AffixSetting<String>CLUSTER_ROUTING_EXCLUDE_GROUP_SETTING =
        Setting.prefixKeySetting(CLUSTER_ROUTING_EXCLUDE_GROUP_PREFIX + ".", key ->
            Setting.simpleString(key, value -> IP_VALIDATOR.accept(key, value), Property.Dynamic, Property.NodeScope));

    private volatile DiscoveryNodeFilters clusterRequireFilters;
    private volatile DiscoveryNodeFilters clusterIncludeFilters;
    private volatile DiscoveryNodeFilters clusterExcludeFilters;

    public FilterAllocationDecider(Settings settings, ClusterSettings clusterSettings) {
        setClusterRequireFilters(CLUSTER_ROUTING_REQUIRE_GROUP_SETTING.getAsMap(settings));
        setClusterExcludeFilters(CLUSTER_ROUTING_EXCLUDE_GROUP_SETTING.getAsMap(settings));
        setClusterIncludeFilters(CLUSTER_ROUTING_INCLUDE_GROUP_SETTING.getAsMap(settings));
        clusterSettings.addAffixMapUpdateConsumer(CLUSTER_ROUTING_REQUIRE_GROUP_SETTING, this::setClusterRequireFilters, (a, b) -> {});
        clusterSettings.addAffixMapUpdateConsumer(CLUSTER_ROUTING_EXCLUDE_GROUP_SETTING, this::setClusterExcludeFilters, (a, b) -> {});
        clusterSettings.addAffixMapUpdateConsumer(CLUSTER_ROUTING_INCLUDE_GROUP_SETTING, this::setClusterIncludeFilters, (a, b) -> {});
    }

    @Override
    public Decision canAllocate(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
        if (shardRouting.unassigned()) {
            // only for unassigned - we filter allocation right after the index creation (for shard shrinking) to ensure
            // that once it has been allocated post API the replicas can be allocated elsewhere without user interaction
            // this is a setting that can only be set within the system!
            IndexMetaData indexMd = allocation.metaData().getIndexSafe(shardRouting.index());
            DiscoveryNodeFilters initialRecoveryFilters = indexMd.getInitialRecoveryFilters();
            if (initialRecoveryFilters != null  &&
                shardRouting.recoverySource().getType() == RecoverySource.Type.LOCAL_SHARDS &&
                initialRecoveryFilters.match(node.node()) == false) {
                String explanation =
                    "initial allocation of the shrunken index is only allowed on nodes [%s] that hold a copy of every shard in the index";
                return allocation.decision(Decision.NO, NAME, explanation, initialRecoveryFilters);
            }
        }
        return shouldFilter(shardRouting, node.node(), allocation);
    }

    @Override
    public Decision canAllocate(IndexMetaData indexMetaData, RoutingNode node, RoutingAllocation allocation) {
        return shouldFilter(indexMetaData, node.node(), allocation);
    }

    @Override
    public Decision canRemain(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
        return shouldFilter(shardRouting, node.node(), allocation);
    }

    @Override
    public Decision shouldAutoExpandToNode(IndexMetaData indexMetaData, DiscoveryNode node, RoutingAllocation allocation) {
        Decision decision = shouldClusterFilter(node, allocation);
        if (decision != null) return decision;

        decision = shouldIndexFilter(indexMetaData, node, allocation);
        if (decision != null) return decision;

        return allocation.decision(Decision.YES, NAME, "node passes include/exclude/require filters");
    }

    private Decision shouldFilter(ShardRouting shardRouting, DiscoveryNode node, RoutingAllocation allocation) {
        Decision decision = shouldClusterFilter(node, allocation);
        if (decision != null) return decision;

        decision = shouldIndexFilter(allocation.metaData().getIndexSafe(shardRouting.index()), node, allocation);
        if (decision != null) return decision;

        return allocation.decision(Decision.YES, NAME, "node passes include/exclude/require filters");
    }

    private Decision shouldFilter(IndexMetaData indexMd, DiscoveryNode node, RoutingAllocation allocation) {
        Decision decision = shouldClusterFilter(node, allocation);
        if (decision != null) return decision;

        decision = shouldIndexFilter(indexMd, node, allocation);
        if (decision != null) return decision;

        return allocation.decision(Decision.YES, NAME, "node passes include/exclude/require filters");
    }

    private Decision shouldIndexFilter(IndexMetaData indexMd, DiscoveryNode node, RoutingAllocation allocation) {
        if (indexMd.requireFilters() != null) {
            if (indexMd.requireFilters().match(node) == false) {
                return allocation.decision(Decision.NO, NAME, "node does not match index setting [%s] filters [%s]",
                    IndexMetaData.INDEX_ROUTING_REQUIRE_GROUP_PREFIX, indexMd.requireFilters());
            }
        }
        if (indexMd.includeFilters() != null) {
            if (indexMd.includeFilters().match(node) == false) {
                return allocation.decision(Decision.NO, NAME, "node does not match index setting [%s] filters [%s]",
                    IndexMetaData.INDEX_ROUTING_INCLUDE_GROUP_PREFIX, indexMd.includeFilters());
            }
        }
        if (indexMd.excludeFilters() != null) {
            if (indexMd.excludeFilters().match(node)) {
                return allocation.decision(Decision.NO, NAME, "node matches index setting [%s] filters [%s]",
                    IndexMetaData.INDEX_ROUTING_EXCLUDE_GROUP_SETTING.getKey(), indexMd.excludeFilters());
            }
        }
        return null;
    }

    private Decision shouldClusterFilter(DiscoveryNode node, RoutingAllocation allocation) {
        if (clusterRequireFilters != null) {
            if (clusterRequireFilters.match(node) == false) {
                return allocation.decision(Decision.NO, NAME, "node does not match cluster setting [%s] filters [%s]",
                    CLUSTER_ROUTING_REQUIRE_GROUP_PREFIX, clusterRequireFilters);
            }
        }
        if (clusterIncludeFilters != null) {
            if (clusterIncludeFilters.match(node) == false) {
                return allocation.decision(Decision.NO, NAME, "node does not cluster setting [%s] filters [%s]",
                    CLUSTER_ROUTING_INCLUDE_GROUP_PREFIX, clusterIncludeFilters);
            }
        }
        if (clusterExcludeFilters != null) {
            if (clusterExcludeFilters.match(node)) {
                return allocation.decision(Decision.NO, NAME, "node matches cluster setting [%s] filters [%s]",
                    CLUSTER_ROUTING_EXCLUDE_GROUP_PREFIX, clusterExcludeFilters);
            }
        }
        return null;
    }

    private void setClusterRequireFilters(Map<String, String> filters) {
        clusterRequireFilters = DiscoveryNodeFilters.buildFromKeyValue(AND, filters);
    }
    private void setClusterIncludeFilters(Map<String, String> filters) {
        clusterIncludeFilters = DiscoveryNodeFilters.buildFromKeyValue(OR, filters);
    }
    private void setClusterExcludeFilters(Map<String, String> filters) {
        clusterExcludeFilters = DiscoveryNodeFilters.buildFromKeyValue(OR, filters);
    }
}
