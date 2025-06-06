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

package org.elasticsearch.cluster.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.Assertions;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.cluster.AckedClusterStateTaskListener;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterState.Builder;
import org.elasticsearch.cluster.ClusterStateTaskConfig;
import org.elasticsearch.cluster.ClusterStateTaskExecutor;
import org.elasticsearch.cluster.ClusterStateTaskExecutor.ClusterTasksResult;
import org.elasticsearch.cluster.ClusterStateTaskListener;
import org.elasticsearch.cluster.coordination.ClusterStatePublisher;
import org.elasticsearch.cluster.coordination.FailedToCommitClusterStateException;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.metadata.ProcessClusterEventTimeoutException;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.CountDown;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.common.util.concurrent.EsRejectedExecutionException;
import org.elasticsearch.common.util.concurrent.FutureUtils;
import org.elasticsearch.common.util.concurrent.PrioritizedEsThreadPoolExecutor;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.discovery.Discovery;
import org.elasticsearch.node.Node;
import org.elasticsearch.threadpool.Scheduler;
import org.elasticsearch.threadpool.ThreadPool;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.elasticsearch.common.util.concurrent.EsExecutors.daemonThreadFactory;

/**
 *  MasterService 和ClusterApplierService 分别负责运行任务和应用任务产生的集群状态。
 *  只有主节点会执行这个类中的方法。也就是说，只有主节点会提交集群任务到内部的队列，并运行队列中的任务。.
 *  参考：https://cloud.tencent.com/developer/article/1860217
 */
public class MasterService extends AbstractLifecycleComponent {
    private static final Logger logger = LogManager.getLogger(MasterService.class);

    public static final Setting<TimeValue> MASTER_SERVICE_SLOW_TASK_LOGGING_THRESHOLD_SETTING =
        Setting.positiveTimeSetting("cluster.service.slow_master_task_logging_threshold", TimeValue.timeValueSeconds(10),
            Setting.Property.Dynamic, Setting.Property.NodeScope);

    static final String MASTER_UPDATE_THREAD_NAME = "masterService#updateTask";

    /**
     * 发布集群任务的模块
     */
    ClusterStatePublisher clusterStatePublisher;

    private final String nodeName;

    /**
     * 存储集群状态
     */
    private java.util.function.Supplier<ClusterState> clusterStateSupplier;
    /**
     * 集群任务慢执行的检测门限
     */
    private volatile TimeValue slowTaskLoggingThreshold;

    protected final ThreadPool threadPool;
    /**
     * 执行集群任务的线程池
     * 执行集群任务的线程池为单个线程的线程池。因此，集群任务被串行地执行。
     * 线程池类型为PrioritizedEsThreadPoolExecutor，支持优先级。其初始化在MasterService#doStart 方法中:
     */
    private volatile PrioritizedEsThreadPoolExecutor threadPoolExecutor;
    /**
     * 管理、执行提交的任务。通过submitStateUpdateTask 方法提交的任务最终调用内部类Batcher的提交方法
     */
    private volatile Batcher taskBatcher;

    public MasterService(Settings settings, ClusterSettings clusterSettings, ThreadPool threadPool) {
        this.nodeName = Objects.requireNonNull(Node.NODE_NAME_SETTING.get(settings));

        this.slowTaskLoggingThreshold = MASTER_SERVICE_SLOW_TASK_LOGGING_THRESHOLD_SETTING.get(settings);
        clusterSettings.addSettingsUpdateConsumer(MASTER_SERVICE_SLOW_TASK_LOGGING_THRESHOLD_SETTING, this::setSlowTaskLoggingThreshold);

        this.threadPool = threadPool;
    }

    private void setSlowTaskLoggingThreshold(TimeValue slowTaskLoggingThreshold) {
        this.slowTaskLoggingThreshold = slowTaskLoggingThreshold;
    }

    public synchronized void setClusterStatePublisher(ClusterStatePublisher publisher) {
        clusterStatePublisher = publisher;
    }

    public synchronized void setClusterStateSupplier(java.util.function.Supplier<ClusterState> clusterStateSupplier) {
        this.clusterStateSupplier = clusterStateSupplier;
    }

    @Override
    protected synchronized void doStart() {
        Objects.requireNonNull(clusterStatePublisher, "please set a cluster state publisher before starting");
        Objects.requireNonNull(clusterStateSupplier, "please set a cluster state supplier before starting");
        threadPoolExecutor = createThreadPoolExecutor();
        taskBatcher = new Batcher(logger, threadPoolExecutor);
    }

    protected PrioritizedEsThreadPoolExecutor createThreadPoolExecutor() {
        return EsExecutors.newSinglePrioritizing(
                nodeName + "/" + MASTER_UPDATE_THREAD_NAME,
                daemonThreadFactory(nodeName, MASTER_UPDATE_THREAD_NAME),
                threadPool.getThreadContext(),
                threadPool.scheduler());
    }

    @SuppressWarnings("unchecked")
    class Batcher extends TaskBatcher {

        Batcher(Logger logger, PrioritizedEsThreadPoolExecutor threadExecutor) {
            super(logger, threadExecutor);
        }

        @Override
        protected void onTimeout(List<? extends BatchedTask> tasks, TimeValue timeout) {
            threadPool.generic().execute(
                () -> tasks.forEach(
                    task -> ((UpdateTask) task).listener.onFailure(task.source,
                        new ProcessClusterEventTimeoutException(timeout, task.source))));
        }

        @Override
        protected void run(Object batchingKey, List<? extends BatchedTask> tasks, String tasksSummary) {
            ClusterStateTaskExecutor<Object> taskExecutor = (ClusterStateTaskExecutor<Object>) batchingKey;
            List<UpdateTask> updateTasks = (List<UpdateTask>) tasks;
            runTasks(new TaskInputs(taskExecutor, updateTasks, tasksSummary));
        }

        class UpdateTask extends BatchedTask {
            final ClusterStateTaskListener listener;

            UpdateTask(Priority priority, String source, Object task, ClusterStateTaskListener listener,
                       ClusterStateTaskExecutor<?> executor) {
                super(priority, source, executor, task);
                this.listener = listener;
            }

            @Override
            public String describeTasks(List<? extends BatchedTask> tasks) {
                return ((ClusterStateTaskExecutor<Object>) batchingKey).describeTasks(
                    tasks.stream().map(BatchedTask::getTask).collect(Collectors.toList()));
            }
        }
    }

    @Override
    protected synchronized void doStop() {
        ThreadPool.terminate(threadPoolExecutor, 10, TimeUnit.SECONDS);
    }

    @Override
    protected synchronized void doClose() {
    }

    /**
     * The current cluster state exposed by the discovery layer. Package-visible for tests.
     */
    ClusterState state() {
        return clusterStateSupplier.get();
    }

    private static boolean isMasterUpdateThread() {
        return Thread.currentThread().getName().contains(MASTER_UPDATE_THREAD_NAME);
    }

    public static boolean assertMasterUpdateThread() {
        assert isMasterUpdateThread() : "not called from the master service thread";
        return true;
    }

    public static boolean assertNotMasterUpdateThread(String reason) {
        assert isMasterUpdateThread() == false :
            "Expected current thread [" + Thread.currentThread() + "] to not be the master service thread. Reason: [" + reason + "]";
        return true;
    }

    private void runTasks(TaskInputs taskInputs) {
        final String summary = taskInputs.summary;
        if (!lifecycle.started()) {
            logger.debug("processing [{}]: ignoring, master service not started", summary);
            return;
        }

        logger.debug("executing cluster state update for [{}]", summary);
        // 上一次集群状态
        final ClusterState previousClusterState = state();
        // 保证只能在master上执行任务
        if (!previousClusterState.nodes().isLocalNodeElectedMaster() && taskInputs.runOnlyWhenMaster()) {
            logger.debug("failing [{}]: local node is no longer master", summary);
            taskInputs.onNoLongerMaster();
            return;
        }

        final long computationStartTime = threadPool.relativeTimeInMillis();
        // 执行集群task任务 可能生成 “新的集群状态”
        final TaskOutputs taskOutputs = calculateTaskOutputs(taskInputs, previousClusterState);
        taskOutputs.notifyFailedTasks();
        final TimeValue computationTime = getTimeSince(computationStartTime);
        logExecutionTime(computationTime, "compute cluster state update", summary);
        // 判断 “上一次集群状态” 与 “最新集群状态” 是否相等
        if (taskOutputs.clusterStateUnchanged()) {
            final long notificationStartTime = threadPool.relativeTimeInMillis();
            taskOutputs.notifySuccessfulTasksOnUnchangedClusterState();
            final TimeValue executionTime = getTimeSince(notificationStartTime);
            logExecutionTime(executionTime, "notify listeners on unchanged cluster state", summary);
        } else {
            // 发现：集群状态发生变化
            final ClusterState newClusterState = taskOutputs.newClusterState;
            if (logger.isTraceEnabled()) {
                logger.trace("cluster state updated, source [{}]\n{}", summary, newClusterState);
            } else {
                logger.debug("cluster state updated, version [{}], source [{}]", newClusterState.version(), summary);
            }
            final long publicationStartTime = threadPool.relativeTimeInMillis();
            try {
                ClusterChangedEvent clusterChangedEvent = new ClusterChangedEvent(summary, newClusterState, previousClusterState);
                // new cluster state, notify all listeners
                final DiscoveryNodes.Delta nodesDelta = clusterChangedEvent.nodesDelta();
                if (nodesDelta.hasChanges() && logger.isInfoEnabled()) {
                    String nodesDeltaSummary = nodesDelta.shortSummary();
                    if (nodesDeltaSummary.length() > 0) {
                        logger.info("{}, term: {}, version: {}, delta: {}",
                            summary, newClusterState.term(), newClusterState.version(), nodesDeltaSummary);
                    }
                }

                logger.debug("publishing cluster state version [{}]", newClusterState.version());
                // 一旦集群状态发生变化，则广播集群状态
                //发布集群状态是-一个分布式事务操作，分布式事务需要实现原子性:要么所有参与者都提交事务，要么都取消事务。
                // ES使用二段提交来实现分布式事务。二段提交可以避免失败回滚，其基本过程是:把信息发下去，但不应用，如果得到多数节点的确认，则再发-一个请求出去要求节点应用。
                //ES实现二段提交与标准二段提交有一些区别，发布集群状态到参与者的数量并非定义为全部，而是多数节点成功就算成功。多数的定义取决于配置项:
                //discovery.zen.minimum_master_nodes
                //两个阶段过程如下。
                //发布阶段：发布集群状态，等待响应。
                //提交阶段：收到的响应数量大于minimum master_ nodes 数量，发送commit请求。
                //参考：https://cloud.tencent.com/developer/article/1860217
                publish(clusterChangedEvent, taskOutputs, publicationStartTime);
            } catch (Exception e) {
                handleException(summary, publicationStartTime, newClusterState, e);
            }
        }
    }

    private TimeValue getTimeSince(long startTimeMillis) {
        return TimeValue.timeValueMillis(Math.max(0, threadPool.relativeTimeInMillis() - startTimeMillis));
    }

    protected void publish(ClusterChangedEvent clusterChangedEvent, TaskOutputs taskOutputs, long startTimeMillis) {
        final PlainActionFuture<Void> fut = new PlainActionFuture<Void>() {
            @Override
            protected boolean blockingAllowed() {
                return isMasterUpdateThread() || super.blockingAllowed();
            }
        };
        // ClusterStatePublisher是抽象类，具体类：ZenDiscovery（旧版ES用它） 和 Coordinator（新版ES用它，7.6默认）
        //先关注：ZenDiscovery
        // 在Node.start中 显式调用clusterService.getMasterService().setClusterStatePublisher配置集群状态发布器
        clusterStatePublisher.publish(clusterChangedEvent, fut, taskOutputs.createAckListener(threadPool, clusterChangedEvent.state()));

        // indefinitely wait for publication to complete
        try {
            FutureUtils.get(fut);
            // 唤醒选举流程，继续执行
            onPublicationSuccess(clusterChangedEvent, taskOutputs);
        } catch (Exception e) {
            // 唤醒选举流程，并告知执行异常，让其执行异常处理: 再次发起选举
            onPublicationFailed(clusterChangedEvent, taskOutputs, startTimeMillis, e);
        }
    }

    void onPublicationSuccess(ClusterChangedEvent clusterChangedEvent, TaskOutputs taskOutputs) {
        final long notificationStartTime = threadPool.relativeTimeInMillis();
        taskOutputs.processedDifferentClusterState(clusterChangedEvent.previousState(), clusterChangedEvent.state());

        try {
            taskOutputs.clusterStatePublished(clusterChangedEvent);
        } catch (Exception e) {
            logger.error(() -> new ParameterizedMessage(
                "exception thrown while notifying executor of new cluster state publication [{}]",
                clusterChangedEvent.source()), e);
        }
        final TimeValue executionTime = getTimeSince(notificationStartTime);
        logExecutionTime(executionTime, "notify listeners on successful publication of cluster state (version: "
            + clusterChangedEvent.state().version() + ", uuid: " + clusterChangedEvent.state().stateUUID() + ')',
            clusterChangedEvent.source());
    }

    void onPublicationFailed(ClusterChangedEvent clusterChangedEvent, TaskOutputs taskOutputs, long startTimeMillis, Exception exception) {
        if (exception instanceof FailedToCommitClusterStateException) {
            final long version = clusterChangedEvent.state().version();
            logger.warn(() -> new ParameterizedMessage(
                "failing [{}]: failed to commit cluster state version [{}]", clusterChangedEvent.source(), version), exception);
            taskOutputs.publishingFailed((FailedToCommitClusterStateException) exception);
        } else {
            handleException(clusterChangedEvent.source(), startTimeMillis, clusterChangedEvent.state(), exception);
        }
    }

    private void handleException(String summary, long startTimeMillis, ClusterState newClusterState, Exception e) {
        final TimeValue executionTime = getTimeSince(startTimeMillis);
        final long version = newClusterState.version();
        final String stateUUID = newClusterState.stateUUID();
        final String fullState = newClusterState.toString();
        logger.warn(new ParameterizedMessage(
                "took [{}] and then failed to publish updated cluster state (version: {}, uuid: {}) for [{}]:\n{}",
                executionTime,
                version,
                stateUUID,
                summary,
                fullState),
            e);
        // TODO: do we want to call updateTask.onFailure here?
    }

    private TaskOutputs calculateTaskOutputs(TaskInputs taskInputs, ClusterState previousClusterState) {
        ClusterTasksResult<Object> clusterTasksResult = executeTasks(taskInputs, previousClusterState);
        ClusterState newClusterState = patchVersions(previousClusterState, clusterTasksResult);
        return new TaskOutputs(taskInputs, previousClusterState, newClusterState, getNonFailedTasks(taskInputs, clusterTasksResult),
            clusterTasksResult.executionResults);
    }

    private ClusterState patchVersions(ClusterState previousClusterState, ClusterTasksResult<?> executionResult) {
        ClusterState newClusterState = executionResult.resultingState;

        if (previousClusterState != newClusterState) {
            // only the master controls the version numbers
            Builder builder = incrementVersion(newClusterState);
            if (previousClusterState.routingTable() != newClusterState.routingTable()) {
                builder.routingTable(RoutingTable.builder(newClusterState.routingTable())
                    .version(newClusterState.routingTable().version() + 1).build());
            }
            if (previousClusterState.metaData() != newClusterState.metaData()) {
                builder.metaData(MetaData.builder(newClusterState.metaData()).version(newClusterState.metaData().version() + 1));
            }

            newClusterState = builder.build();
        }

        return newClusterState;
    }

    public Builder incrementVersion(ClusterState clusterState) {
        return ClusterState.builder(clusterState).incrementVersion();
    }

    /**
     * Submits a cluster state update task; unlike {@link #submitStateUpdateTask(String, Object, ClusterStateTaskConfig,
     * ClusterStateTaskExecutor, ClusterStateTaskListener)}, submitted updates will not be batched.
     *
     * @param source     the source of the cluster state update task
     * @param updateTask the full context for the cluster state update
     *                   task
     *
     */
    public <T extends ClusterStateTaskConfig & ClusterStateTaskExecutor<T> & ClusterStateTaskListener>
        void submitStateUpdateTask(
        String source, T updateTask) {
        submitStateUpdateTask(source, updateTask, updateTask, updateTask, updateTask);
    }

    /**
     * 提交集群任务
     * Submits a cluster state update task; submitted updates will be
     * batched across the same instance of executor. The exact batching
     * semantics depend on the underlying implementation but a rough
     * guideline is that if the update task is submitted while there
     * are pending update tasks for the same executor, these update
     * tasks will all be executed on the executor in a single batch
     *
     * @param source   the source of the cluster state update task
     * @param task     the state needed for the cluster state update task
     * @param config   the cluster state update task configuration
     * @param executor the cluster state update task executor; tasks
     *                 that share the same executor will be executed
     *                 batches on this executor
     * @param listener callback after the cluster state update task
     *                 completes
     * @param <T>      the type of the cluster state update task state
     *
     */
    public <T> void submitStateUpdateTask(String source, T task,
                                          ClusterStateTaskConfig config,
                                          ClusterStateTaskExecutor<T> executor,
                                          ClusterStateTaskListener listener) {
        submitStateUpdateTasks(source, Collections.singletonMap(task, listener), config, executor);
    }

    /**
     * Output created by executing a set of tasks provided as TaskInputs
     */
    class TaskOutputs {
        final TaskInputs taskInputs;
        final ClusterState previousClusterState;
        final ClusterState newClusterState;
        final List<Batcher.UpdateTask> nonFailedTasks;
        final Map<Object, ClusterStateTaskExecutor.TaskResult> executionResults;

        TaskOutputs(TaskInputs taskInputs, ClusterState previousClusterState,
                           ClusterState newClusterState,
                           List<Batcher.UpdateTask> nonFailedTasks,
                           Map<Object, ClusterStateTaskExecutor.TaskResult> executionResults) {
            this.taskInputs = taskInputs;
            this.previousClusterState = previousClusterState;
            this.newClusterState = newClusterState;
            this.nonFailedTasks = nonFailedTasks;
            this.executionResults = executionResults;
        }

        void publishingFailed(FailedToCommitClusterStateException t) {
            nonFailedTasks.forEach(task -> task.listener.onFailure(task.source(), t));
        }

        void processedDifferentClusterState(ClusterState previousClusterState, ClusterState newClusterState) {
            nonFailedTasks.forEach(task -> task.listener.clusterStateProcessed(task.source(), previousClusterState, newClusterState));
        }

        void clusterStatePublished(ClusterChangedEvent clusterChangedEvent) {
            taskInputs.executor.clusterStatePublished(clusterChangedEvent);
        }

        Discovery.AckListener createAckListener(ThreadPool threadPool, ClusterState newClusterState) {
            return new DelegatingAckListener(nonFailedTasks.stream()
                .filter(task -> task.listener instanceof AckedClusterStateTaskListener)
                .map(task -> new AckCountDownListener((AckedClusterStateTaskListener) task.listener, newClusterState.version(),
                    newClusterState.nodes(), threadPool))
                .collect(Collectors.toList()));
        }

        boolean clusterStateUnchanged() {
            return previousClusterState == newClusterState;
        }

        void notifyFailedTasks() {
            // fail all tasks that have failed
            for (Batcher.UpdateTask updateTask : taskInputs.updateTasks) {
                assert executionResults.containsKey(updateTask.task) : "missing " + updateTask;
                final ClusterStateTaskExecutor.TaskResult taskResult = executionResults.get(updateTask.task);
                if (taskResult.isSuccess() == false) {
                    updateTask.listener.onFailure(updateTask.source(), taskResult.getFailure());
                }
            }
        }

        void notifySuccessfulTasksOnUnchangedClusterState() {
            nonFailedTasks.forEach(task -> {
                if (task.listener instanceof AckedClusterStateTaskListener) {
                    //no need to wait for ack if nothing changed, the update can be counted as acknowledged
                    ((AckedClusterStateTaskListener) task.listener).onAllNodesAcked(null);
                }
                task.listener.clusterStateProcessed(task.source(), newClusterState, newClusterState);
            });
        }
    }

    /**
     * Returns the tasks that are pending.
     * 返回待执行的任务列表
     */
    public List<PendingClusterTask> pendingTasks() {
        return Arrays.stream(threadPoolExecutor.getPending()).map(pending -> {
            assert pending.task instanceof SourcePrioritizedRunnable :
                "thread pool executor should only use SourcePrioritizedRunnable instances but found: " + pending.task.getClass().getName();
            SourcePrioritizedRunnable task = (SourcePrioritizedRunnable) pending.task;
            return new PendingClusterTask(pending.insertionOrder, pending.priority, new Text(task.source()),
                task.getAgeInMillis(), pending.executing);
        }).collect(Collectors.toList());
    }

    /**
     * Returns the number of currently pending tasks.
     * 返回待执行的任务数量
     */
    public int numberOfPendingTasks() {
        return threadPoolExecutor.getNumberOfPendingTasks();
    }

    /**
     * Returns the maximum wait time for tasks in the queue
     *
     * @return A zero time value if the queue is empty, otherwise the time value oldest task waiting in the queue
     */
    public TimeValue getMaxTaskWaitTime() {
        return threadPoolExecutor.getMaxTaskWaitTime();
    }

    private SafeClusterStateTaskListener safe(ClusterStateTaskListener listener, Supplier<ThreadContext.StoredContext> contextSupplier) {
        if (listener instanceof AckedClusterStateTaskListener) {
            return new SafeAckedClusterStateTaskListener((AckedClusterStateTaskListener) listener, contextSupplier, logger);
        } else {
            return new SafeClusterStateTaskListener(listener, contextSupplier, logger);
        }
    }

    private static class SafeClusterStateTaskListener implements ClusterStateTaskListener {
        private final ClusterStateTaskListener listener;
        protected final Supplier<ThreadContext.StoredContext> context;
        private final Logger logger;

        SafeClusterStateTaskListener(ClusterStateTaskListener listener, Supplier<ThreadContext.StoredContext> context, Logger logger) {
            this.listener = listener;
            this.context = context;
            this.logger = logger;
        }

        @Override
        public void onFailure(String source, Exception e) {
            try (ThreadContext.StoredContext ignore = context.get()) {
                listener.onFailure(source, e);
            } catch (Exception inner) {
                inner.addSuppressed(e);
                logger.error(() -> new ParameterizedMessage(
                        "exception thrown by listener notifying of failure from [{}]", source), inner);
            }
        }

        @Override
        public void onNoLongerMaster(String source) {
            try (ThreadContext.StoredContext ignore = context.get()) {
                listener.onNoLongerMaster(source);
            } catch (Exception e) {
                logger.error(() -> new ParameterizedMessage(
                        "exception thrown by listener while notifying no longer master from [{}]", source), e);
            }
        }

        @Override
        public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
            try (ThreadContext.StoredContext ignore = context.get()) {
                listener.clusterStateProcessed(source, oldState, newState);
            } catch (Exception e) {
                logger.error(() -> new ParameterizedMessage(
                        "exception thrown by listener while notifying of cluster state processed from [{}], old cluster state:\n" +
                            "{}\nnew cluster state:\n{}", source, oldState, newState), e);
            }
        }
    }

    private static class SafeAckedClusterStateTaskListener extends SafeClusterStateTaskListener implements AckedClusterStateTaskListener {
        private final AckedClusterStateTaskListener listener;
        private final Logger logger;

        SafeAckedClusterStateTaskListener(AckedClusterStateTaskListener listener, Supplier<ThreadContext.StoredContext> context,
                                          Logger logger) {
            super(listener, context, logger);
            this.listener = listener;
            this.logger = logger;
        }

        @Override
        public boolean mustAck(DiscoveryNode discoveryNode) {
            return listener.mustAck(discoveryNode);
        }

        @Override
        public void onAllNodesAcked(@Nullable Exception e) {
            try (ThreadContext.StoredContext ignore = context.get()) {
                listener.onAllNodesAcked(e);
            } catch (Exception inner) {
                inner.addSuppressed(e);
                logger.error("exception thrown by listener while notifying on all nodes acked", inner);
            }
        }

        @Override
        public void onAckTimeout() {
            try (ThreadContext.StoredContext ignore = context.get()) {
                listener.onAckTimeout();
            } catch (Exception e) {
                logger.error("exception thrown by listener while notifying on ack timeout", e);
            }
        }

        @Override
        public TimeValue ackTimeout() {
            return listener.ackTimeout();
        }
    }

    private void logExecutionTime(TimeValue executionTime, String activity, String summary) {
        if (executionTime.getMillis() > slowTaskLoggingThreshold.getMillis()) {
            logger.warn("took [{}], which is over [{}], to {} for [{}]", executionTime, slowTaskLoggingThreshold, activity, summary);
        } else {
            logger.debug("took [{}] to {} for [{}]", executionTime, activity, summary);
        }
    }

    private static class DelegatingAckListener implements Discovery.AckListener {

        private final List<Discovery.AckListener> listeners;

        private DelegatingAckListener(List<Discovery.AckListener> listeners) {
            this.listeners = listeners;
        }

        @Override
        public void onCommit(TimeValue commitTime) {
            for (Discovery.AckListener listener : listeners) {
                listener.onCommit(commitTime);
            }
        }

        @Override
        public void onNodeAck(DiscoveryNode node, @Nullable Exception e) {
            for (Discovery.AckListener listener : listeners) {
                listener.onNodeAck(node, e);
            }
        }
    }

    private static class AckCountDownListener implements Discovery.AckListener {

        private static final Logger logger = LogManager.getLogger(AckCountDownListener.class);

        private final AckedClusterStateTaskListener ackedTaskListener;
        private final CountDown countDown;
        private final DiscoveryNode masterNode;
        private final ThreadPool threadPool;
        private final long clusterStateVersion;
        private volatile Scheduler.Cancellable ackTimeoutCallback;
        private Exception lastFailure;

        AckCountDownListener(AckedClusterStateTaskListener ackedTaskListener, long clusterStateVersion, DiscoveryNodes nodes,
                             ThreadPool threadPool) {
            this.ackedTaskListener = ackedTaskListener;
            this.clusterStateVersion = clusterStateVersion;
            this.threadPool = threadPool;
            this.masterNode = nodes.getMasterNode();
            int countDown = 0;
            for (DiscoveryNode node : nodes) {
                //we always wait for at least the master node
                if (node.equals(masterNode) || ackedTaskListener.mustAck(node)) {
                    countDown++;
                }
            }
            logger.trace("expecting {} acknowledgements for cluster_state update (version: {})", countDown, clusterStateVersion);
            this.countDown = new CountDown(countDown + 1); // we also wait for onCommit to be called
        }

        @Override
        public void onCommit(TimeValue commitTime) {
            TimeValue ackTimeout = ackedTaskListener.ackTimeout();
            if (ackTimeout == null) {
                ackTimeout = TimeValue.ZERO;
            }
            final TimeValue timeLeft = TimeValue.timeValueNanos(Math.max(0, ackTimeout.nanos() - commitTime.nanos()));
            if (timeLeft.nanos() == 0L) {
                onTimeout();
            } else if (countDown.countDown()) {
                finish();
            } else {
                this.ackTimeoutCallback = threadPool.schedule(this::onTimeout, timeLeft, ThreadPool.Names.GENERIC);
                // re-check if onNodeAck has not completed while we were scheduling the timeout
                if (countDown.isCountedDown()) {
                    ackTimeoutCallback.cancel();
                }
            }
        }

        @Override
        public void onNodeAck(DiscoveryNode node, @Nullable Exception e) {
            if (node.equals(masterNode) == false && ackedTaskListener.mustAck(node) == false) {
                return;
            }
            if (e == null) {
                logger.trace("ack received from node [{}], cluster_state update (version: {})", node, clusterStateVersion);
            } else {
                this.lastFailure = e;
                logger.debug(() -> new ParameterizedMessage(
                        "ack received from node [{}], cluster_state update (version: {})", node, clusterStateVersion), e);
            }

            if (countDown.countDown()) {
                finish();
            }
        }

        private void finish() {
            logger.trace("all expected nodes acknowledged cluster_state update (version: {})", clusterStateVersion);
            if (ackTimeoutCallback != null) {
                ackTimeoutCallback.cancel();
            }
            ackedTaskListener.onAllNodesAcked(lastFailure);
        }

        public void onTimeout() {
            if (countDown.fastForward()) {
                logger.trace("timeout waiting for acknowledgement for cluster_state update (version: {})", clusterStateVersion);
                ackedTaskListener.onAckTimeout();
            }
        }
    }

    private ClusterTasksResult<Object> executeTasks(TaskInputs taskInputs, ClusterState previousClusterState) {
        ClusterTasksResult<Object> clusterTasksResult;
        try {
            List<Object> inputs = taskInputs.updateTasks.stream().map(tUpdateTask -> tUpdateTask.task).collect(Collectors.toList());
            // 执行Task，并不是执行Task的running方法；而是执行JoinTaskExecutor.execute
            clusterTasksResult = taskInputs.executor.execute(previousClusterState, inputs);
            if (previousClusterState != clusterTasksResult.resultingState &&
                previousClusterState.nodes().isLocalNodeElectedMaster() &&
                (clusterTasksResult.resultingState.nodes().isLocalNodeElectedMaster() == false)) {
                throw new AssertionError("update task submitted to MasterService cannot remove master");
            }
        } catch (Exception e) {
            logger.trace(() -> new ParameterizedMessage(
                    "failed to execute cluster state update (on version: [{}], uuid: [{}]) for [{}]\n{}{}{}",
                    previousClusterState.version(),
                    previousClusterState.stateUUID(),
                    taskInputs.summary,
                    previousClusterState.nodes(),
                    previousClusterState.routingTable(),
                    previousClusterState.getRoutingNodes()), // may be expensive => construct message lazily
                e);
            clusterTasksResult = ClusterTasksResult.builder()
                .failures(taskInputs.updateTasks.stream().map(updateTask -> updateTask.task)::iterator, e)
                .build(previousClusterState);
        }

        assert clusterTasksResult.executionResults != null;
        assert clusterTasksResult.executionResults.size() == taskInputs.updateTasks.size()
            : String.format(Locale.ROOT, "expected [%d] task result%s but was [%d]", taskInputs.updateTasks.size(),
            taskInputs.updateTasks.size() == 1 ? "" : "s", clusterTasksResult.executionResults.size());
        if (Assertions.ENABLED) {
            ClusterTasksResult<Object> finalClusterTasksResult = clusterTasksResult;
            taskInputs.updateTasks.forEach(updateTask -> {
                assert finalClusterTasksResult.executionResults.containsKey(updateTask.task) :
                    "missing task result for " + updateTask;
            });
        }

        return clusterTasksResult;
    }

    private List<Batcher.UpdateTask> getNonFailedTasks(TaskInputs taskInputs,
                                                      ClusterTasksResult<Object> clusterTasksResult) {
        return taskInputs.updateTasks.stream().filter(updateTask -> {
            assert clusterTasksResult.executionResults.containsKey(updateTask.task) : "missing " + updateTask;
            final ClusterStateTaskExecutor.TaskResult taskResult =
                clusterTasksResult.executionResults.get(updateTask.task);
            return taskResult.isSuccess();
        }).collect(Collectors.toList());
    }

    /**
     * Represents a set of tasks to be processed together with their executor
     */
    private class TaskInputs {
        final String summary;
        final List<Batcher.UpdateTask> updateTasks;
        final ClusterStateTaskExecutor<Object> executor;

        TaskInputs(ClusterStateTaskExecutor<Object> executor, List<Batcher.UpdateTask> updateTasks, String summary) {
            this.summary = summary;
            this.executor = executor;
            this.updateTasks = updateTasks;
        }

        boolean runOnlyWhenMaster() {
            return executor.runOnlyOnMaster();
        }

        void onNoLongerMaster() {
            updateTasks.forEach(task -> task.listener.onNoLongerMaster(task.source()));
        }
    }

    /**
     * Submits a batch of cluster state update tasks; submitted updates are guaranteed to be processed together,
     * potentially with more tasks of the same executor.
     *
     * @param source   the source of the cluster state update task
     * @param tasks    a map of update tasks and their corresponding listeners
     * @param config   the cluster state update task configuration
     * @param executor the cluster state update task executor; tasks
     *                 that share the same executor will be executed
     *                 batches on this executor
     * @param <T>      the type of the cluster state update task state
     * 无论提交单个任务，还是提交多个任务，submitStateUpdateTask 最终通过TaskBatcher#submitTasks来提交任务。
     *           TaskBatcher#submitTasks其核心工作就是将提交的任务交给线程池执行。但是在此基础上实现了部分场景下的任务去重。

     * 去重的原理是：拥有相同ClusterStateTaskExecutor对象实例的任务只执行一个。
     * 去重在实现时并没有在线程池队列的列表上操作，而是将任务列表添加到-一个独立于线程池任务队列之外的HashMap中：tasksPerBatchingKey。
     * 这个HashMap保存的也是待执行的任务。与线程池任务队列不同的是，当提交多个任务时，线程池队列中只保存第一个，而tasksPerBatchingKey以ClusterState TaskExecutor为Key, Value 是提交的整个任务列表。当从线程池任务队列中取出任务准备执行时,先根据任务的ClusterStateTaskExecutor从tasksPerBatchingKey中找到它的任务列表，然后批量执行这个任务列表。所谓批量执行实际上只执行一个，然后对其他任务赋予相同的执行结果。

     * 换句话说，区分重复任务的方式是通过定义的任务本身实现的，即定义具有相同的ClusterStateTaskExecutor对象。
     * 去重的方式并非将重复的任务从列表中删除，而是在执行完任务后赋予重复任务相同的执行结果。

     * 去重的时机有两方面：
     * 提交的任务列表本身的去重。
     * 提交的任务在任务队列tasksPerBatchingKey中已存在，也就是存在尚未执行的相同任务，此时新提交的任务被追加到tasksPerBatchingKey某个k对应的v中。

     * 提交的任务列表本身任务数量大于1个的情况如上一小节所述,只在选主的流程中。
     * 那么，拥有相同ClusterStateTaskExecutor对象的是什么情况?一般情况下，各模块调用clusterService.submitStateUpdateTask提交任务，如果在任务执行之前提交多次，则不认为这些是重复的任务。因为一般每次提交会创建不同的ClusterStateUpdateTask对象，因此也拥有不同的ClusterStateTaskExecutor实例。例如，提交两次相同的创建索引请求，两次请求都会被加入任务队列，然后执行2次。
     * 只有模块在提交任务时明确使用了相同的ClusterStateTaskExecutor对象，才可能会在执行时去重。
     * 例如，每次提交shard-started任务时使用创建好的ClusterStateTaskExecutor，因此是同一个ClusterState' TaskExecutor对象实例。
     * 参考：https://cloud.tencent.com/developer/article/1860217
     */
    public <T> void submitStateUpdateTasks(final String source,
                                           final Map<T, ClusterStateTaskListener> tasks, final ClusterStateTaskConfig config,
                                           final ClusterStateTaskExecutor<T> executor) {
        // 集群任务执行完毕，可能会产生新的集群状态。如果产生新的集群状态，则主节点会把它广播到其他节点。
        if (!lifecycle.started()) {
            return;
        }
        final ThreadContext threadContext = threadPool.getThreadContext();
        final Supplier<ThreadContext.StoredContext> supplier = threadContext.newRestorableContext(true);
        try (ThreadContext.StoredContext ignore = threadContext.stashContext()) {
            threadContext.markAsSystemContext();

            List<Batcher.UpdateTask> safeTasks = tasks.entrySet().stream()
                .map(e -> taskBatcher.new UpdateTask(config.priority(), source, e.getKey(), safe(e.getValue(), supplier), executor))
                .collect(Collectors.toList());
            //submitTasks的核心工作就是将待执行任务加入任务队列
            taskBatcher.submitTasks(safeTasks, config.timeout());
        } catch (EsRejectedExecutionException e) {
            // ignore cases where we are shutting down..., there is really nothing interesting
            // to be done here...
            if (!lifecycle.stoppedOrClosed()) {
                throw e;
            }
        }
    }

}
