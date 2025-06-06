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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.lucene.store.AlreadyClosedException;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateTaskConfig;
import org.elasticsearch.cluster.ClusterStateTaskListener;
import org.elasticsearch.cluster.NotMasterException;
import org.elasticsearch.cluster.coordination.JoinTaskExecutor;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.RerouteService;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.cluster.service.MasterService;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This class processes incoming join request (passed zia {@link ZenDiscovery}). Incoming nodes
 * are directly added to the cluster state or are accumulated during master election.
 */
public class NodeJoinController {

    private static final Logger logger = LogManager.getLogger(NodeJoinController.class);

    private final MasterService masterService;
    private final JoinTaskExecutor joinTaskExecutor;

    // this is set while trying to become a master
    // mutation should be done under lock
    // 代表单轮选举流程，一旦选举流程结束，electionContext会被清空
    private ElectionContext electionContext = null;


    public NodeJoinController(Settings settings, MasterService masterService, AllocationService allocationService,
                              ElectMasterService electMaster, RerouteService rerouteService) {
        this.masterService = masterService;
        joinTaskExecutor = new JoinTaskExecutor(settings, allocationService, logger, rerouteService) {
            @Override
            public void clusterStatePublished(ClusterChangedEvent event) {
                electMaster.logMinimumMasterNodesWarningIfNecessary(event.previousState(), event.state());
            }
        };
    }

    /**
     * waits for enough incoming joins from master eligible nodes to complete the master election
     * <p>
     * You must start accumulating joins before calling this method. See {@link #startElectionContext()}
     * <p>
     * The method will return once the local node has been elected as master or some failure/timeout has happened.
     * The exact outcome is communicated via the callback parameter, which is guaranteed to be called.
     *
     * @param requiredMasterJoins the number of joins from master eligible needed to complete the election
     * @param timeValue           how long to wait before failing. a timeout is communicated via the callback's onFailure method.
     * @param callback            the result of the election (success or failure) will be communicated by calling methods on this
     *                            object
     **/
    public void waitToBeElectedAsMaster(int requiredMasterJoins, TimeValue timeValue, final ElectionCallback callback) {
        // 注意done.await配置了超时施加，也就是说，在指定时间内，没有触发done.countDown()，那么就当超时处理：发起重选举
        final CountDownLatch done = new CountDownLatch(1);
        // 集群中任意一个节点已执行提交状态，都会触发ElectionCallback
        final ElectionCallback wrapperCallback = new ElectionCallback() {
            @Override
            public void onElectedAsMaster(ClusterState state) {
                // 选举成功，唤醒发起选举的线程，继续执行
                done.countDown();
                //执行正式成为master节点要做的流程
                callback.onElectedAsMaster(state);
            }

            @Override
            public void onFailure(Throwable t) {
                // 选举失败，唤醒发起选举的线程，继续执行
                done.countDown();
                callback.onFailure(t);
            }
        };

        ElectionContext myElectionContext = null;

        try {
            // check what we have so far..
            // capture the context we add the callback to make sure we fail our own
            synchronized (this) {
                assert electionContext != null : "waitToBeElectedAsMaster is called we are not accumulating joins";
                myElectionContext = electionContext;
                // requiredMasterJoins的值来源于：discovery.zen.minimum_master_nodes
                // 此处将requiredMasterJoins的值保存到选举容器中，每一次选举容器都是新对象，选举完成后，就会清除选举容器
                // 选举结果的callback：wrapperCallback
                // 什么时候回调electionContext.wrapperCallback
                electionContext.onAttemptToBeElected(requiredMasterJoins, wrapperCallback);
                // 执行一次统计选票是否过半：因为投给本地节点的其他节点 可能先执行了选举流程且投给了本节点
                checkPendingJoinsAndElectIfNeeded();
            }

            //此处实际等待handleJoinRequest，收集到足够的选票，进入发布状态流程，当发布状态成功才会触发wrapperCallback，唤醒done.await
            try {
                //等待投票：默认等待30s，失败则重新选举
                if (done.await(timeValue.millis(), TimeUnit.MILLISECONDS)) {
                    // callback handles everything
                    // 在超时时间内，收到足够选票，则正常返回。后面的代码不执行
                    return;
                }
            } catch (InterruptedException e) {

            }
            // 如果done.await执行成功，后面的代码不会执行，因为已经return
            // 如果执行失败，则执行后面的代码：发起再次选举流程
            if (logger.isTraceEnabled()) {
                final int pendingNodes = myElectionContext.getPendingMasterJoinsCount();
                logger.trace("timed out waiting to be elected. waited [{}]. pending master node joins [{}]", timeValue, pendingNodes);
            }

            // 如果在规定的timeout里，并没有收到足够的投票，那么说明本节点的选举失败。failContextIfNeeded会触发onFailure
            // 则会回到通过markThreadAsDoneAndStartNew()关闭当前线程，并重新启动一个线程在startNewThreadIfNotRunning()方法中开始下一次循环中，继续上述选举的流程参与选举。

            // https://blog.csdn.net/kissfox220/article/details/119956861
            // 如果30秒内没有完成选举、则放弃本轮选举，发布选举失败信息，通知集群内其他节点开始新一轮临时master选举
            // 触发callback.onFailure
            failContextIfNeeded(myElectionContext, "timed out waiting to be elected");
        } catch (Exception e) {
            logger.error("unexpected failure while waiting for incoming joins", e);
            if (myElectionContext != null) {
                failContextIfNeeded(myElectionContext, "unexpected failure while waiting for pending joins [" + e.getMessage() + "]");
            }
        }
    }

    /**
     * utility method to fail the given election context under the cluster state thread
     */
    private synchronized void failContextIfNeeded(final ElectionContext context, final String reason) {
        if (electionContext == context) {
            stopElectionContext(reason);
        }
    }

    /**
     * Accumulates any future incoming join request. Pending join requests will be processed in the final steps of becoming a
     * master or when {@link #stopElectionContext(String)} is called.
     */
    public synchronized void startElectionContext() {
        logger.trace("starting an election context, will accumulate joins");
        assert electionContext == null : "double startElectionContext() calls";
        electionContext = new ElectionContext();
    }

    /**
     * Stopped accumulating joins. All pending joins will be processed. Future joins will be processed immediately
     */
    public void stopElectionContext(String reason) {
        logger.trace("stopping election ([{}])", reason);
        synchronized (this) {
            assert electionContext != null : "stopElectionContext() called but not accumulating";
            electionContext.closeAndProcessPending(reason);
            electionContext = null;
        }
    }

    /**
     * processes or queues an incoming join request.
     * <p>
     * Note: doesn't do any validation. This should have been done before.
     */
    public synchronized void handleJoinRequest(final DiscoveryNode node, final MembershipAction.JoinCallback callback) {
        if (electionContext != null) {
            electionContext.addIncomingJoin(node, callback);
            checkPendingJoinsAndElectIfNeeded();
        } else {
            // 什么时候会触发此处：当集群已选出主节点，electionContext就会被清除，后续再有新节点申请加入集群，此类情况就会触发这行代码
            masterService.submitStateUpdateTask("zen-disco-node-join",
                new JoinTaskExecutor.Task(node, "no election context"), ClusterStateTaskConfig.build(Priority.URGENT),
                joinTaskExecutor, new JoinTaskListener(callback, logger));
        }
    }

    /**
     * checks if there is an on going request to become master and if it has enough pending joins. If so, the node will
     * become master via a ClusterState update task.
     */
    // 在checkPendingJoinsAndElectIfNeed()方法中，如果已经接收到的join请求也就是投票自己的节点数量已经超过集群中节点数量的半数，
    // 那么调用closeAndBecomeMaster()方法结束本次选举正式成为master节点。
    private synchronized void checkPendingJoinsAndElectIfNeeded() {
        assert electionContext != null : "election check requested but no active context";
        final int pendingMasterJoins = electionContext.getPendingMasterJoinsCount();
        //过半协议判断：检查接收到的且有选举资格的投票数是否满足最小投票数
        //一旦满足过半协议，立即成为“主节点”
        if (electionContext.isEnoughPendingJoins(pendingMasterJoins) == false) {
            if (logger.isTraceEnabled()) {
                logger.trace("not enough joins for election. Got [{}], required [{}]", pendingMasterJoins,
                    electionContext.requiredMasterJoins);
            }
        } else {
            if (logger.isTraceEnabled()) {
                logger.trace("have enough joins for election. Got [{}], required [{}]", pendingMasterJoins,
                    electionContext.requiredMasterJoins);
            }
            // 当前节点成为“主节点”，并发布（广播）集群状态 & 定期发送ping到集群中所有其他节点
            // 依赖ZenDiscovery.publish广播集群状态，其实就是通知其他节点，本节点已成为“大佬”
            electionContext.closeAndBecomeMaster();
            // 清空选举容器，表示此轮选举结束，防止后续再次选举的时候导致累加
            // 变空后，failContextIfNeeded就不会执行，也就不会再发起一次选举
            electionContext = null; // clear this out so future joins won't be accumulated
        }
    }

    public interface ElectionCallback {
        /**
         * called when the local node is successfully elected as master
         * Guaranteed to be called on the cluster state update thread
         **/
        void onElectedAsMaster(ClusterState state);

        /**
         * called when the local node failed to be elected as master
         * Guaranteed to be called on the cluster state update thread
         **/
        void onFailure(Throwable t);
    }

    class ElectionContext {
        private ElectionCallback callback = null;
        private int requiredMasterJoins = -1;
        private final Map<DiscoveryNode, List<MembershipAction.JoinCallback>> joinRequestAccumulator = new HashMap<>();

        final AtomicBoolean closed = new AtomicBoolean();

        public synchronized void onAttemptToBeElected(int requiredMasterJoins, ElectionCallback callback) {
            ensureOpen();
            assert this.requiredMasterJoins < 0;
            assert this.callback == null;
            this.requiredMasterJoins = requiredMasterJoins;
            this.callback = callback;
        }

        public synchronized void addIncomingJoin(DiscoveryNode node, MembershipAction.JoinCallback callback) {
            ensureOpen();
            joinRequestAccumulator.computeIfAbsent(node, n -> new ArrayList<>()).add(callback);
        }


        public synchronized boolean isEnoughPendingJoins(int pendingMasterJoins) {
            // pendingMasterJoins的值来源于：discovery.zen.minimum_master_nodes（写死的），一旦配错，引发脑裂。如配置成discovery.zen.minimum_master_nodes=1
            //
            final boolean hasEnough;
            if (requiredMasterJoins < 0) {
                // requiredMasterNodes is unknown yet, return false and keep on waiting
                hasEnough = false;
            } else {
                assert callback != null : "requiredMasterJoins is set but not the callback";
                hasEnough = pendingMasterJoins >= requiredMasterJoins;
            }
            return hasEnough;
        }

        private Map<JoinTaskExecutor.Task, ClusterStateTaskListener> getPendingAsTasks(String reason) {
            Map<JoinTaskExecutor.Task, ClusterStateTaskListener> tasks = new HashMap<>();
            joinRequestAccumulator.entrySet().stream().forEach(e -> tasks.put(
                new JoinTaskExecutor.Task(e.getKey(), reason), new JoinTaskListener(e.getValue(), logger)));
            return tasks;
        }

        public synchronized int getPendingMasterJoinsCount() {
            int pendingMasterJoins = 0;
            //遍历当前收到的join请求
            for (DiscoveryNode node : joinRequestAccumulator.keySet()) {
                //过滤掉不具备master资格的节点
                if (node.isMasterNode()) {
                    pendingMasterJoins++;
                }
            }
            return pendingMasterJoins;
        }

        public synchronized void closeAndBecomeMaster() {
            assert callback != null : "becoming a master but the callback is not yet set";
            assert isEnoughPendingJoins(getPendingMasterJoinsCount()) : "becoming a master but pending joins of "
                + getPendingMasterJoinsCount() + " are not enough. needs [" + requiredMasterJoins + "];";

            innerClose();
            // 两处的getPendingAsTasks返回的并非执行集群任务线程池的等待队列，而是选主流程中等待加入集群的请求数量。
            Map<JoinTaskExecutor.Task, ClusterStateTaskListener> tasks = getPendingAsTasks("become master");
            final String source = "zen-disco-elected-as-master ([" + tasks.size() + "] nodes joined)";

            // noop listener, the election finished listener determines result
            // Task的执行结果，会通知到Listener。也就是说，先执行Task,然后将Task的执行结果通知到Listener
            tasks.put(JoinTaskExecutor.newBecomeMasterTask(), (source1, e) -> {
            });
            tasks.put(JoinTaskExecutor.newFinishElectionTask(), electionFinishedListener);
            //MasterService主要负责集群任务管理和运行，只有主节点会提交集群任务到内部队列，并运行队列中的任务
            // masterService.submitStateUpdateTasks属于模板模式：
            // 1、执行joinTaskExecutor.execute得到集群状态（新或旧）， masterService.submitStateUpdateTasks通过判断前后状态值是否变化，进而触发状态发布流程
            // 2、masterService.submitStateUpdateTasks内部并没有实现发布状态流程，而是委托ZenDiscovery.publish或Coordinator.publish实现二阶段发布集群状态
            masterService.submitStateUpdateTasks(source, tasks, ClusterStateTaskConfig.build(Priority.URGENT), joinTaskExecutor);
        }

        public synchronized void closeAndProcessPending(String reason) {
            innerClose();
            // 两处的getPendingAsTasks返回的并非执行集群任务线程池的等待队列，而是选主流程中等待加入集群的请求数量。
            Map<JoinTaskExecutor.Task, ClusterStateTaskListener> tasks = getPendingAsTasks(reason);
            final String source = "zen-disco-election-stop [" + reason + "]";
            tasks.put(JoinTaskExecutor.newFinishElectionTask(), electionFinishedListener);
            masterService.submitStateUpdateTasks(source, tasks, ClusterStateTaskConfig.build(Priority.URGENT), joinTaskExecutor);
        }

        private void innerClose() {
            if (closed.getAndSet(true)) {
                throw new AlreadyClosedException("election context is already closed");
            }
        }

        private void ensureOpen() {
            if (closed.get()) {
                throw new AlreadyClosedException("election context is already closed");
            }
        }

        private synchronized ElectionCallback getCallback() {
            return callback;
        }

        private void onElectedAsMaster(ClusterState state) {
            assert MasterService.assertMasterUpdateThread();
            assert state.nodes().isLocalNodeElectedMaster() : "onElectedAsMaster called but local node is not master";
            ElectionCallback callback = getCallback(); // get under lock
            if (callback != null) {
                callback.onElectedAsMaster(state);
            }
        }

        private void onFailure(Throwable t) {
            assert MasterService.assertMasterUpdateThread();
            ElectionCallback callback = getCallback(); // get under lock
            if (callback != null) {
                callback.onFailure(t);
            }
        }

        private final ClusterStateTaskListener electionFinishedListener = new ClusterStateTaskListener() {

            @Override
            public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                if (newState.nodes().isLocalNodeElectedMaster()) {
                    // 通知本地选自己而等待的线程：继续执行
                    // 什么时候回调electionContext.wrapperCallback? 答案是此处
                    ElectionContext.this.onElectedAsMaster(newState);
                } else {
                    onFailure(source, new NotMasterException("election stopped [" + source + "]"));
                }
            }

            @Override
            public void onFailure(String source, Exception e) {
                ElectionContext.this.onFailure(e);
            }
        };

    }

    static class JoinTaskListener implements ClusterStateTaskListener {
        final List<MembershipAction.JoinCallback> callbacks;
        private final Logger logger;

        JoinTaskListener(MembershipAction.JoinCallback callback, Logger logger) {
            this(Collections.singletonList(callback), logger);
        }

        JoinTaskListener(List<MembershipAction.JoinCallback> callbacks, Logger logger) {
            this.callbacks = callbacks;
            this.logger = logger;
        }

        @Override
        public void onFailure(String source, Exception e) {
            for (MembershipAction.JoinCallback callback : callbacks) {
                try {
                    callback.onFailure(e);
                } catch (Exception inner) {
                    logger.error(() -> new ParameterizedMessage("error handling task failure [{}]", e), inner);
                }
            }
        }

        @Override
        public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
            for (MembershipAction.JoinCallback callback : callbacks) {
                try {
                    callback.onSuccess();
                } catch (Exception e) {
                    logger.error(() -> new ParameterizedMessage("unexpected error during [{}]", source), e);
                }
            }
        }
    }

}
