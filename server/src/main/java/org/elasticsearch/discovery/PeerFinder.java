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

import com.carrotsearch.hppc.cursors.ObjectCursor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.lucene.util.SetOnce;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.coordination.PeersResponse;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.AbstractRunnable;
import org.elasticsearch.discovery.zen.UnicastZenPing;
import org.elasticsearch.discovery.zen.ZenDiscovery;
import org.elasticsearch.discovery.zen.ZenPing;
import org.elasticsearch.discovery.zen.ZenPing.PingResponse;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool.Names;
import org.elasticsearch.transport.TransportChannel;
import org.elasticsearch.transport.TransportException;
import org.elasticsearch.transport.TransportRequest;
import org.elasticsearch.transport.TransportRequestHandler;
import org.elasticsearch.transport.TransportRequestOptions;
import org.elasticsearch.transport.TransportResponseHandler;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static java.util.Collections.emptyList;
import static org.elasticsearch.cluster.coordination.Coordinator.isZen1Node;
import static org.elasticsearch.cluster.coordination.DiscoveryUpgradeService.createDiscoveryNodeWithImpossiblyHighId;

public abstract class PeerFinder {

    private static final Logger logger = LogManager.getLogger(PeerFinder.class);

    public static final String REQUEST_PEERS_ACTION_NAME = "internal:discovery/request_peers";

    // the time between attempts to find all peers
    public static final Setting<TimeValue> DISCOVERY_FIND_PEERS_INTERVAL_SETTING =
        Setting.timeSetting("discovery.find_peers_interval",
            TimeValue.timeValueMillis(1000), TimeValue.timeValueMillis(1), Setting.Property.NodeScope);

    public static final Setting<TimeValue> DISCOVERY_REQUEST_PEERS_TIMEOUT_SETTING =
        Setting.timeSetting("discovery.request_peers_timeout",
            TimeValue.timeValueMillis(3000), TimeValue.timeValueMillis(1), Setting.Property.NodeScope);

    private final Settings settings;

    private final TimeValue findPeersInterval;
    private final TimeValue requestPeersTimeout;

    private final Object mutex = new Object();
    private final TransportService transportService;
    private final TransportAddressConnector transportAddressConnector;
    private final ConfiguredHostsResolver configuredHostsResolver;

    private volatile long currentTerm;
    private boolean active;
    private DiscoveryNodes lastAcceptedNodes;
    private final Map<TransportAddress, Peer> peersByAddress = new LinkedHashMap<>();
    private Optional<DiscoveryNode> leader = Optional.empty();
    private volatile List<TransportAddress> lastResolvedAddresses = emptyList();

    public PeerFinder(Settings settings, TransportService transportService, TransportAddressConnector transportAddressConnector,
                      ConfiguredHostsResolver configuredHostsResolver) {
        this.settings = settings;
        findPeersInterval = DISCOVERY_FIND_PEERS_INTERVAL_SETTING.get(settings);
        requestPeersTimeout = DISCOVERY_REQUEST_PEERS_TIMEOUT_SETTING.get(settings);
        this.transportService = transportService;
        this.transportAddressConnector = transportAddressConnector;
        this.configuredHostsResolver = configuredHostsResolver;

        transportService.registerRequestHandler(REQUEST_PEERS_ACTION_NAME, Names.GENERIC, false, false,
            PeersRequest::new,
            (request, channel, task) -> channel.sendResponse(handlePeersRequest(request)));

        transportService.registerRequestHandler(UnicastZenPing.ACTION_NAME, Names.GENERIC, false, false,
            UnicastZenPing.UnicastPingRequest::new, new Zen1UnicastPingRequestHandler());
    }

    public void activate(final DiscoveryNodes lastAcceptedNodes) {
        logger.trace("activating with {}", lastAcceptedNodes);

        // 加锁，防止并发
        synchronized (mutex) {
            // 简单的参数条件判断
            assert assertInactiveWithNoKnownPeers();
            // 激活探活流程，表示探活流程进行中
            active = true;
            this.lastAcceptedNodes = lastAcceptedNodes;
            leader = Optional.empty();
            // 1、首先与discovery.seed_providers中所有节点连接，并调用相应接口获取对方已知的集群节点列表，然后分别与这些节点建立链接，进行通信
            // 跟具备master参选资格的节点建立起链接，当链接成功个数满足过半时，立即就发起预选举，没必要等所有链接建立完成后，才发起预选举流程。
            handleWakeUp(); // return value discarded: there are no known peers, so none can be disconnected
        }
        // 触发onFoundPeersUpdated()1次
        onFoundPeersUpdated(); // trigger a check for a quorum already
    }

    public void deactivate(DiscoveryNode leader) {
        final boolean peersRemoved;
        synchronized (mutex) {
            logger.trace("deactivating and setting leader to {}", leader);
            active = false;
            peersRemoved = handleWakeUp();
            this.leader = Optional.of(leader);
            assert assertInactiveWithNoKnownPeers();
        }
        if (peersRemoved) {
            onFoundPeersUpdated();
        }
    }

    // exposed to subclasses for testing
    protected final boolean holdsLock() {
        return Thread.holdsLock(mutex);
    }

    private boolean assertInactiveWithNoKnownPeers() {
        assert holdsLock() : "PeerFinder mutex not held";
        assert active == false;
        assert peersByAddress.isEmpty() : peersByAddress.keySet();
        return true;
    }

    PeersResponse handlePeersRequest(PeersRequest peersRequest) {
        synchronized (mutex) {
            assert peersRequest.getSourceNode().equals(getLocalNode()) == false;
            final List<DiscoveryNode> knownPeers;
            if (active) {
                assert leader.isPresent() == false : leader;
                if (peersRequest.getSourceNode().isMasterNode()) {
                    startProbe(peersRequest.getSourceNode().getAddress());
                }
                peersRequest.getKnownPeers().stream().map(DiscoveryNode::getAddress).forEach(this::startProbe);
                knownPeers = getFoundPeersUnderLock();
            } else {
                assert leader.isPresent() || lastAcceptedNodes == null;
                knownPeers = emptyList();
            }
            return new PeersResponse(leader, knownPeers, currentTerm);
        }
    }

    // exposed for checking invariant in o.e.c.c.Coordinator (public since this is a different package)
    public Optional<DiscoveryNode> getLeader() {
        synchronized (mutex) {
            return leader;
        }
    }

    // exposed for checking invariant in o.e.c.c.Coordinator (public since this is a different package)
    public long getCurrentTerm() {
        return currentTerm;
    }

    public void setCurrentTerm(long currentTerm) {
        this.currentTerm = currentTerm;
    }

    private DiscoveryNode getLocalNode() {
        final DiscoveryNode localNode = transportService.getLocalNode();
        assert localNode != null;
        return localNode;
    }

    /**
     * Invoked on receipt of a PeersResponse from a node that believes it's an active leader, which this node should therefore try and join.
     * Note that invocations of this method are not synchronised. By the time it is called we may have been deactivated.
     */
    protected abstract void onActiveMasterFound(DiscoveryNode masterNode, long term);

    /**
     * Invoked when the set of found peers changes. Note that invocations of this method are not fully synchronised, so we only guarantee
     * that the change to the set of found peers happens before this method is invoked. If there are multiple concurrent changes then there
     * will be multiple concurrent invocations of this method, with no guarantee as to their order. For this reason we do not pass the
     * updated set of peers as an argument to this method, leaving it to the implementation to call getFoundPeers() with appropriate
     * synchronisation to avoid lost updates. Also, by the time this method is invoked we may have been deactivated.
     */
    protected abstract void onFoundPeersUpdated();

    public List<TransportAddress> getLastResolvedAddresses() {
        return lastResolvedAddresses;
    }

    public interface TransportAddressConnector {
        /**
         * Identify the node at the given address and, if it is a master node and not the local node then establish a full connection to it.
         */
        void connectToRemoteMasterNode(TransportAddress transportAddress, ActionListener<DiscoveryNode> listener);
    }

    public interface ConfiguredHostsResolver {
        /**
         * Attempt to resolve the configured unicast hosts list to a list of transport addresses.
         *
         * @param consumer Consumer for the resolved list. May not be called if an error occurs or if another resolution attempt is in
         *                 progress.
         */
        void resolveConfiguredHosts(Consumer<List<TransportAddress>> consumer);
    }

    public Iterable<DiscoveryNode> getFoundPeers() {
        synchronized (mutex) {
            return getFoundPeersUnderLock();
        }
    }

    private List<DiscoveryNode> getFoundPeersUnderLock() {
        assert holdsLock() : "PeerFinder mutex not held";
        // 此处要求Peer的discoveryNode属性不为空，只有建立连接成功，Peer的discoveryNode才不会为空
        // 也就是，统计过半时，最低要求是 过半节点能连接成功即可发起选举
        // 此处加了distinct()，说明存在重复数据，重复数据何来？在handleWakeUp方法中，两次调用startProbe
        // 第一次：根据集群状态中的节点信息执行startProbe
        // 第二次：根据配置文件中的节点信息执行startProbe
        // 而集群状态中的节点信息 与 配置文件中的节点信息 存在相同数据，如此就会导致 peersByAddress存在重复数据
        return peersByAddress.values().stream()
            .map(Peer::getDiscoveryNode).filter(Objects::nonNull).distinct().collect(Collectors.toList());
    }

    private Peer createConnectingPeer(TransportAddress transportAddress) {
        // 创建peer,代表远程端点，peer内部有DiscoveryNode属性，代表与远程端点的链接
        Peer peer = new Peer(transportAddress);
        // 建立链接：建立成功后，确保能ping通，所以establishConnection包含了两个步骤：一：建立链接；二：ping接口
        peer.establishConnection();
        return peer;
    }

    /**
     * @return whether any peers were removed due to disconnection
     */
    private boolean handleWakeUp() {
        assert holdsLock() : "PeerFinder mutex not held";
        // peersByAddress: 是保存在内存中的，只有已参与过选举，再次发生选举的节点，此值才不为空
        // peersByAddress为端点列表，遍历此列表，逐个建立与本端点的链接，如建立失败，则从端点列表中删除
        // Peer::handleWakeUp  返回true的话，则从peersByAddress当中删除
        final boolean peersRemoved = peersByAddress.values().removeIf(Peer::handleWakeUp);

        if (active == false) {
            // 说明：探活流程被取消。好家伙，刚开始就有可能被取消
            logger.trace("not active");
            return peersRemoved;
        }
        // 从集群状态中解析出：具有选举资格的节点，初次启动，肯定为空，所以加了一个步骤：resolveConfiguredHosts：从配置文件中读取
        // 如果某个节点在集群中运行过一段时间后，发生重启,或主节点掉线/宕机，那么大概率通过此步骤就触发重新选举，而不会走到resolveConfiguredHosts函数
        logger.trace("probing master nodes from cluster state: {}", lastAcceptedNodes);
        // lastAcceptedNodes：是从集群状态里获取的，所以说初次加入集群的节点，lastAcceptedNodes为空
        // 遍历所有具备选举资格的节点，并执行探测（Probe=探测）,其实就是ping对方，当ping通的节点数 过半，即执行预选举
        for (ObjectCursor<DiscoveryNode> discoveryNodeObjectCursor : lastAcceptedNodes.getMasterNodes().values()) {
            startProbe(discoveryNodeObjectCursor.value.getAddress());
        }
        // startProbe调用了两轮，对于某个节点，可能存在多次执行连接操作 => 冗余操作了
        configuredHostsResolver.resolveConfiguredHosts(providedAddresses -> {
            synchronized (mutex) {
                lastResolvedAddresses = providedAddresses;
                logger.trace("probing resolved transport addresses {}", providedAddresses);
                providedAddresses.forEach(this::startProbe);
            }
        });

        //周期性发现探测--核心作用
        //(1) 周期性节点发现
        //目标：非主节点（Data Node 或 Client Node）通过定期发送探测请求，主动发现集群中的其他节点（尤其是主节点候选）。
        //(2) 维护集群拓扑一致性
        //场景：当节点加入或离开集群时，其他节点需要通过周期性探测快速感知变化，更新本地集群状态。
        // findPeersInterval 默认1秒
        transportService.getThreadPool().scheduleUnlessShuttingDown(findPeersInterval, Names.GENERIC, new AbstractRunnable() {
            @Override
            public boolean isForceExecution() {
                return true;
            }

            @Override
            public void onFailure(Exception e) {
                assert false : e;
                logger.debug("unexpected exception in wakeup", e);
            }

            @Override
            protected void doRun() {
                synchronized (mutex) {
                    // 任意一个节点探测不成功，都会返回true
                    // handleWakeUp()内部：遍历peersByAddress，逐个调用相应接口，获取其已知集群节点，并其建立连接（服务发现）
                    if (handleWakeUp() == false) {
                        // 没有任意节点从peersByAddress集合被剔除，即peersByAddress中所有节点都能正常连通
                        return;
                    }
                }
                // 触发onFoundPeersUpdated()3次
                // 疑问一：这里定期执行onFoundPeersUpdated，难道每次都得重新选举？
                // 答：非也。选举流程结束后，节点状态要么是主节点，要么是从节点，不会是候选节点。onFoundPeersUpdated内部，只有mode == Mode.CANDIDATE时，才会发起选举
                onFoundPeersUpdated();

                // doRun是定期执行。假如选举流程被其他线程触发，即mode == Mode.CANDIDATE
                // 而doRun会判断是否Mode.CANDIDATE，是则执行选举，此时就会出现多线程执行选举，此时增加了synchronized (mutex)解决并发问题
            }

            @Override
            public String toString() {
                return "PeerFinder handling wakeup";
            }
        });

        return peersRemoved;
    }

    // 启动探查
    // startProbe 是 Elasticsearch 节点启动过程中 集群发现和健康检查的核心机制，其作用包括：
    // 1、探测种子节点或主节点，确保网络连通性。
    // 2、验证主节点选举条件，避免脑裂。
    // 3、同步集群状态，保证数据一致性。
    protected void startProbe(TransportAddress transportAddress) {
        // 验证是否锁定 mutex对象，如没有，则报错
        assert holdsLock() : "PeerFinder mutex not held";
        // 验证探查状态是否激活，如没有，则返回
        if (active == false) {
            logger.trace("startProbe({}) not running", transportAddress);
            return;
        }
        // 校验目标节点是否当前节点，如是，则返回。因为没必要，连接自身
        if (transportAddress.equals(getLocalNode().getAddress())) {
            logger.trace("startProbe({}) not probing local node", transportAddress);
            return;
        }
        // 创建与远程节点的链接，并保存到 peersByAddress,注：peersByAddress保存的是：具备选举资格的节点
        // 远程节点transportAddress不存在于peersByAddress中，才会触发createConnectingPeer，避免死循环
        peersByAddress.computeIfAbsent(transportAddress, this::createConnectingPeer);

    }

    private class Peer {
        private final TransportAddress transportAddress;
        private SetOnce<DiscoveryNode> discoveryNode = new SetOnce<>();
        // 这个标志表示：正在执行ping的过程。因为是异步的，所以加一个标识，表示正在执行ping过程当中
        private volatile boolean peersRequestInFlight;

        Peer(TransportAddress transportAddress) {
            this.transportAddress = transportAddress;
        }

        @Nullable
        DiscoveryNode getDiscoveryNode() {
            return discoveryNode.get();
        }

        /**
         * 判断指定节点是否可通
         * @return 返回true说明节点不可用，得删除。返回false说明节点可用，不能删除
         */
        boolean handleWakeUp() {
            assert holdsLock() : "PeerFinder mutex not held";

            if (active == false) {
                return true;
            }

            //远程节点
            final DiscoveryNode discoveryNode = getDiscoveryNode();
            // may be null if connection not yet established

            if (discoveryNode != null) {
                // transportService.nodeConnected：证明该节点已链接
                if (transportService.nodeConnected(discoveryNode)) {
                    // peersRequestInFlight：请求同辈是否在执行过程中
                    if (peersRequestInFlight == false) {

                        // 内部会使peersRequestInFlight=true
                        // 服务发现最关键一步：调用远程节点discoveryNode的接口，获取对方已知的集群节点列表，然后分别与这些节点建立链接，进行通信
                        // 然后将可连通的节点，放入peersByAddress集合
                        requestPeers();
                    }
                } else {
                    logger.trace("{} no longer connected", this);
                    return true;
                }
            }

            return false;
        }

        /**
         * 建立链接：注是，与具有选举资格的节点建立链接
         */
        void establishConnection() {
            // 校验是否持有mutex锁，如没有，则报错。好几个地方都反复校验这个锁
            assert holdsLock() : "PeerFinder mutex not held";
            // 必须保证getDiscoveryNode()为空，否则报错，暂时未知原因
            // 下面创建连接成功后，执行了discoveryNode.set(remoteNode)，说明该节点已连接成功，discoveryNode.set(remoteNode)作用：保存连接。
            assert getDiscoveryNode() == null : "unexpectedly connected to " + getDiscoveryNode();
            // 校验探查状态是否激活，如没有，则报错。好几个地方都反复校验探查状态
            assert active;

            logger.trace("{} attempting connection", this);
            // 此函数仅仅是建立  【当前节点与远程节点】  通信的连接而已，至于该链接是否可用，还需要执行 requestPeers(),来ping一下接口，验证接口是否正常
            transportAddressConnector.connectToRemoteMasterNode(transportAddress, new ActionListener<DiscoveryNode>() {
                @Override
                public void onResponse(DiscoveryNode remoteNode) {
                    // 对方节点必须是 具备选举资格的节点
                    assert remoteNode.isMasterNode() : remoteNode + " is not master-eligible";
                    // 对方节点不能是 本节点
                    assert remoteNode.equals(getLocalNode()) == false : remoteNode + " is the local node";
                    // 校验是否持有mutex锁，如没有，则报错。好几个地方都反复校验这个锁
                    synchronized (mutex) {
                        // 校验探查状态是否激活，如没有，则报错。好几个地方都反复校验探查状态
                        if (active == false) {
                            return;
                        }
                        // Peer代表端点，discoveryNode代表连接
                        // 这里校验保存连接之前，discoveryNode必须为空，否则报错
                        assert discoveryNode.get() == null : "discoveryNode unexpectedly already set to " + discoveryNode.get();
                        // 保存连接
                        discoveryNode.set(remoteNode);
                        // 实现服务发现：发送ping命令，拿到对方已知的节点集合，分别与每个节点建立连接
                        requestPeers();
                    }

                    assert holdsLock() == false : "PeerFinder mutex is held in error";
                    // 触发onFoundPeersUpdated()2次
                    onFoundPeersUpdated();
                }

                @Override
                public void onFailure(Exception e) {
                    logger.debug(() -> new ParameterizedMessage("{} connection failed", Peer.this), e);
                    synchronized (mutex) {
                        peersByAddress.remove(transportAddress);
                    }
                }
            });
        }

        private void requestPeers() {
            // 校验是否持有mutex锁，如没有，则报错。好几个地方都反复校验这个锁
            assert holdsLock() : "PeerFinder mutex not held";
            // 校验ping目标是否处于 【进行中】 ，InFlight=飞行过程中
            assert peersRequestInFlight == false : "PeersRequest already in flight";
            // 校验探查状态是否激活，如没有，则报错。好几个地方都反复校验探查状态
            assert active;
            // 目标节点，getDiscoveryNode()不为空，证明已连接成功，只是还没有ping通某接口
            final DiscoveryNode discoveryNode = getDiscoveryNode();
            assert discoveryNode != null : "cannot request peers without first connecting";
            // 对方节点不能是 本节点，因为没有必要。当然这里不是通过IP判断是否同一个节点，而是通过节点ID
            // 也就是说，只要节点ID不同，无论这些节点部署在各机器上，都认为是是不同的节点
            if (discoveryNode.equals(getLocalNode())) {
                logger.trace("{} not requesting peers from local node", this);
                return;
            }

            logger.trace("{} requesting peers", this);
            // 请求的状态：代表该请求处于【正在执行中】，InFlight=飞行过程中
            peersRequestInFlight = true;
            // 获取到当前节点已ping通的节点列表，这些节点都是具备选举资格的节点
            // 然后将这些节点告知 discoveryNode节点，酱紫实现的：扩散功能
            // 也就是说：一个节点上线，仅配置集群中一个节点信息，即可感知其他节点
            final List<DiscoveryNode> knownNodes = getFoundPeersUnderLock();

            final TransportResponseHandler<PeersResponse> peersResponseHandler = new TransportResponseHandler<PeersResponse>() {

                @Override
                public PeersResponse read(StreamInput in) throws IOException {
                    return new PeersResponse(in);
                }

                @Override
                public void handleResponse(PeersResponse response) {
                    logger.trace("{} received {}", Peer.this, response);
                    // 校验是否持有mutex锁，如没有，则报错。好几个地方都反复校验这个锁
                    synchronized (mutex) {
                        // 校验探查状态是否激活，如没有，则报错。好几个地方都反复校验探查状态
                        if (active == false) {
                            return;
                        }
                        // 请求的状态：代表该请求处于【结束】，InFlight=飞行过程中
                        peersRequestInFlight = false;
                        // 当发现主节点，则去ping主节点
                        response.getMasterNode().map(DiscoveryNode::getAddress).ifPresent(PeerFinder.this::startProbe);
                        // 当发现具备选举资格的节点，则去ping这些节点
                        // response是远程节点响应信息，包含了远程节点知道的集群中所有其他节点的信息
                        // 如此当前节点已拿到了集群中所有其他节点的信息，并与他们建立链接
                        response.getKnownPeers().stream().map(DiscoveryNode::getAddress).forEach(PeerFinder.this::startProbe);
                    }
                    // 说明当前节点 ping通 了远程主节点
                    if (response.getMasterNode().equals(Optional.of(discoveryNode))) {
                        // Must not hold lock here to avoid deadlock
                        assert holdsLock() == false : "PeerFinder mutex is held in error";
                        // ping通过主节点，response.getTerm()=主节点的任期
                        onActiveMasterFound(discoveryNode, response.getTerm());
                    }
                }

                @Override
                public void handleException(TransportException exp) {
                    // 从异常处理可以看出：即使ping失败了，也不做任何处理。换句话说：无论ping通与否，都不影响选举。
                    // 只要建立连接成功，且过半数，即可执行选举。无须考虑ping通与否
                    peersRequestInFlight = false;
                    logger.debug(new ParameterizedMessage("{} peers request failed", Peer.this), exp);
                }

                @Override
                public String executor() {
                    return Names.GENERIC;
                }
            };
            final String actionName;
            final TransportRequest transportRequest;
            final TransportResponseHandler<?> transportResponseHandler;
            if (isZen1Node(discoveryNode)) {
                // 旧版选举算法：bully
                actionName = UnicastZenPing.ACTION_NAME;
                transportRequest = new UnicastZenPing.UnicastPingRequest(1, ZenDiscovery.PING_TIMEOUT_SETTING.get(settings),
                    new ZenPing.PingResponse(createDiscoveryNodeWithImpossiblyHighId(getLocalNode()), null,
                        ClusterName.CLUSTER_NAME_SETTING.get(settings), ClusterState.UNKNOWN_VERSION));
                transportResponseHandler = peersResponseHandler.wrap(ucResponse -> {
                    Optional<DiscoveryNode> optionalMasterNode = Arrays.stream(ucResponse.pingResponses)
                        .filter(pr -> discoveryNode.equals(pr.node()) && discoveryNode.equals(pr.master()))
                        .map(ZenPing.PingResponse::node)
                        .findFirst();
                    List<DiscoveryNode> discoveredNodes = new ArrayList<>();
                    if (optionalMasterNode.isPresent() == false) {
                        Arrays.stream(ucResponse.pingResponses).map(PingResponse::master).filter(Objects::nonNull)
                            .forEach(discoveredNodes::add);
                        Arrays.stream(ucResponse.pingResponses).map(PingResponse::node).forEach(discoveredNodes::add);
                    }
                    return new PeersResponse(optionalMasterNode, discoveredNodes, 0L);
                }, UnicastZenPing.UnicastPingResponse::new);
            } else {
                // 新版选举算法：raft
                actionName = REQUEST_PEERS_ACTION_NAME;
                transportRequest = new PeersRequest(getLocalNode(), knownNodes);
                transportResponseHandler = peersResponseHandler;
            }
            transportService.sendRequest(discoveryNode, actionName,
                transportRequest,
                TransportRequestOptions.builder().withTimeout(requestPeersTimeout).build(),
                transportResponseHandler);
        }

        @Override
        public String toString() {
            return "Peer{" +
                "transportAddress=" + transportAddress +
                ", discoveryNode=" + discoveryNode.get() +
                ", peersRequestInFlight=" + peersRequestInFlight +
                '}';
        }
    }

    private class Zen1UnicastPingRequestHandler implements TransportRequestHandler<UnicastZenPing.UnicastPingRequest> {
        @Override
        public void messageReceived(UnicastZenPing.UnicastPingRequest request, TransportChannel channel, Task task) throws Exception {
            final PeersRequest peersRequest = new PeersRequest(request.pingResponse.node(),
                Optional.ofNullable(request.pingResponse.master()).map(Collections::singletonList).orElse(emptyList()));
            final PeersResponse peersResponse = handlePeersRequest(peersRequest);
            final List<ZenPing.PingResponse> pingResponses = new ArrayList<>();
            final ClusterName clusterName = ClusterName.CLUSTER_NAME_SETTING.get(settings);
            pingResponses.add(new ZenPing.PingResponse(createDiscoveryNodeWithImpossiblyHighId(transportService.getLocalNode()),
                peersResponse.getMasterNode().orElse(null),
                clusterName, ClusterState.UNKNOWN_VERSION));
            peersResponse.getKnownPeers().forEach(dn -> pingResponses.add(
                new ZenPing.PingResponse(ZenPing.PingResponse.FAKE_PING_ID,
                    isZen1Node(dn) ? dn : createDiscoveryNodeWithImpossiblyHighId(dn), null, clusterName, ClusterState.UNKNOWN_VERSION)));
            channel.sendResponse(new UnicastZenPing.UnicastPingResponse(request.id, pingResponses.toArray(new ZenPing.PingResponse[0])));
        }
    }
}
