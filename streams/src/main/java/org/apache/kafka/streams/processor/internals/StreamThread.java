/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.processor.internals;

import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.NoOffsetForPartitionException;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigDef.Type;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.metrics.stats.Avg;
import org.apache.kafka.common.metrics.stats.Count;
import org.apache.kafka.common.metrics.stats.Max;
import org.apache.kafka.common.metrics.stats.Rate;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.streams.KafkaClientSupplier;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.errors.LockException;
import org.apache.kafka.streams.errors.StreamsException;
import org.apache.kafka.streams.errors.TaskIdFormatException;
import org.apache.kafka.streams.processor.PartitionGrouper;
import org.apache.kafka.streams.processor.TaskId;
import org.apache.kafka.streams.processor.TopologyBuilder;
import org.apache.kafka.streams.state.HostInfo;
import org.apache.kafka.streams.state.internals.ThreadCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static java.util.Collections.singleton;

public class StreamThread extends Thread {

    private static final Logger log = LoggerFactory.getLogger(StreamThread.class);
    private static final AtomicInteger STREAM_THREAD_ID_SEQUENCE = new AtomicInteger(1);
    /**
     * Stream thread states are the possible states that a stream thread can be in.
     * A thread must only be in one state at a time
     * The expected state transitions with the following defined states is:
     *
     * <pre>
     *                +-------------+
     *                | Not Running | <-------+
     *                +-----+-------+         |
     *                      |                 |
     *                      v                 |
     *                +-----+-------+         |
     *          +<--- | Running     | <----+  |
     *          |     +-----+-------+      |  |
     *          |           |              |  |
     *          |           v              |  |
     *          |     +-----+-------+      |  |
     *          +<--- | Partitions  |      |  |
     *          |     | Revoked     |      |  |
     *          |     +-----+-------+      |  |
     *          |           |              |  |
     *          |           v              |  |
     *          |     +-----+-------+      |  |
     *          |     | Assigning   |      |  |
     *          |     | Partitions  | ---->+  |
     *          |     +-----+-------+         |
     *          |           |                 |
     *          |           v                 |
     *          |     +-----+-------+         |
     *          +---> | Pending     | ------->+
     *                | Shutdown    |
     *                +-------------+
     * </pre>
     */
    public enum State {
        NOT_RUNNING(1), RUNNING(1, 2, 4), PARTITIONS_REVOKED(3, 4), ASSIGNING_PARTITIONS(1, 4), PENDING_SHUTDOWN(0);

        private final Set<Integer> validTransitions = new HashSet<>();

        State(final Integer... validTransitions) {
            this.validTransitions.addAll(Arrays.asList(validTransitions));
        }

        public boolean isRunning() {
            return !this.equals(PENDING_SHUTDOWN) && !this.equals(NOT_RUNNING);
        }

        public boolean isValidTransition(final State newState) {
            return validTransitions.contains(newState.ordinal());
        }
    }

    private volatile State state = State.NOT_RUNNING;
    private StateListener stateListener = null;

    /**
     * Listen to state change events
     */
    public interface StateListener {

        /**
         * Called when state changes
         * @param thread       thread changing state
         * @param newState     current state
         * @param oldState     previous state
         */
        void onChange(final StreamThread thread, final State newState, final State oldState);
    }

    /**
     * Set the {@link StateListener} to be notified when state changes. Note this API is internal to
     * Kafka Streams and is not intended to be used by an external application.
     */
    public void setStateListener(final StateListener listener) {
        this.stateListener = listener;
    }

    /**
     * @return The state this instance is in
     */
    public synchronized State state() {
        return state;
    }

    private synchronized void setState(State newState) {
        State oldState = state;
        if (!state.isValidTransition(newState)) {
            log.warn("Unexpected state transition from {} to {}.", logPrefix, oldState, newState);
        } else {
            log.info("{} State transition from {} to {}.", logPrefix, oldState, newState);
        }

        state = newState;
        if (stateListener != null) {
            stateListener.onChange(this, state, oldState);
        }
    }

    private synchronized void setStateWhenNotInPendingShutdown(final State newState) {
        if (state == State.PENDING_SHUTDOWN) {
            return;
        }
        setState(newState);
    }

    public final PartitionGrouper partitionGrouper;
    private final StreamsMetadataState streamsMetadataState;
    public final String applicationId;
    public final String clientId;
    public final UUID processId;

    protected final StreamsConfig config;
    protected final TopologyBuilder builder;
    protected final Producer<byte[], byte[]> producer;
    protected final Consumer<byte[], byte[]> consumer;
    protected final Consumer<byte[], byte[]> restoreConsumer;

    private final String logPrefix;
    private final String threadClientId;
    private final Pattern sourceTopicPattern;
    private final Map<TaskId, StreamTask> activeTasks;
    private final Map<TaskId, StandbyTask> standbyTasks;
    private final Map<TopicPartition, StreamTask> activeTasksByPartition;
    private final Map<TopicPartition, StandbyTask> standbyTasksByPartition;
    private final Set<TaskId> prevActiveTasks;
    private final Map<TaskId, StreamTask> suspendedTasks;
    private final Map<TaskId, StandbyTask> suspendedStandbyTasks;
    private final Time time;
    private final int rebalanceTimeoutMs;
    private final long pollTimeMs;
    private final long cleanTimeMs;
    private final long commitTimeMs;
    private final StreamsMetricsThreadImpl streamsMetrics;
    // TODO: this is not private only for tests, should be better refactored
    final StateDirectory stateDirectory;
    private String originalReset;
    private StreamPartitionAssignor partitionAssignor = null;
    private boolean cleanRun = false;
    private long timerStartedMs;
    private long lastCleanMs;
    private long lastCommitMs;
    private Throwable rebalanceException = null;

    private Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> standbyRecords;
    private boolean processStandbyRecords = false;

    private ThreadCache cache;
    private StoreChangelogReader storeChangelogReader;

    private final TaskCreator taskCreator = new TaskCreator();

    final ConsumerRebalanceListener rebalanceListener;
    private final static int UNLIMITED_RECORDS = -1;

    public synchronized boolean isInitialized() {
        return state == State.RUNNING;
    }

    public String threadClientId() {
        return threadClientId;
    }

    public StreamThread(TopologyBuilder builder,
                        StreamsConfig config,
                        KafkaClientSupplier clientSupplier,
                        String applicationId,
                        String clientId,
                        UUID processId,
                        Metrics metrics,
                        Time time,
                        StreamsMetadataState streamsMetadataState,
                        final long cacheSizeBytes) {
        super(clientId + "-StreamThread-" + STREAM_THREAD_ID_SEQUENCE.getAndIncrement());
        this.applicationId = applicationId;
        this.config = config;
        this.builder = builder;
        this.sourceTopicPattern = builder.sourceTopicPattern();
        this.clientId = clientId;
        this.processId = processId;
        this.partitionGrouper = config.getConfiguredInstance(StreamsConfig.PARTITION_GROUPER_CLASS_CONFIG, PartitionGrouper.class);
        this.streamsMetadataState = streamsMetadataState;
        threadClientId = getName();
        this.streamsMetrics = new StreamsMetricsThreadImpl(metrics, "stream-metrics", "thread." + threadClientId,
            Collections.singletonMap("client-id", threadClientId));
        if (config.getLong(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG) < 0) {
            log.warn("Negative cache size passed in thread [{}]. Reverting to cache size of 0 bytes.", threadClientId);
        }
        this.cache = new ThreadCache(threadClientId, cacheSizeBytes, this.streamsMetrics);

        this.logPrefix = String.format("stream-thread [%s]", threadClientId);

        // set the producer and consumer clients
        log.info("{} Creating producer client", logPrefix);
        this.producer = clientSupplier.getProducer(config.getProducerConfigs(threadClientId));
        log.info("{} Creating consumer client", logPrefix);

        Map<String, Object> consumerConfigs = config.getConsumerConfigs(this, applicationId, threadClientId);

        if (!builder.latestResetTopicsPattern().pattern().equals("") || !builder.earliestResetTopicsPattern().pattern().equals("")) {
            originalReset = (String) consumerConfigs.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG);
            log.info("{} Custom offset resets specified updating configs original auto offset reset {}", logPrefix, originalReset);
            consumerConfigs.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
        }

        this.consumer = clientSupplier.getConsumer(consumerConfigs);
        log.info("{} Creating restore consumer client", logPrefix);
        this.restoreConsumer = clientSupplier.getRestoreConsumer(config.getRestoreConsumerConfigs(threadClientId));
        // initialize the task list
        // activeTasks needs to be concurrent as it can be accessed
        // by QueryableState
        this.activeTasks = new ConcurrentHashMap<>();
        this.standbyTasks = new HashMap<>();
        this.activeTasksByPartition = new HashMap<>();
        this.standbyTasksByPartition = new HashMap<>();
        this.prevActiveTasks = new HashSet<>();
        this.suspendedTasks = new HashMap<>();
        this.suspendedStandbyTasks = new HashMap<>();

        // standby ktables
        this.standbyRecords = new HashMap<>();

        this.stateDirectory = new StateDirectory(applicationId, threadClientId, config.getString(StreamsConfig.STATE_DIR_CONFIG), time);
        final Object maxPollInterval = consumerConfigs.get(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG);
        this.rebalanceTimeoutMs =  (Integer) ConfigDef.parseType(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, maxPollInterval, Type.INT);
        this.pollTimeMs = config.getLong(StreamsConfig.POLL_MS_CONFIG);
        this.commitTimeMs = config.getLong(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG);
        this.cleanTimeMs = config.getLong(StreamsConfig.STATE_CLEANUP_DELAY_MS_CONFIG);

        this.time = time;
        this.timerStartedMs = time.milliseconds();
        this.lastCleanMs = Long.MAX_VALUE; // the cleaning cycle won't start until partition assignment
        this.lastCommitMs = timerStartedMs;
        this.rebalanceListener = new RebalanceListener(time, config.getInt(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG));
        setState(State.RUNNING);

    }

    public void partitionAssignor(StreamPartitionAssignor partitionAssignor) {
        this.partitionAssignor = partitionAssignor;
    }

    /**
     * Execute the stream processors
     *
     * @throws KafkaException for any Kafka-related exceptions
     * @throws Exception      for any other non-Kafka exceptions
     */
    @Override
    public void run() {
        log.info("{} Starting", logPrefix);

        try {
            runLoop();
            cleanRun = true;
        } catch (KafkaException e) {
            // just re-throw the exception as it should be logged already
            throw e;
        } catch (Exception e) {
            // we have caught all Kafka related exceptions, and other runtime exceptions
            // should be due to user application errors
            log.error("{} Streams application error during processing: ", logPrefix, e);
            throw e;
        } finally {
            shutdown();
        }
    }

    /**
     * Shutdown this stream thread.
     */
    public synchronized void close() {
        log.info("{} Informed thread to shut down", logPrefix);
        setState(State.PENDING_SHUTDOWN);
    }

    public Map<TaskId, StreamTask> tasks() {
        return Collections.unmodifiableMap(activeTasks);
    }


    private void shutdown() {
        log.info("{} Shutting down", logPrefix);
        shutdownTasksAndState();

        // close all embedded clients
        try {
            producer.close();
        } catch (Throwable e) {
            log.error("{} Failed to close producer: ", logPrefix, e);
        }
        try {
            consumer.close();
        } catch (Throwable e) {
            log.error("{} Failed to close consumer: ", logPrefix, e);
        }
        try {
            restoreConsumer.close();
        } catch (Throwable e) {
            log.error("{} Failed to close restore consumer: ", logPrefix, e);
        }
        try {
            partitionAssignor.close();
        } catch (Throwable e) {
            log.error("{} Failed to close KafkaStreamClient: ", logPrefix, e);
        }

        removeStreamTasks();
        removeStandbyTasks();

        // clean up global tasks

        log.info("{} Stream thread shutdown complete", logPrefix);
        setState(State.NOT_RUNNING);
        streamsMetrics.removeAllSensors();
    }

    private RuntimeException unAssignChangeLogPartitions() {
        try {
            // un-assign the change log partitions
            restoreConsumer.assign(Collections.<TopicPartition>emptyList());
        } catch (RuntimeException e) {
            log.error("{} Failed to un-assign change log partitions: ", logPrefix, e);
            return e;
        }
        return null;
    }


    @SuppressWarnings("ThrowableNotThrown")
    private void shutdownTasksAndState() {
        log.debug("{} shutdownTasksAndState: shutting down all active tasks {} and standby tasks {}", logPrefix,
            activeTasks.keySet(), standbyTasks.keySet());

        final AtomicReference<RuntimeException> firstException = new AtomicReference<>(null);
        // Close all processors in topology order
        firstException.compareAndSet(null, closeAllTasks());
        // flush state
        firstException.compareAndSet(null, flushAllState());
        // Close all task state managers. Don't need to set exception as all
        // state would have been flushed above
        closeAllStateManagers(firstException.get() == null);
        // only commit under clean exit
        if (cleanRun && firstException.get() == null) {
            firstException.set(commitOffsets());
        }
        // remove the changelog partitions from restore consumer
        unAssignChangeLogPartitions();
    }


    /**
     * Similar to shutdownTasksAndState, however does not close the task managers, in the hope that
     * soon the tasks will be assigned again
     */
    private void suspendTasksAndState()  {
        log.debug("{} suspendTasksAndState: suspending all active tasks {} and standby tasks {}", logPrefix,
            activeTasks.keySet(), standbyTasks.keySet());
        final AtomicReference<RuntimeException> firstException = new AtomicReference<>(null);
        // Close all topology nodes
        firstException.compareAndSet(null, closeAllTasksTopologies());
        // flush state
        firstException.compareAndSet(null, flushAllState());
        // only commit after all state has been flushed and there hasn't been an exception
        if (firstException.get() == null) {
            // TODO: currently commit failures will not be thrown to users
            // while suspending tasks; this need to be re-visit after KIP-98
            commitOffsets();
        }
        // remove the changelog partitions from restore consumer
        firstException.compareAndSet(null, unAssignChangeLogPartitions());

        updateSuspendedTasks();

        if (firstException.get() != null) {
            throw new StreamsException(logPrefix + " failed to suspend stream tasks", firstException.get());
        }
    }

    interface AbstractTaskAction {
        void apply(final AbstractTask task);
    }

    private RuntimeException performOnAllTasks(final AbstractTaskAction action,
                                   final String exceptionMessage) {
        RuntimeException firstException = null;
        final List<AbstractTask> allTasks = new ArrayList<AbstractTask>(activeTasks.values());
        allTasks.addAll(standbyTasks.values());
        for (final AbstractTask task : allTasks) {
            try {
                action.apply(task);
            } catch (RuntimeException t) {
                log.error("{} Failed while executing {} {} due to {}: ",
                        StreamThread.this.logPrefix,
                        task.getClass().getSimpleName(),
                        task.id(),
                        exceptionMessage,
                        t);
                if (firstException == null) {
                    firstException = t;
                }
            }
        }
        return firstException;
    }

    private Throwable closeAllStateManagers(final boolean writeCheckpoint) {
        return performOnAllTasks(new AbstractTaskAction() {
            @Override
            public void apply(final AbstractTask task) {
                log.info("{} Closing the state manager of task {}", StreamThread.this.logPrefix, task.id());
                task.closeStateManager(writeCheckpoint);
            }
        }, "close state manager");
    }

    private RuntimeException commitOffsets() {
        // Exceptions should not prevent this call from going through all shutdown steps
        return performOnAllTasks(new AbstractTaskAction() {
            @Override
            public void apply(final AbstractTask task) {
                log.info("{} Committing consumer offsets of task {}", StreamThread.this.logPrefix, task.id());
                task.commitOffsets();
            }
        }, "commit consumer offsets");
    }

    private RuntimeException flushAllState() {
        return performOnAllTasks(new AbstractTaskAction() {
            @Override
            public void apply(final AbstractTask task) {
                log.info("{} Flushing state stores of task {}", StreamThread.this.logPrefix, task.id());
                task.flushState();
            }
        }, "flush state");
    }

    /**
     * Compute the latency based on the current marked timestamp, and update the marked timestamp
     * with the current system timestamp.
     *
     * @return latency
     */
    private long computeLatency() {
        long previousTimeMs = this.timerStartedMs;
        this.timerStartedMs = time.milliseconds();

        return Math.max(this.timerStartedMs - previousTimeMs, 0);
    }

    /**
     * Get the next batch of records by polling.
     * @return Next batch of records or null if no records available.
     */
    private ConsumerRecords<byte[], byte[]> pollRequests(final long pollTimeMs) {
        ConsumerRecords<byte[], byte[]> records = null;

        try {
            records = consumer.poll(pollTimeMs);
        } catch (NoOffsetForPartitionException ex) {
            TopicPartition partition = ex.partition();
            if (builder.earliestResetTopicsPattern().matcher(partition.topic()).matches()) {
                log.info(String.format("stream-thread [%s] setting topic to consume from earliest offset %s", this.getName(), partition.topic()));
                consumer.seekToBeginning(ex.partitions());
            } else if (builder.latestResetTopicsPattern().matcher(partition.topic()).matches()) {
                consumer.seekToEnd(ex.partitions());
                log.info(String.format("stream-thread [%s] setting topic to consume from latest offset %s", this.getName(), partition.topic()));
            } else {

                if (originalReset == null || (!originalReset.equals("earliest") && !originalReset.equals("latest"))) {
                    setState(State.PENDING_SHUTDOWN);
                    String errorMessage = "No valid committed offset found for input topic %s (partition %s) and no valid reset policy configured." +
                        " You need to set configuration parameter \"auto.offset.reset\" or specify a topic specific reset " +
                        "policy via KStreamBuilder#stream(StreamsConfig.AutoOffsetReset offsetReset, ...) or KStreamBuilder#table(StreamsConfig.AutoOffsetReset offsetReset, ...)";
                    throw new StreamsException(String.format(errorMessage, partition.topic(), partition.partition()), ex);
                }

                if (originalReset.equals("earliest")) {
                    consumer.seekToBeginning(ex.partitions());
                } else if (originalReset.equals("latest")) {
                    consumer.seekToEnd(ex.partitions());
                }
                log.info(String.format("stream-thread [%s] no custom setting defined for topic %s using original config %s for offset reset", this.getName(), partition.topic(), originalReset));
            }

        }

        if (rebalanceException != null)
            throw new StreamsException(logPrefix + " Failed to rebalance", rebalanceException);

        return records;
    }

    /**
     * Take records and add them to each respective task
     * @param records Records, can be null
     */
    private void addRecordsToTasks(ConsumerRecords<byte[], byte[]> records) {
        if (records != null && !records.isEmpty()) {
            int numAddedRecords = 0;

            for (TopicPartition partition : records.partitions()) {
                StreamTask task = activeTasksByPartition.get(partition);
                numAddedRecords += task.addRecords(partition, records.records(partition));
            }
            streamsMetrics.skippedRecordsSensor.record(records.count() - numAddedRecords, timerStartedMs);
        }
    }

    /**
     * Schedule the records processing by selecting which record is processed next. Commits may
     * happen as records are processed.
     * @tasks The tasks that have records.
     * @param recordsProcessedBeforeCommit number of records to be processed before commit is called.
     *                                     if UNLIMITED_RECORDS, then commit is never called
     * @return Number of records processed since last commit.
     */
    private long processAndPunctuate(final Map<TaskId, StreamTask> tasks,
                                     final long recordsProcessedBeforeCommit) {

        long totalProcessedEachRound;
        long totalProcessedSinceLastMaybeCommit = 0;
        // Round-robin scheduling by taking one record from each task repeatedly
        // until no task has any records left
        do {
            totalProcessedEachRound = 0;
            for (StreamTask task : tasks.values()) {
                // we processed one record,
                // and more are buffered waiting for the next round
                if (task.process()) {
                    totalProcessedEachRound++;
                    totalProcessedSinceLastMaybeCommit++;
                }
            }
            if (recordsProcessedBeforeCommit != UNLIMITED_RECORDS &&
                totalProcessedSinceLastMaybeCommit >= recordsProcessedBeforeCommit) {
                totalProcessedSinceLastMaybeCommit = 0;
                long processLatency = computeLatency();
                streamsMetrics.processTimeSensor.record(processLatency / (double) totalProcessedSinceLastMaybeCommit,
                    timerStartedMs);
                maybeCommit(this.timerStartedMs);
            }
        } while (totalProcessedEachRound != 0);

        // go over the tasks again to punctuate or commit
        for (StreamTask task : tasks.values()) {
            maybePunctuate(task);
            if (task.commitNeeded())
                commitOne(task);
        }

        return totalProcessedSinceLastMaybeCommit;
    }

    /**
     * Adjust the number of records that should be processed by scheduler. This avoids
     * scenarios where the processing time is higher than the commit time.
     * @param prevRecordsProcessedBeforeCommit Previous number of records processed by scheduler.
     * @param totalProcessed Total number of records processed in this last round.
     * @param processLatency Total processing latency in ms processed in this last round.
     * @param commitTime Desired commit time in ms.
     * @return An adjusted number of records to be processed in the next round.
     */
    private long adjustRecordsProcessedBeforeCommit(final long prevRecordsProcessedBeforeCommit, final long totalProcessed,
                                                    final long processLatency, final long commitTime) {
        long recordsProcessedBeforeCommit = UNLIMITED_RECORDS;
        // check if process latency larger than commit latency
        // note that once we set recordsProcessedBeforeCommit, it will never be UNLIMITED_RECORDS again, so
        // we will never process all records again. This might be an issue if the initial measurement
        // was off due to a slow start.
        if (processLatency > commitTime) {
            // push down
            recordsProcessedBeforeCommit = Math.max(1, (commitTime * totalProcessed) / processLatency);
            log.debug("{} processing latency {} > commit time {} for {} records. Adjusting down recordsProcessedBeforeCommit={}",
                logPrefix, processLatency, commitTime, totalProcessed, recordsProcessedBeforeCommit);
        } else if (prevRecordsProcessedBeforeCommit != UNLIMITED_RECORDS && processLatency > 0) {
            // push up
            recordsProcessedBeforeCommit = Math.max(1, (commitTime * totalProcessed) / processLatency);
            log.debug("{} processing latency {} > commit time {} for {} records. Adjusting up recordsProcessedBeforeCommit={}",
                logPrefix, processLatency, commitTime, totalProcessed, recordsProcessedBeforeCommit);
        }

        return recordsProcessedBeforeCommit;
    }

    /**
     * Main event loop for polling, and processing records through topologies.
     */
    private void runLoop() {
        long recordsProcessedBeforeCommit = UNLIMITED_RECORDS;
        consumer.subscribe(sourceTopicPattern, rebalanceListener);

        while (stillRunning()) {
            this.timerStartedMs = time.milliseconds();

            // try to fetch some records if necessary
            ConsumerRecords<byte[], byte[]> records = pollRequests(this.pollTimeMs);
            if (records != null && !records.isEmpty() && !activeTasks.isEmpty()) {
                streamsMetrics.pollTimeSensor.record(computeLatency(), timerStartedMs);
                addRecordsToTasks(records);
                final long totalProcessed = processAndPunctuate(activeTasks, recordsProcessedBeforeCommit);
                if (totalProcessed > 0) {
                    final long processLatency = computeLatency();
                    streamsMetrics.processTimeSensor.record(processLatency / (double) totalProcessed,
                        timerStartedMs);
                    recordsProcessedBeforeCommit = adjustRecordsProcessedBeforeCommit(recordsProcessedBeforeCommit, totalProcessed,
                        processLatency, commitTimeMs);
                }
            }

            maybeCommit(timerStartedMs);
            maybeUpdateStandbyTasks();
            maybeClean(timerStartedMs);
        }
        log.info("{} Shutting down at user request", logPrefix);
    }

    private void maybeUpdateStandbyTasks() {
        if (!standbyTasks.isEmpty()) {
            if (processStandbyRecords) {
                if (!standbyRecords.isEmpty()) {
                    Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> remainingStandbyRecords = new HashMap<>();

                    for (TopicPartition partition : standbyRecords.keySet()) {
                        List<ConsumerRecord<byte[], byte[]>> remaining = standbyRecords.get(partition);
                        if (remaining != null) {
                            StandbyTask task = standbyTasksByPartition.get(partition);
                            remaining = task.update(partition, remaining);
                            if (remaining != null) {
                                remainingStandbyRecords.put(partition, remaining);
                            } else {
                                restoreConsumer.resume(singleton(partition));
                            }
                        }
                    }

                    standbyRecords = remainingStandbyRecords;
                }
                processStandbyRecords = false;
            }

            ConsumerRecords<byte[], byte[]> records = restoreConsumer.poll(0);

            if (!records.isEmpty()) {
                for (TopicPartition partition : records.partitions()) {
                    StandbyTask task = standbyTasksByPartition.get(partition);

                    if (task == null) {
                        throw new StreamsException(logPrefix + " Missing standby task for partition " + partition);
                    }

                    List<ConsumerRecord<byte[], byte[]>> remaining = task.update(partition, records.records(partition));
                    if (remaining != null) {
                        restoreConsumer.pause(singleton(partition));
                        standbyRecords.put(partition, remaining);
                    }
                }
            }
        }
    }

    public synchronized boolean stillRunning() {
        return state.isRunning();
    }

    private void maybePunctuate(StreamTask task) {
        try {
            // check whether we should punctuate based on the task's partition group timestamp;
            // which are essentially based on record timestamp.
            if (task.maybePunctuate())
                streamsMetrics.punctuateTimeSensor.record(computeLatency(), timerStartedMs);

        } catch (KafkaException e) {
            log.error("{} Failed to punctuate active task {}: ", logPrefix, task.id(), e);
            throw e;
        }
    }

    /**
     * Commit all tasks owned by this thread if specified interval time has elapsed
     */
    protected void maybeCommit(final long now) {

        if (commitTimeMs >= 0 && lastCommitMs + commitTimeMs < now) {

            log.info("{} Committing all active tasks {} and standby tasks {} because the commit interval {}ms has elapsed by {}ms",
                    logPrefix, activeTasks, standbyTasks, commitTimeMs, now - lastCommitMs);

            commitAll();
            lastCommitMs = now;

            processStandbyRecords = true;
        }
    }

    /**
     * Cleanup any states of the tasks that have been removed from this thread
     */
    protected void maybeClean(final long now) {

        if (now > lastCleanMs + cleanTimeMs) {
            stateDirectory.cleanRemovedTasks(cleanTimeMs);
            lastCleanMs = now;
        }
    }

    /**
     * Commit the states of all its tasks
     */
    private void commitAll() {
        for (StreamTask task : activeTasks.values()) {
            commitOne(task);
        }
        for (StandbyTask task : standbyTasks.values()) {
            commitOne(task);
        }
    }

    /**
     * Commit the state of a task
     */
    private void commitOne(AbstractTask task) {
        log.info("{} Committing task {} {}", logPrefix, task.getClass().getSimpleName(), task.id());
        try {
            task.commit();
        } catch (CommitFailedException e) {
            // commit failed. Just log it.
            log.warn("{} Failed to commit {} {} state: ", logPrefix, task.getClass().getSimpleName(), task.id(), e);
        } catch (KafkaException e) {
            // commit failed due to an unexpected exception. Log it and rethrow the exception.
            log.error("{} Failed to commit {} {} state: ", logPrefix, task.getClass().getSimpleName(), task.id(), e);
            throw e;
        }

        streamsMetrics.commitTimeSensor.record(computeLatency(), timerStartedMs);
    }

    /**
     * Returns ids of tasks that were being executed before the rebalance.
     */
    public Set<TaskId> prevActiveTasks() {
        return Collections.unmodifiableSet(prevActiveTasks);
    }

    /**
     * Returns ids of tasks whose states are kept on the local storage.
     */
    public Set<TaskId> cachedTasks() {
        // A client could contain some inactive tasks whose states are still kept on the local storage in the following scenarios:
        // 1) the client is actively maintaining standby tasks by maintaining their states from the change log.
        // 2) the client has just got some tasks migrated out of itself to other clients while these task states
        //    have not been cleaned up yet (this can happen in a rolling bounce upgrade, for example).

        HashSet<TaskId> tasks = new HashSet<>();

        File[] stateDirs = stateDirectory.listTaskDirectories();
        if (stateDirs != null) {
            for (File dir : stateDirs) {
                try {
                    TaskId id = TaskId.parse(dir.getName());
                    // if the checkpoint file exists, the state is valid.
                    if (new File(dir, ProcessorStateManager.CHECKPOINT_FILE_NAME).exists())
                        tasks.add(id);

                } catch (TaskIdFormatException e) {
                    // there may be some unknown files that sits in the same directory,
                    // we should ignore these files instead trying to delete them as well
                }
            }
        }

        return tasks;
    }

    private StreamTask findMatchingSuspendedTask(final TaskId taskId, final Set<TopicPartition> partitions) {
        if (suspendedTasks.containsKey(taskId)) {
            final StreamTask task = suspendedTasks.get(taskId);
            if (task.partitions.equals(partitions)) {
                return task;
            }
        }
        return null;
    }

    private StandbyTask findMatchingSuspendedStandbyTask(final TaskId taskId, final Set<TopicPartition> partitions) {
        if (suspendedStandbyTasks.containsKey(taskId)) {
            final StandbyTask task = suspendedStandbyTasks.get(taskId);
            if (task.partitions.equals(partitions)) {
                return task;
            }
        }
        return null;
    }

    private void closeNonAssignedSuspendedTasks() {
        final Map<TaskId, Set<TopicPartition>> newTaskAssignment = partitionAssignor.activeTasks();
        final Iterator<Map.Entry<TaskId, StreamTask>> suspendedTaskIterator = suspendedTasks.entrySet().iterator();
        while (suspendedTaskIterator.hasNext()) {
            final Map.Entry<TaskId, StreamTask> next = suspendedTaskIterator.next();
            final StreamTask task = next.getValue();
            final Set<TopicPartition> assignedPartitionsForTask = newTaskAssignment.get(next.getKey());
            if (!task.partitions().equals(assignedPartitionsForTask)) {
                log.debug("{} Closing suspended non-assigned active task {}", logPrefix, task.id);
                try {
                    task.close();
                    task.closeStateManager(true);
                } catch (Exception e) {
                    log.error("{} Failed to remove suspended task {}", logPrefix, next.getKey(), e);
                } finally {
                    suspendedTaskIterator.remove();
                }
            }
        }

    }

    private void closeNonAssignedSuspendedStandbyTasks() {
        final Set<TaskId> currentSuspendedTaskIds = partitionAssignor.standbyTasks().keySet();
        final Iterator<Map.Entry<TaskId, StandbyTask>> standByTaskIterator = suspendedStandbyTasks.entrySet().iterator();
        while (standByTaskIterator.hasNext()) {
            final Map.Entry<TaskId, StandbyTask> suspendedTask = standByTaskIterator.next();
            if (!currentSuspendedTaskIds.contains(suspendedTask.getKey())) {
                final StandbyTask task = suspendedTask.getValue();
                log.debug("{} Closing suspended non-assigned standby task {}", logPrefix, task.id);
                try {
                    task.close();
                    task.closeStateManager(true);
                } catch (Exception e) {
                    log.error("{} Failed to remove suspended standby task {}", logPrefix, task.id, e);
                } finally {
                    standByTaskIterator.remove();
                }
            }
        }
    }

    protected StreamTask createStreamTask(TaskId id, Collection<TopicPartition> partitions) {
        log.debug("{} Creating new active task {} with assigned partitions {}", logPrefix, id, partitions);

        streamsMetrics.taskCreatedSensor.record();

        final ProcessorTopology topology = builder.build(id.topicGroupId);
        final RecordCollector recordCollector = new RecordCollectorImpl(producer, id.toString());
        try {
            return new StreamTask(id, applicationId, partitions, topology, consumer, storeChangelogReader, config, streamsMetrics, stateDirectory, cache, time, recordCollector);
        } finally {
            log.info("{} Created active task {} with assigned partitions {}", logPrefix, id, partitions);
        }
    }

    private void addStreamTasks(Collection<TopicPartition> assignment, final long start) {
        if (partitionAssignor == null)
            throw new IllegalStateException(logPrefix + " Partition assignor has not been initialized while adding stream tasks: this should not happen.");

        final Map<TaskId, Set<TopicPartition>> newTasks = new HashMap<>();

        // collect newly assigned tasks and reopen re-assigned tasks
        for (Map.Entry<TaskId, Set<TopicPartition>> entry : partitionAssignor.activeTasks().entrySet()) {
            final TaskId taskId = entry.getKey();
            final Set<TopicPartition> partitions = entry.getValue();

            if (assignment.containsAll(partitions)) {
                try {
                    StreamTask task = findMatchingSuspendedTask(taskId, partitions);
                    if (task != null) {
                        log.debug("{} recycling old task {}", logPrefix, taskId);
                        suspendedTasks.remove(taskId);
                        task.initTopology();

                        activeTasks.put(taskId, task);

                        for (TopicPartition partition : partitions) {
                            activeTasksByPartition.put(partition, task);
                        }
                    } else {
                        newTasks.put(taskId, partitions);
                    }
                } catch (StreamsException e) {
                    log.error("{} Failed to create an active task {}: ", logPrefix, taskId, e);
                    throw e;
                }
            } else {
                log.warn("{} Task {} owned partitions {} are not contained in the assignment {}", logPrefix, taskId, partitions, assignment);
            }
        }

        // create all newly assigned tasks (guard against race condition with other thread via backoff and retry)
        // -> other thread will call removeSuspendedTasks(); eventually
        log.debug("{} new active tasks to be created: {}", logPrefix, newTasks);

        taskCreator.retryWithBackoff(newTasks, start);
    }

    private StandbyTask createStandbyTask(TaskId id, Collection<TopicPartition> partitions) {
        log.debug("{} Creating new standby task {} with assigned partitions {}", logPrefix, id, partitions);

        streamsMetrics.taskCreatedSensor.record();

        ProcessorTopology topology = builder.build(id.topicGroupId);

        if (!topology.stateStores().isEmpty()) {
            try {
                return new StandbyTask(id, applicationId, partitions, topology, consumer, storeChangelogReader, config, streamsMetrics, stateDirectory);
            } finally {
                log.info("{} Created standby task {} with assigned partitions {}", logPrefix, id, partitions);
            }
        } else {
            log.info("{} Skipped standby task {} with assigned partitions {} since it does not have any state stores to materialize", logPrefix, id, partitions);

            return null;
        }
    }

    private void addStandbyTasks(final long start) {
        if (partitionAssignor == null)
            throw new IllegalStateException(logPrefix + " Partition assignor has not been initialized while adding standby tasks: this should not happen.");

        Map<TopicPartition, Long> checkpointedOffsets = new HashMap<>();

        final Map<TaskId, Set<TopicPartition>> newStandbyTasks = new HashMap<>();

        // collect newly assigned standby tasks and reopen re-assigned standby tasks
        for (Map.Entry<TaskId, Set<TopicPartition>> entry : partitionAssignor.standbyTasks().entrySet()) {
            final TaskId taskId = entry.getKey();
            final Set<TopicPartition> partitions = entry.getValue();
            StandbyTask task = findMatchingSuspendedStandbyTask(taskId, partitions);

            if (task != null) {
                log.debug("{} recycling old standby task {}", logPrefix, taskId);
                suspendedStandbyTasks.remove(taskId);
                task.initTopology();
            } else {
                newStandbyTasks.put(taskId, partitions);
            }

            updateStandByTaskMaps(checkpointedOffsets, taskId, partitions, task);
        }

        // create all newly assigned standby tasks (guard against race condition with other thread via backoff and retry)
        // -> other thread will call removeSuspendedStandbyTasks(); eventually
        log.debug("{} new standby tasks to be created: {}", logPrefix, newStandbyTasks);

        new StandbyTaskCreator(checkpointedOffsets).retryWithBackoff(newStandbyTasks, start);

        restoreConsumer.assign(new ArrayList<>(checkpointedOffsets.keySet()));

        for (Map.Entry<TopicPartition, Long> entry : checkpointedOffsets.entrySet()) {
            TopicPartition partition = entry.getKey();
            long offset = entry.getValue();
            if (offset >= 0) {
                restoreConsumer.seek(partition, offset);
            } else {
                restoreConsumer.seekToBeginning(singleton(partition));
            }
        }
    }

    private void updateStandByTaskMaps(final Map<TopicPartition, Long> checkpointedOffsets, final TaskId taskId, final Set<TopicPartition> partitions, final StandbyTask task) {
        if (task != null) {
            standbyTasks.put(taskId, task);
            for (TopicPartition partition : partitions) {
                standbyTasksByPartition.put(partition, task);
            }
            // collect checked pointed offsets to position the restore consumer
            // this include all partitions from which we restore states
            for (TopicPartition partition : task.checkpointedOffsets().keySet()) {
                standbyTasksByPartition.put(partition, task);
            }
            checkpointedOffsets.putAll(task.checkpointedOffsets());
        }
    }

    private void updateSuspendedTasks() {
        log.info("{} Updating suspended tasks to contain active tasks {}", logPrefix, activeTasks.keySet());
        suspendedTasks.clear();
        suspendedTasks.putAll(activeTasks);
        suspendedStandbyTasks.putAll(standbyTasks);
    }

    private void removeStreamTasks() {
        log.info("{} Removing all active tasks {}", logPrefix, activeTasks.keySet());

        try {
            prevActiveTasks.clear();
            prevActiveTasks.addAll(activeTasks.keySet());

            activeTasks.clear();
            activeTasksByPartition.clear();

        } catch (Exception e) {
            log.error("{} Failed to remove stream tasks: ", logPrefix, e);
        }
    }

    private void removeStandbyTasks() {
        log.info("{} Removing all standby tasks {}", logPrefix, standbyTasks.keySet());

        standbyTasks.clear();
        standbyTasksByPartition.clear();
        standbyRecords.clear();
    }

    private RuntimeException closeAllTasks() {
        return performOnAllTasks(new AbstractTaskAction() {
            @Override
            public void apply(final AbstractTask task) {
                log.info("{} Closing task {}", StreamThread.this.logPrefix, task.id());
                task.close();
                streamsMetrics.tasksClosedSensor.record();
            }
        }, "close");
    }

    private RuntimeException closeAllTasksTopologies() {
        return performOnAllTasks(new AbstractTaskAction() {
            @Override
            public void apply(final AbstractTask task) {
                log.info("{} Closing task's topology {}", StreamThread.this.logPrefix, task.id());
                task.closeTopology();
                streamsMetrics.tasksClosedSensor.record();
            }
        }, "close");
    }

    /**
     * Produces a string representation containing useful information about a StreamThread.
     * This is useful in debugging scenarios.
     * @return A string representation of the StreamThread instance.
     */
    @Override
    public String toString() {
        return toString("");
    }

    /**
     * Produces a string representation containing useful information about a StreamThread, starting with the given indent.
     * This is useful in debugging scenarios.
     * @return A string representation of the StreamThread instance.
     */
    public String toString(final String indent) {
        final StringBuilder sb = new StringBuilder(indent + "StreamsThread appId: " + this.applicationId + "\n");
        sb.append(indent).append("\tStreamsThread clientId: ").append(clientId).append("\n");
        sb.append(indent).append("\tStreamsThread threadId: ").append(this.getName()).append("\n");

        // iterate and print active tasks
        if (activeTasks != null) {
            sb.append(indent).append("\tActive tasks:\n");
            for (TaskId tId : activeTasks.keySet()) {
                StreamTask task = activeTasks.get(tId);
                sb.append(indent).append(task.toString(indent + "\t\t"));
            }
        }

        // iterate and print standby tasks
        if (standbyTasks != null) {
            sb.append(indent).append("\tStandby tasks:\n");
            for (TaskId tId : standbyTasks.keySet()) {
                StandbyTask task = standbyTasks.get(tId);
                sb.append(indent).append(task.toString(indent + "\t\t"));
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * This class extends {@link StreamsMetricsImpl(Metrics, String, String, Map)} and
     * overrides one of its functions for efficiency
     */
    private class StreamsMetricsThreadImpl extends StreamsMetricsImpl {
        final Sensor commitTimeSensor;
        final Sensor pollTimeSensor;
        final Sensor processTimeSensor;
        final Sensor punctuateTimeSensor;
        final Sensor taskCreatedSensor;
        final Sensor tasksClosedSensor;
        final Sensor skippedRecordsSensor;

        public StreamsMetricsThreadImpl(Metrics metrics, String groupName, String prefix, Map<String, String> tags) {
            super(metrics, groupName, tags);
            this.commitTimeSensor = metrics.sensor(prefix + ".commit-latency", Sensor.RecordingLevel.INFO);
            this.commitTimeSensor.add(metrics.metricName("commit-latency-avg", this.groupName, "The average commit time in ms", this.tags), new Avg());
            this.commitTimeSensor.add(metrics.metricName("commit-latency-max", this.groupName, "The maximum commit time in ms", this.tags), new Max());
            this.commitTimeSensor.add(metrics.metricName("commit-rate", this.groupName, "The average per-second number of commit calls", this.tags), new Rate(new Count()));

            this.pollTimeSensor = metrics.sensor(prefix + ".poll-latency", Sensor.RecordingLevel.INFO);
            this.pollTimeSensor.add(metrics.metricName("poll-latency-avg", this.groupName, "The average poll time in ms", this.tags), new Avg());
            this.pollTimeSensor.add(metrics.metricName("poll-latency-max", this.groupName, "The maximum poll time in ms", this.tags), new Max());
            this.pollTimeSensor.add(metrics.metricName("poll-rate", this.groupName, "The average per-second number of record-poll calls", this.tags), new Rate(new Count()));

            this.processTimeSensor = metrics.sensor(prefix + ".process-latency", Sensor.RecordingLevel.INFO);
            this.processTimeSensor.add(metrics.metricName("process-latency-avg", this.groupName, "The average process time in ms", this.tags), new Avg());
            this.processTimeSensor.add(metrics.metricName("process-latency-max", this.groupName, "The maximum process time in ms", this.tags), new Max());
            this.processTimeSensor.add(metrics.metricName("process-rate", this.groupName, "The average per-second number of process calls", this.tags), new Rate(new Count()));

            this.punctuateTimeSensor = metrics.sensor(prefix + ".punctuate-latency", Sensor.RecordingLevel.INFO);
            this.punctuateTimeSensor.add(metrics.metricName("punctuate-latency-avg", this.groupName, "The average punctuate time in ms", this.tags), new Avg());
            this.punctuateTimeSensor.add(metrics.metricName("punctuate-latency-max", this.groupName, "The maximum punctuate time in ms", this.tags), new Max());
            this.punctuateTimeSensor.add(metrics.metricName("punctuate-rate", this.groupName, "The average per-second number of punctuate calls", this.tags), new Rate(new Count()));

            this.taskCreatedSensor = metrics.sensor(prefix + ".task-created", Sensor.RecordingLevel.INFO);
            this.taskCreatedSensor.add(metrics.metricName("task-created-rate", this.groupName, "The average per-second number of newly created tasks", this.tags), new Rate(new Count()));

            this.tasksClosedSensor = metrics.sensor(prefix + ".task-closed", Sensor.RecordingLevel.INFO);
            this.tasksClosedSensor.add(metrics.metricName("task-closed-rate", this.groupName, "The average per-second number of closed tasks", this.tags), new Rate(new Count()));

            this.skippedRecordsSensor = metrics.sensor(prefix + ".skipped-records");
            this.skippedRecordsSensor.add(metrics.metricName("skipped-records-rate", this.groupName, "The average per-second number of skipped records.", this.tags), new Rate(new Count()));

        }


        @Override
        public void recordLatency(Sensor sensor, long startNs, long endNs) {
            sensor.record(endNs - startNs, timerStartedMs);
        }

        public void removeAllSensors() {
            removeSensor(commitTimeSensor);
            removeSensor(pollTimeSensor);
            removeSensor(processTimeSensor);
            removeSensor(punctuateTimeSensor);
            removeSensor(taskCreatedSensor);
            removeSensor(tasksClosedSensor);
            removeSensor(skippedRecordsSensor);

        }
    }

    abstract class AbstractTaskCreator {
        void retryWithBackoff(final Map<TaskId, Set<TopicPartition>> tasksToBeCreated, final long start) {
            long backoffTimeMs = 50L;
            while (true) {
                final Iterator<Map.Entry<TaskId, Set<TopicPartition>>> it = tasksToBeCreated.entrySet().iterator();
                while (it.hasNext()) {
                    final Map.Entry<TaskId, Set<TopicPartition>> newTaskAndPartitions = it.next();
                    final TaskId taskId = newTaskAndPartitions.getKey();
                    final Set<TopicPartition> partitions = newTaskAndPartitions.getValue();

                    try {
                        createTask(taskId, partitions);
                        it.remove();
                        backoffTimeMs = 50L;
                    } catch (final LockException e) {
                        // ignore and retry
                        log.warn("Could not create task {}. Will retry.", taskId, e);
                    }
                }

                if (tasksToBeCreated.isEmpty() || time.milliseconds() - start > rebalanceTimeoutMs) {
                    break;
                }

                try {
                    Thread.sleep(backoffTimeMs);
                    backoffTimeMs <<= 1;
                } catch (InterruptedException e) {
                    // ignore
                }
            }
        }

        abstract void createTask(final TaskId id, final Set<TopicPartition> partitions);
    }

    class TaskCreator extends AbstractTaskCreator {
        void createTask(final TaskId taskId, final Set<TopicPartition> partitions) {
            final StreamTask task = createStreamTask(taskId, partitions);

            activeTasks.put(taskId, task);

            for (TopicPartition partition : partitions) {
                activeTasksByPartition.put(partition, task);
            }
        }
    }

    class StandbyTaskCreator extends AbstractTaskCreator {
        private final Map<TopicPartition, Long> checkpointedOffsets;

        StandbyTaskCreator(final Map<TopicPartition, Long> checkpointedOffsets) {
            this.checkpointedOffsets = checkpointedOffsets;
        }

        void createTask(final TaskId taskId, final Set<TopicPartition> partitions) {
            final StandbyTask task = createStandbyTask(taskId, partitions);
            updateStandByTaskMaps(checkpointedOffsets, taskId, partitions, task);
        }
    }

    private class RebalanceListener implements ConsumerRebalanceListener {
        private final Time time;
        private final int requestTimeOut;

        RebalanceListener(final Time time, final int requestTimeOut) {
            this.time = time;
            this.requestTimeOut = requestTimeOut;
        }

        @Override
        public void onPartitionsAssigned(Collection<TopicPartition> assignment) {
            log.info("{} at state {}: new partitions {} assigned at the end of consumer rebalance.\n" +
                            "\tassigned active tasks: {}\n" +
                            "\tassigned standby tasks: {}\n" +
                            "\tcurrent suspended active tasks: {}\n" +
                            "\tcurrent suspended standby tasks: {}\n" +
                            "\tprevious active tasks: {}",
                    logPrefix, state, assignment,
                    partitionAssignor.activeTasks(), partitionAssignor.standbyTasks(),
                    suspendedTasks, suspendedStandbyTasks, prevActiveTasks);

            final long start = time.milliseconds();
            try {
                storeChangelogReader = new StoreChangelogReader(getName(), restoreConsumer, time, requestTimeOut);
                setStateWhenNotInPendingShutdown(State.ASSIGNING_PARTITIONS);
                // do this first as we may have suspended standby tasks that
                // will become active or vice versa
                closeNonAssignedSuspendedStandbyTasks();
                closeNonAssignedSuspendedTasks();
                addStreamTasks(assignment, start);
                storeChangelogReader.restore();
                addStandbyTasks(start);
                streamsMetadataState.onChange(partitionAssignor.getPartitionsByHostState(), partitionAssignor.clusterMetadata());
                lastCleanMs = time.milliseconds(); // start the cleaning cycle
                setStateWhenNotInPendingShutdown(State.RUNNING);
            } catch (Throwable t) {
                rebalanceException = t;
                throw t;
            } finally {
                log.info("{} partition assignment took {} ms.\n" +
                        "\tcurrent active tasks: {}\n" +
                        "\tcurrent standby tasks: {}",
                        logPrefix, time.milliseconds() - start,
                        activeTasks, standbyTasks);
            }
        }

        @Override
        public void onPartitionsRevoked(Collection<TopicPartition> assignment) {
            log.info("{} at state {}: partitions {} revoked at the beginning of consumer rebalance.\n" +
                            "\tcurrent assigned active tasks: {}\n" +
                            "\tcurrent assigned standby tasks: {}\n",
                    logPrefix, state, assignment,
                    activeTasks, standbyTasks);

            final long start = time.milliseconds();
            try {
                setStateWhenNotInPendingShutdown(State.PARTITIONS_REVOKED);
                lastCleanMs = Long.MAX_VALUE; // stop the cleaning cycle until partitions are assigned
                // suspend active tasks
                suspendTasksAndState();
            } catch (Throwable t) {
                rebalanceException = t;
                throw t;
            } finally {
                streamsMetadataState.onChange(Collections.<HostInfo, Set<TopicPartition>>emptyMap(), partitionAssignor.clusterMetadata());
                removeStreamTasks();
                removeStandbyTasks();

                log.info("{} partition revocation took {} ms.\n" +
                                "\tsuspended active tasks: {}\n" +
                                "\tsuspended standby tasks: {}\n" +
                                "\tprevious active tasks: {}\n",
                        logPrefix, time.milliseconds() - start,
                        suspendedTasks, suspendedStandbyTasks, prevActiveTasks);
            }
        }
    }
}
