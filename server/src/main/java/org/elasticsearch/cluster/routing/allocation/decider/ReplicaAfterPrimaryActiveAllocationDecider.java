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

import org.elasticsearch.cluster.routing.RoutingNode;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.allocation.RoutingAllocation;

/**
 * An allocation strategy that only allows for a replica to be allocated when the primary is active.
 * 定义了Shard维度的Allocation策略，在分配副本分片时，检查主分片的状态，防止主分片不是Active情况下分配副本分片。
 * https://cloud.tencent.com/developer/article/1361266
 */
public class ReplicaAfterPrimaryActiveAllocationDecider extends AllocationDecider {

    private static final String NAME = "replica_after_primary_active";

    @Override
    public Decision canAllocate(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
        return canAllocate(shardRouting, allocation);
    }

    @Override
    public Decision canAllocate(ShardRouting shardRouting, RoutingAllocation allocation) {
        if (shardRouting.primary()) {
            return allocation.decision(Decision.YES, NAME, "shard is primary and can be allocated");
        }
        ShardRouting primary = allocation.routingNodes().activePrimary(shardRouting.shardId());
        if (primary == null) {
            return allocation.decision(Decision.NO, NAME, "primary shard for this replica is not yet active");
        }
        return allocation.decision(Decision.YES, NAME, "primary shard for this replica is already active");
    }
}
