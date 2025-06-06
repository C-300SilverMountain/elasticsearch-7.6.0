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

package org.elasticsearch.discovery.zen;

import com.carrotsearch.hppc.ObjectContainer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.util.CollectionUtil;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class ElectMasterService {

    private static final Logger logger = LogManager.getLogger(ElectMasterService.class);

    public static final Setting<Integer> DISCOVERY_ZEN_MINIMUM_MASTER_NODES_SETTING =
        Setting.intSetting("discovery.zen.minimum_master_nodes", -1, Property.Dynamic, Property.NodeScope, Property.Deprecated);

    private volatile int minimumMasterNodes;

    /**
     * a class to encapsulate all the information about a candidate in a master election
     * that is needed to decided which of the candidates should win
     */
    public static class MasterCandidate {

        public static final long UNRECOVERED_CLUSTER_VERSION = -1;

        final DiscoveryNode node;

        final long clusterStateVersion;

        public MasterCandidate(DiscoveryNode node, long clusterStateVersion) {
            Objects.requireNonNull(node);
            assert clusterStateVersion >= -1 : "got: " + clusterStateVersion;
            assert node.isMasterNode();
            this.node = node;
            this.clusterStateVersion = clusterStateVersion;
        }

        public DiscoveryNode getNode() {
            return node;
        }

        public long getClusterStateVersion() {
            return clusterStateVersion;
        }

        @Override
        public String toString() {
            return "Candidate{" +
                "node=" + node +
                ", clusterStateVersion=" + clusterStateVersion +
                '}';
        }

        /**
         * compares two candidates to indicate which the a better master.
         * A higher cluster state version is better
         *
         * @return -1 if c1 is a batter candidate, 1 if c2.
         */
        public static int compare(MasterCandidate c1, MasterCandidate c2) {
            // we explicitly swap c1 and c2 here. the code expects "better" is lower in a sorted
            // list, so if c2 has a higher cluster state version, it needs to come first.
            // 当存在多个候选节点时，根据版本号和nodeid进行选择
            //先比较集群状态版本，注意此处c2在前，c1在后（倒序）
            int ret = Long.compare(c2.clusterStateVersion, c1.clusterStateVersion);
            if (ret == 0) {
                //如果版本号相同，则比较节点ID
                ret = compareNodes(c1.getNode(), c2.getNode());
            }
            return ret;
        }
    }

    public ElectMasterService(Settings settings) {
        this.minimumMasterNodes = DISCOVERY_ZEN_MINIMUM_MASTER_NODES_SETTING.get(settings);
        logger.debug("using minimum_master_nodes [{}]", minimumMasterNodes);
    }

    public void minimumMasterNodes(int minimumMasterNodes) {
        this.minimumMasterNodes = minimumMasterNodes;
    }

    public int minimumMasterNodes() {
        return minimumMasterNodes;
    }

    public int countMasterNodes(Iterable<DiscoveryNode> nodes) {
        int count = 0;
        for (DiscoveryNode node : nodes) {
            if (node.isMasterNode()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 判断当前候选者人数是否达到法定人数，否则选主失败。
     */
    public boolean hasEnoughCandidates(Collection<MasterCandidate> candidates) {
        //候选者为空
        if (candidates.isEmpty()) {
            return false;
        }
        //默认-1，确保单节点的集群可以正常选主。通常用户会配置此参数
        if (minimumMasterNodes < 1) {
            return true;
        }
        assert candidates.stream().map(MasterCandidate::getNode).collect(Collectors.toSet()).size() == candidates.size() :
            "duplicates ahead: " + candidates;
        return candidates.size() >= minimumMasterNodes;
    }

    /**
     * Elects a new master out of the possible nodes, returning it. Returns {@code null}
     * if no master has been elected.
     */
    public MasterCandidate electMaster(Collection<MasterCandidate> candidates) {
        //这里所要投票的master节点的选择则是从候选节点数组中选择id最小版本最新的节点。
        //这样，当前节点所要在选举中投票的master节点已经被选出。
        assert hasEnoughCandidates(candidates);
        List<MasterCandidate> sortedCandidates = new ArrayList<>(candidates);
        //通过自定义的比较函数对候选者节点从小到大排序
        sortedCandidates.sort(MasterCandidate::compare);
        //返回id最小的作为临时master 或 集群状态版本号最高的
        return sortedCandidates.get(0);
    }

    /** selects the best active master to join, where multiple are discovered */
    public DiscoveryNode tieBreakActiveMasters(Collection<DiscoveryNode> activeMasters) {
        return activeMasters.stream().min(ElectMasterService::compareNodes).get();
    }

    public boolean hasEnoughMasterNodes(Iterable<DiscoveryNode> nodes) {
        final int count = countMasterNodes(nodes);
        return count > 0 && (minimumMasterNodes < 0 || count >= minimumMasterNodes);
    }

    public boolean hasTooManyMasterNodes(Iterable<DiscoveryNode> nodes) {
        final int count = countMasterNodes(nodes);
        return count > 1 && minimumMasterNodes <= count / 2;
    }

    public void logMinimumMasterNodesWarningIfNecessary(ClusterState oldState, ClusterState newState) {
        // check if min_master_nodes setting is too low and log warning
        if (hasTooManyMasterNodes(oldState.nodes()) == false && hasTooManyMasterNodes(newState.nodes())) {
            logger.warn("value for setting \"{}\" is too low. This can result in data loss! Please set it to at least a quorum of master-" +
                    "eligible nodes (current value: [{}], total number of master-eligible nodes used for publishing in this round: [{}])",
                ElectMasterService.DISCOVERY_ZEN_MINIMUM_MASTER_NODES_SETTING.getKey(), minimumMasterNodes(),
                newState.getNodes().getMasterNodes().size());
        }
    }

    /**
     * Returns the given nodes sorted by likelihood of being elected as master, most likely first.
     * Non-master nodes are not removed but are rather put in the end
     */
    static List<DiscoveryNode> sortByMasterLikelihood(Iterable<DiscoveryNode> nodes) {
        ArrayList<DiscoveryNode> sortedNodes = CollectionUtils.iterableAsArrayList(nodes);
        CollectionUtil.introSort(sortedNodes, ElectMasterService::compareNodes);
        return sortedNodes;
    }

    /**
     * Returns a list of the next possible masters.
     */
    public DiscoveryNode[] nextPossibleMasters(ObjectContainer<DiscoveryNode> nodes, int numberOfPossibleMasters) {
        List<DiscoveryNode> sortedNodes = sortedMasterNodes(Arrays.asList(nodes.toArray(DiscoveryNode.class)));
        if (sortedNodes == null) {
            return new DiscoveryNode[0];
        }
        List<DiscoveryNode> nextPossibleMasters = new ArrayList<>(numberOfPossibleMasters);
        int counter = 0;
        for (DiscoveryNode nextPossibleMaster : sortedNodes) {
            if (++counter >= numberOfPossibleMasters) {
                break;
            }
            nextPossibleMasters.add(nextPossibleMaster);
        }
        return nextPossibleMasters.toArray(new DiscoveryNode[nextPossibleMasters.size()]);
    }

    private List<DiscoveryNode> sortedMasterNodes(Iterable<DiscoveryNode> nodes) {
        List<DiscoveryNode> possibleNodes = CollectionUtils.iterableAsArrayList(nodes);
        if (possibleNodes.isEmpty()) {
            return null;
        }
        // clean non master nodes
        possibleNodes.removeIf(node -> !node.isMasterNode());
        CollectionUtil.introSort(possibleNodes, ElectMasterService::compareNodes);
        return possibleNodes;
    }

    /** master nodes go before other nodes, with a secondary sort by id **/
     private static int compareNodes(DiscoveryNode o1, DiscoveryNode o2) {
        //如有一个节点具备master资格，而另一个不具备，则把有master资格的节点排在前面
         //如果都不具备master资格，或者都具备master资格，则比较节点ID
         if (o1.isMasterNode() && !o2.isMasterNode()) {
            return -1;
        }
        if (!o1.isMasterNode() && o2.isMasterNode()) {
            return 1;
        }
        return o1.getId().compareTo(o2.getId());
    }
}
