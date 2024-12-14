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
import org.elasticsearch.common.SuppressForbidden;
import org.elasticsearch.common.lease.Releasable;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.AbstractRunnable;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.threadpool.ThreadPool.Names;

import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * It's provably impossible to guarantee that any leader election algorithm ever elects a leader, but they generally work (with probability
 * that approaches 1 over time) as long as elections occur sufficiently infrequently, compared to the time it takes to send a message to
 * another node and receive a response back. We do not know the round-trip latency here, but we can approximate it by attempting elections
 * randomly at reasonably high frequency and backing off (linearly) until one of them succeeds. We also place an upper bound on the backoff
 * so that if elections are failing due to a network partition that lasts for a long time then when the partition heals there is an election
 * attempt reasonably quickly.
 */
public class ElectionSchedulerFactory {

    private static final Logger logger = LogManager.getLogger(ElectionSchedulerFactory.class);

    private static final String ELECTION_INITIAL_TIMEOUT_SETTING_KEY = "cluster.election.initial_timeout";
    private static final String ELECTION_BACK_OFF_TIME_SETTING_KEY = "cluster.election.back_off_time";
    private static final String ELECTION_MAX_TIMEOUT_SETTING_KEY = "cluster.election.max_timeout";
    private static final String ELECTION_DURATION_SETTING_KEY = "cluster.election.duration";

    /*
     * The first election is scheduled to occur a random number of milliseconds after the scheduler is started, where the random number of
     * milliseconds is chosen uniformly from
     *
     *     (0, min(ELECTION_INITIAL_TIMEOUT_SETTING, ELECTION_MAX_TIMEOUT_SETTING)]
     *
     * For `n > 1`, the `n`th election is scheduled to occur a random number of milliseconds after the `n - 1`th election, where the random
     * number of milliseconds is chosen uniformly from
     *
     *     (0, min(ELECTION_INITIAL_TIMEOUT_SETTING + (n-1) * ELECTION_BACK_OFF_TIME_SETTING, ELECTION_MAX_TIMEOUT_SETTING)]
     *
     * Each election lasts up to ELECTION_DURATION_SETTING.
     */

    public static final Setting<TimeValue> ELECTION_INITIAL_TIMEOUT_SETTING = Setting.timeSetting(ELECTION_INITIAL_TIMEOUT_SETTING_KEY,
        TimeValue.timeValueMillis(100), TimeValue.timeValueMillis(1), TimeValue.timeValueSeconds(10), Property.NodeScope);

    public static final Setting<TimeValue> ELECTION_BACK_OFF_TIME_SETTING = Setting.timeSetting(ELECTION_BACK_OFF_TIME_SETTING_KEY,
        TimeValue.timeValueMillis(100), TimeValue.timeValueMillis(1), TimeValue.timeValueSeconds(60), Property.NodeScope);

    public static final Setting<TimeValue> ELECTION_MAX_TIMEOUT_SETTING = Setting.timeSetting(ELECTION_MAX_TIMEOUT_SETTING_KEY,
        TimeValue.timeValueSeconds(10), TimeValue.timeValueMillis(200), TimeValue.timeValueSeconds(300), Property.NodeScope);

    public static final Setting<TimeValue> ELECTION_DURATION_SETTING = Setting.timeSetting(ELECTION_DURATION_SETTING_KEY,
        TimeValue.timeValueMillis(500), TimeValue.timeValueMillis(1), TimeValue.timeValueSeconds(300), Property.NodeScope);

    private final TimeValue initialTimeout;
    private final TimeValue backoffTime;
    private final TimeValue maxTimeout;
    private final TimeValue duration;
    private final ThreadPool threadPool;
    private final Random random;

    public ElectionSchedulerFactory(Settings settings, Random random, ThreadPool threadPool) {
        this.random = random;
        this.threadPool = threadPool;
        // 初始延迟
        initialTimeout = ELECTION_INITIAL_TIMEOUT_SETTING.get(settings);
        // 延迟增量值，即每次递增值
        backoffTime = ELECTION_BACK_OFF_TIME_SETTING.get(settings);
        // 最大延迟，控制无限大
        maxTimeout = ELECTION_MAX_TIMEOUT_SETTING.get(settings);
        // 从第二次开始，起点值
        duration = ELECTION_DURATION_SETTING.get(settings);

        if (maxTimeout.millis() < initialTimeout.millis()) {
            throw new IllegalArgumentException(new ParameterizedMessage("[{}] is [{}], but must be at least [{}] which is [{}]",
                ELECTION_MAX_TIMEOUT_SETTING_KEY, maxTimeout, ELECTION_INITIAL_TIMEOUT_SETTING_KEY, initialTimeout).getFormattedMessage());
        }
    }

    /**
     * Start the process to schedule repeated election attempts.
     *
     * @param gracePeriod       An initial period to wait before attempting the first election.
     * @param scheduledRunnable The action to run each time an election should be attempted.
     */
    public Releasable startElectionScheduler(TimeValue gracePeriod, Runnable scheduledRunnable) {
        final ElectionScheduler scheduler = new ElectionScheduler();
        scheduler.scheduleNextElection(gracePeriod, scheduledRunnable);
        return scheduler;
    }

    @SuppressForbidden(reason = "Argument to Math.abs() is definitely not Long.MIN_VALUE")
    private static long nonNegative(long n) {
        return n == Long.MIN_VALUE ? 0 : Math.abs(n);
    }

    /**
     * @param randomNumber a randomly-chosen long
     * @param upperBound   inclusive upper bound
     * @return a number in the range (0, upperBound]
     */
    // package-private for testing
    static long toPositiveLongAtMost(long randomNumber, long upperBound) {
        assert 0 < upperBound : upperBound;
        return nonNegative(randomNumber) % upperBound + 1;
    }

    @Override
    public String toString() {
        return "ElectionSchedulerFactory{" +
            "initialTimeout=" + initialTimeout +
            ", backoffTime=" + backoffTime +
            ", maxTimeout=" + maxTimeout +
            '}';
    }

    private class ElectionScheduler implements Releasable {
        private final AtomicBoolean isClosed = new AtomicBoolean();
        // 尝试次数
        private final AtomicLong attempt = new AtomicLong();

        void scheduleNextElection(final TimeValue gracePeriod, final Runnable scheduledRunnable) {
            if (isClosed.get()) {
                logger.debug("{} not scheduling election", this);
                return;
            }
            //重试次数，每次加 1
            final long thisAttempt = attempt.getAndIncrement();
            // to overflow here would take over a million years of failed election attempts, so we won't worry about that:
            //最大延迟时间不超过cluster.election.max_timeout配置，每次递增cluster.election.back_off_time
            final long maxDelayMillis = Math.min(maxTimeout.millis(), initialTimeout.millis() + thisAttempt * backoffTime.millis());
            //执行延迟在cluster.election.duration基础上递增随机值，注：随机值并不是没有约束，其实是约束在：1 ~ maxDelayMillis之间的随机值
            final long delayMillis = toPositiveLongAtMost(random.nextLong(), maxDelayMillis) + gracePeriod.millis();

            // 延迟时间解读：
            // 1、初次延迟：sum(初始值initialTimeout + 每次固定递增值backoffTime + 随机值random)，不能大于最大延迟maxTimeout
            // 2、第二次之后，sum(初始值initialTimeout + 每次固定递增值backoffTime + 随机值random + duration)，不能大于最大延迟maxTimeout
            // 第二次并不是简单的在第一次的基础上递增，还加了一个固定值duration
            // 第二次起点比第一次大，到底解决什么问题？未知
            final Runnable runnable = new AbstractRunnable() {
                @Override
                public void onFailure(Exception e) {
                    logger.debug(new ParameterizedMessage("unexpected exception in wakeup of {}", this), e);
                    assert false : e;
                }

                @Override
                protected void doRun() {
                    if (isClosed.get()) {
                        logger.debug("{} not starting election", this);
                    } else {
                        logger.debug("{} starting election", this);
                        // 先设置下次多久后，再次执行此方法，即递归执行，直到成功或超时。
                        // 总不能死循环选举吧，如何手动关闭选举流程？貌似只能获取ElectionScheduler对象，然后调用close()
                        // 何时触发ElectionScheduler.close()? 当然是选举成功后：可看 Coordinator.becomeLeader()
                        scheduleNextElection(duration, scheduledRunnable);
                        // 真正的竞选流程
                        scheduledRunnable.run();
                    }
                }

                @Override
                public String toString() {
                    return "scheduleNextElection{gracePeriod=" + gracePeriod
                        + ", thisAttempt=" + thisAttempt
                        + ", maxDelayMillis=" + maxDelayMillis
                        + ", delayMillis=" + delayMillis
                        + ", " + ElectionScheduler.this + "}";
                }
            };

            logger.debug("scheduling {}", runnable);
            threadPool.scheduleUnlessShuttingDown(TimeValue.timeValueMillis(delayMillis), Names.GENERIC, runnable);
        }

        @Override
        public String toString() {
            return "ElectionScheduler{attempt=" + attempt
                + ", " + ElectionSchedulerFactory.this + "}";
        }

        @Override
        public void close() {
            boolean wasNotPreviouslyClosed = isClosed.compareAndSet(false, true);
            assert wasNotPreviouslyClosed;
        }
    }
}
