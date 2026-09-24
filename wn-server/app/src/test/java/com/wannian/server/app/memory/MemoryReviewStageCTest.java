package com.wannian.server.app.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.conversation.MessageRole;
import com.wannian.server.kernel.conversation.ConversationStore;
import com.wannian.server.kernel.conversation.CreateConversationCommand;
import com.wannian.server.kernel.conversation.CreateConversationResult;
import com.wannian.server.kernel.memory.ApprovedMemoryChange;
import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.memory.MemoryReviewConstants;
import com.wannian.server.kernel.memory.MemoryReviewBatchApplier;
import com.wannian.server.kernel.memory.MemoryReviewLlm;
import com.wannian.server.kernel.memory.MemoryReviewLease;
import com.wannian.server.kernel.memory.MemoryReviewScheduler;
import com.wannian.server.kernel.memory.MemoryReviewWorkerPort;
import com.wannian.server.kernel.memory.MemoryStore;
import com.wannian.server.kernel.memory.StoredMemoryRecord;
import com.wannian.server.kernel.turn.CommitTurnPlan;
import com.wannian.server.kernel.turn.ExecutionClaim;
import com.wannian.server.kernel.turn.FreezeCommitPlan;
import com.wannian.server.kernel.turn.ReceiveTurnPlan;
import com.wannian.server.kernel.turn.Turn;
import com.wannian.server.kernel.turn.TurnCommitter;
import com.wannian.server.kernel.turn.TurnRepository;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 0.2.3-C：Review enqueue / Worker / 假 LLM / 短事务 APPLY。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(MemoryReviewStageCTest.ReviewTestConfig.class)
class MemoryReviewStageCTest {

    @TestConfiguration
    static class ReviewTestConfig {
        @Bean
        @Primary
        FakeMemoryReviewLlm fakeMemoryReviewLlm() {
            return new FakeMemoryReviewLlm();
        }
    }

    @TempDir
    static Path tempDataDir;

    @DynamicPropertySource
    static void registerDataDir(DynamicPropertyRegistry registry) {
        registry.add("wannian.data-dir", () -> tempDataDir.toAbsolutePath().toString());
        registry.add("wannian.memory.review.tick-ms", () -> "3600000");
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ConversationStore conversationStore;

    @Autowired
    private TurnCommitter turnCommitter;

    @Autowired
    private TurnRepository turnRepository;

    @Autowired
    private MemoryReviewScheduler scheduler;

    @Autowired
    private MemoryReviewTurnHooks turnHooks;

    @Autowired
    private MemoryIdleScanner idleScanner;

    @Autowired
    private MemoryReviewWorkerPort worker;

    @Autowired
    private FakeMemoryReviewLlm fakeLlm;

    @Autowired
    private MemoryStore memoryStore;

    @Autowired
    private MemoryReviewBatchApplier reviewBatchApplier;

    @BeforeEach
    void clear() throws Exception {
        fakeLlm.clear();
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().executeUpdate("DELETE FROM memory_review_job");
            connection.createStatement().executeUpdate("DELETE FROM memory_subject_generation");
            connection.createStatement().executeUpdate("DELETE FROM memory_record");
            connection.createStatement().executeUpdate("DELETE FROM relationship_state");
            connection.createStatement().executeUpdate("DELETE FROM outbox_event");
            connection.createStatement().executeUpdate("DELETE FROM turn_commit_plan");
            connection.createStatement().executeUpdate("DELETE FROM turn_step");
            connection.createStatement().executeUpdate("DELETE FROM turn");
            connection.createStatement().executeUpdate("DELETE FROM message");
            connection.createStatement().executeUpdate("DELETE FROM conversation");
        }
    }

    @Test
    void enqueueSkipsDuplicatePending() throws Exception {
        ConversationId conversationId = ConversationId.generate();
        conversationStore.create(CreateConversationCommand.of(conversationId));

        scheduler.enqueue(
                conversationId.asString(), CompanionIdentity.YANHUO, MemoryReviewScheduler.Trigger.INTERVAL);
        scheduler.enqueue(
                conversationId.asString(), CompanionIdentity.YANHUO, MemoryReviewScheduler.Trigger.INTERVAL);

        assertThat(countJobs(conversationId.asString(), "PENDING")).isEqualTo(1);
    }

    @Test
    void intervalHookEnqueuesEveryTenthCompletedTurn() throws Exception {
        ConversationId conversationId = ConversationId.generate();
        conversationStore.create(CreateConversationCommand.of(conversationId));

        for (int i = 0; i < MemoryReviewConstants.INTERVAL_USER_TURNS; i++) {
            completeOneTurn(conversationId, "u-" + i);
            turnHooks.afterTurnCompleted(conversationId);
        }

        assertThat(countJobs(conversationId.asString(), "PENDING")).isEqualTo(1);
        assertThat(jobTrigger(conversationId.asString())).isEqualTo("INTERVAL");
    }

    @Test
    void workerWithFakeLlmAppliesReviewMemory() throws Exception {
        ConversationId conversationId = ConversationId.generate();
        conversationStore.create(CreateConversationCommand.of(conversationId));
        completeOneTurn(conversationId, "我喜欢喝龙井");

        fakeLlm.enqueueFact(
                CompanionIdentity.YANHUO, "pref.tea", "用户喜欢喝龙井", 0.8);

        scheduler.enqueue(
                conversationId.asString(), CompanionIdentity.YANHUO, MemoryReviewScheduler.Trigger.IDLE);
        assertThat(worker.pollOnce()).isTrue();
        assertThat(worker.pollOnce()).isFalse();

        List<StoredMemoryRecord> active = memoryStore.listActive(CompanionIdentity.YANHUO);
        assertThat(active).hasSize(1);
        assertThat(active.get(0).claim()).isEqualTo("用户喜欢喝龙井");
        assertThat(active.get(0).importance()).isEqualTo(0.8);
        assertThat(active.get(0).proposeId()).isEqualTo(ApprovedMemoryChange.PROPOSE_LLM_REVIEW);
        assertThat(countJobs(conversationId.asString(), "SUCCEEDED")).isEqualTo(1);
        assertThat(fakeLlm.calls()).hasSize(1);
        assertThat(fakeLlm.calls().get(0).placeAnchor())
                .isEqualTo(MemoryReviewConstants.PLACE_ANCHOR_UNSPECIFIED);
        assertThat(fakeLlm.calls().get(0).wallClockDate()).isNotBlank();
    }

    @Test
    void workerFailureGoesRetryThenDeadWithoutThrowing() throws Exception {
        ConversationId conversationId = ConversationId.generate();
        conversationStore.create(CreateConversationCommand.of(conversationId));

        MemoryReviewLlm throwing =
                request -> {
                    throw new IllegalStateException("fake llm boom");
                };
        InProcessMemoryReviewWorker exploding =
                new InProcessMemoryReviewWorker(
                        dataSource, conversationStore, memoryStore, throwing, reviewBatchApplier);

        scheduler.enqueue(
                conversationId.asString(), CompanionIdentity.YANHUO, MemoryReviewScheduler.Trigger.INTERVAL);

        assertThat(exploding.pollOnce()).isTrue();
        assertThat(countJobs(conversationId.asString(), "PENDING")).isEqualTo(1);

        exploding.pollOnce(); // attempt 2 → still PENDING
        exploding.pollOnce(); // attempt 3 → DEAD
        assertThat(countJobs(conversationId.asString(), "DEAD")).isEqualTo(1);
        assertThat(countJobs(conversationId.asString(), "PENDING")).isEqualTo(0);
    }

    @Test
    void periodicReviewTickerIsDisabledInTestContext() {
        assertThat(applicationContext.getBeansOfType(MemoryReviewConfig.MemoryReviewTicker.class))
                .isEmpty();
    }

    @Test
    void restartedWorkerReclaimsExpiredLeaseAndContinuesAttemptBudget() throws Exception {
        ConversationId conversationId = ConversationId.generate();
        conversationStore.create(CreateConversationCommand.of(conversationId));
        scheduler.enqueue(
                conversationId.asString(), CompanionIdentity.YANHUO, MemoryReviewScheduler.Trigger.INTERVAL);
        setRunningLease(conversationId.asString(), 1, "old-worker", Instant.now().minusSeconds(30));

        // 新 worker 实例代表进程重启；过期 claim 在认领事务中回收并继续 attempt=2。
        InProcessMemoryReviewWorker restarted =
                new InProcessMemoryReviewWorker(
                        dataSource,
                        conversationStore,
                        memoryStore,
                        fakeLlm,
                        reviewBatchApplier);
        assertThat(restarted.pollOnce()).isTrue();
        assertThat(countJobs(conversationId.asString(), "SUCCEEDED")).isEqualTo(1);
        assertThat(jobAttempt(conversationId.asString())).isEqualTo(2);
    }

    @Test
    void expiredFinalAttemptBecomesDeadWithoutExceedingRetryBudget() throws Exception {
        ConversationId conversationId = ConversationId.generate();
        conversationStore.create(CreateConversationCommand.of(conversationId));
        scheduler.enqueue(
                conversationId.asString(), CompanionIdentity.YANHUO, MemoryReviewScheduler.Trigger.INTERVAL);
        setRunningLease(
                conversationId.asString(),
                MemoryReviewConstants.MAX_ATTEMPTS,
                "crashed-final-attempt",
                Instant.now().minusSeconds(30));

        assertThat(worker.pollOnce()).isFalse();
        assertThat(countJobs(conversationId.asString(), "DEAD")).isEqualTo(1);
        assertThat(jobAttempt(conversationId.asString())).isEqualTo(MemoryReviewConstants.MAX_ATTEMPTS);
    }

    @Test
    void reclaimedLeaseFencesLateWorkerBeforeItCanApply() throws Exception {
        ConversationId conversationId = ConversationId.generate();
        conversationStore.create(CreateConversationCommand.of(conversationId));
        fakeLlm.enqueueFact(CompanionIdentity.YANHUO, "pref.tea", "用户喜欢喝龙井", 0.8);
        scheduler.enqueue(
                conversationId.asString(), CompanionIdentity.YANHUO, MemoryReviewScheduler.Trigger.INTERVAL);
        CountDownLatch llmStarted = new CountDownLatch(1);
        CountDownLatch finishLlm = new CountDownLatch(1);
        MemoryReviewLlm delayedLlm =
                request -> {
                    llmStarted.countDown();
                    try {
                        if (!finishLlm.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test LLM release timed out");
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("test LLM interrupted", ex);
                    }
                    return fakeLlm.propose(request);
                };
        InProcessMemoryReviewWorker oldWorker =
                new InProcessMemoryReviewWorker(
                        dataSource, conversationStore, memoryStore, delayedLlm, reviewBatchApplier);
        AtomicReference<Throwable> oldWorkerError = new AtomicReference<>();
        Thread oldWorkerThread =
                new Thread(
                        () -> {
                            try {
                                oldWorker.pollOnce();
                            } catch (Throwable error) {
                                oldWorkerError.set(error);
                            }
                        });
        oldWorkerThread.start();
        assertThat(llmStarted.await(5, TimeUnit.SECONDS)).isTrue();

        setLeaseUntil(conversationId.asString(), Instant.now().minusSeconds(10));
        CountDownLatch newerLlmStarted = new CountDownLatch(1);
        CountDownLatch finishNewerLlm = new CountDownLatch(1);
        FakeMemoryReviewLlm newerFakeLlm = new FakeMemoryReviewLlm();
        newerFakeLlm.enqueueFact(
                CompanionIdentity.YANHUO, "pref.tea", "用户更喜欢喝碧螺春", 0.9);
        MemoryReviewLlm delayedNewerLlm =
                request -> {
                    newerLlmStarted.countDown();
                    try {
                        if (!finishNewerLlm.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("new test LLM release timed out");
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("new test LLM interrupted", ex);
                    }
                    return newerFakeLlm.propose(request);
                };
        InProcessMemoryReviewWorker restartedWorker =
                new InProcessMemoryReviewWorker(
                        dataSource,
                        conversationStore,
                        memoryStore,
                        delayedNewerLlm,
                        reviewBatchApplier);
        AtomicReference<Throwable> restartedWorkerError = new AtomicReference<>();
        Thread restartedWorkerThread =
                new Thread(
                        () -> {
                            try {
                                restartedWorker.pollOnce();
                            } catch (Throwable error) {
                                restartedWorkerError.set(error);
                            }
                        });
        restartedWorkerThread.start();
        assertThat(newerLlmStarted.await(5, TimeUnit.SECONDS)).isTrue();

        finishLlm.countDown();
        oldWorkerThread.join(5000);
        assertThat(oldWorkerThread.isAlive()).isFalse();
        assertThat(memoryStore.listActive(CompanionIdentity.YANHUO)).isEmpty();
        finishNewerLlm.countDown();
        restartedWorkerThread.join(5000);
        assertThat(restartedWorkerThread.isAlive()).isFalse();
        assertThat(oldWorkerError.get()).isNull();
        assertThat(restartedWorkerError.get()).isNull();
        assertThat(memoryStore.listActive(CompanionIdentity.YANHUO))
                .singleElement()
                .extracting(StoredMemoryRecord::claim)
                .isEqualTo("用户更喜欢喝碧螺春");
        assertThat(countJobs(conversationId.asString(), "SUCCEEDED")).isEqualTo(1);
        assertThat(jobAttempt(conversationId.asString())).isEqualTo(2);
    }

    @Test
    void idleScannerEnqueuesWhenIdleAndNoPriorReview() throws Exception {
        ConversationId conversationId = ConversationId.generate();
        conversationStore.create(CreateConversationCommand.of(conversationId));
        Instant old = Instant.now().minus(MemoryReviewConstants.IDLE_THRESHOLD).minusSeconds(60);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                "UPDATE conversation SET last_activity_at = ? WHERE id = ?")) {
            ps.setString(1, old.toString());
            ps.setString(2, conversationId.asString());
            ps.executeUpdate();
        }

        int n = idleScanner.scanOnce(Instant.now());
        assertThat(n).isEqualTo(1);
        assertThat(jobTrigger(conversationId.asString())).isEqualTo("IDLE");
    }

    @Test
    void idleDeadJobSuppressesSameActivityWatermarkAcrossTicksButNewActivityCanEnqueue()
            throws Exception {
        ConversationId conversationId = ConversationId.generate();
        conversationStore.create(CreateConversationCommand.of(conversationId));
        Instant activity = Instant.now().minus(MemoryReviewConstants.IDLE_THRESHOLD).minusSeconds(90);
        setLastActivity(conversationId.asString(), activity);

        assertThat(idleScanner.scanOnce(Instant.now())).isEqualTo(1);
        setOnlyJobStatus(conversationId.asString(), "DEAD");
        idleScanner.scanOnce(Instant.now().plusSeconds(1));
        assertThat(countJobs(conversationId.asString(), "DEAD")).isEqualTo(1);
        assertThat(countIdleJobs(conversationId.asString())).isEqualTo(1);

        Instant laterActivity = Instant.now().minus(MemoryReviewConstants.IDLE_THRESHOLD).minusSeconds(5);
        setLastActivity(conversationId.asString(), laterActivity);
        assertThat(idleScanner.scanOnce(Instant.now())).isEqualTo(1);
        assertThat(countIdleJobs(conversationId.asString())).isEqualTo(2);
    }

    @Test
    void activityDuringReviewRemainsNewAfterJobSucceeds() throws Exception {
        ConversationId conversationId = ConversationId.generate();
        conversationStore.create(CreateConversationCommand.of(conversationId));
        Instant observedActivity =
                Instant.now().minus(MemoryReviewConstants.IDLE_THRESHOLD).minusSeconds(90);
        setLastActivity(conversationId.asString(), observedActivity);
        scheduler.enqueue(
                conversationId.asString(), CompanionIdentity.YANHUO, MemoryReviewScheduler.Trigger.INTERVAL);

        CountDownLatch llmStarted = new CountDownLatch(1);
        CountDownLatch finishLlm = new CountDownLatch(1);
        MemoryReviewLlm delayedLlm =
                request -> {
                    llmStarted.countDown();
                    try {
                        if (!finishLlm.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test LLM release timed out");
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("test LLM interrupted", ex);
                    }
                    return List.of();
                };
        InProcessMemoryReviewWorker delayedWorker =
                new InProcessMemoryReviewWorker(
                        dataSource, conversationStore, memoryStore, delayedLlm, reviewBatchApplier);
        AtomicReference<Throwable> workerError = new AtomicReference<>();
        Thread workerThread =
                new Thread(
                        () -> {
                            try {
                                delayedWorker.pollOnce();
                            } catch (Throwable error) {
                                workerError.set(error);
                            }
                        });
        workerThread.start();
        assertThat(llmStarted.await(5, TimeUnit.SECONDS)).isTrue();

        Instant activityDuringReview =
                Instant.now().minus(MemoryReviewConstants.IDLE_THRESHOLD).minusSeconds(5);
        setLastActivity(conversationId.asString(), activityDuringReview);
        finishLlm.countDown();
        workerThread.join(5_000);

        assertThat(workerThread.isAlive()).isFalse();
        assertThat(workerError.get()).isNull();
        assertThat(countJobs(conversationId.asString(), "SUCCEEDED")).isEqualTo(1);
        assertThat(reviewedActivityWatermark(conversationId.asString()))
                .isEqualTo(observedActivity.toString());
        assertThat(idleScanner.scanOnce(Instant.now())).isEqualTo(1);
        assertThat(countIdleJobs(conversationId.asString())).isEqualTo(1);
    }

    @Test
    void reviewBatchRollsBackWithFailedCompletionAndReplaysCompletedJobAsNoOp() throws Exception {
        ConversationId conversationId = ConversationId.generate();
        conversationStore.create(CreateConversationCommand.of(conversationId));
        scheduler.enqueue(
                conversationId.asString(), CompanionIdentity.YANHUO, MemoryReviewScheduler.Trigger.INTERVAL);
        String owner = "test-review-owner";
        setRunningLease(
                conversationId.asString(), 1, owner, Instant.now().plus(MemoryReviewConstants.LEASE_DURATION));
        String jobId = onlyJobId(conversationId.asString());
        ApprovedMemoryChange first =
                new ApprovedMemoryChange(
                        CompanionIdentity.YANHUO,
                        "pref.tea",
                        "用户喜欢喝龙井",
                        com.wannian.server.kernel.memory.ContentKind.USER_PREFERENCE,
                        com.wannian.server.kernel.memory.SourceKind.OBSERVED,
                        com.wannian.server.kernel.memory.MemoryScope.COMPANION,
                        0.8,
                        null,
                        ApprovedMemoryChange.PROPOSE_LLM_REVIEW)
                        .withExpectedGeneration(0);

        assertThat(reviewBatchApplier.applyAndComplete(
                        new MemoryReviewLease(jobId, "stale-owner"), List.of(first)))
                .isInstanceOf(MemoryReviewBatchApplier.ApplyResult.LeaseLost.class);
        assertThat(memoryStore.listActive(CompanionIdentity.YANHUO)).isEmpty();

        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement()
                    .execute(
                            """
                            CREATE TRIGGER fail_review_completion
                            BEFORE UPDATE OF status ON memory_review_job
                            WHEN NEW.status = 'SUCCEEDED'
                            BEGIN SELECT RAISE(ABORT, 'injected completion failure'); END
                            """);
        }
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> reviewBatchApplier.applyAndComplete(
                                    new MemoryReviewLease(jobId, owner), List.of(first)))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            try (Connection connection = dataSource.getConnection()) {
                connection.createStatement().execute("DROP TRIGGER fail_review_completion");
            }
        }
        assertThat(memoryStore.listActive(CompanionIdentity.YANHUO)).isEmpty();
        assertThat(countJobs(conversationId.asString(), "RUNNING")).isEqualTo(1);
        assertThat(countAppliedDrafts(jobId)).isZero();

        assertThat(reviewBatchApplier.applyAndComplete(
                        new MemoryReviewLease(jobId, owner), List.of(first, first)))
                .isEqualTo(new MemoryReviewBatchApplier.ApplyResult.Completed(false));
        ApprovedMemoryChange changedRetryOutput =
                new ApprovedMemoryChange(
                        CompanionIdentity.YANHUO,
                        "pref.tea",
                        "用户更喜欢喝碧螺春",
                        com.wannian.server.kernel.memory.ContentKind.USER_PREFERENCE,
                        com.wannian.server.kernel.memory.SourceKind.OBSERVED,
                        com.wannian.server.kernel.memory.MemoryScope.COMPANION,
                        0.9,
                        null,
                        ApprovedMemoryChange.PROPOSE_LLM_REVIEW);
        assertThat(reviewBatchApplier.applyAndComplete(
                        new MemoryReviewLease(jobId, "stale-owner"), List.of(changedRetryOutput)))
                .isEqualTo(new MemoryReviewBatchApplier.ApplyResult.Completed(true));
        assertThat(memoryStore.listActive(CompanionIdentity.YANHUO))
                .singleElement()
                .extracting(StoredMemoryRecord::claim)
                .isEqualTo("用户喜欢喝龙井");
        assertThat(countJobs(conversationId.asString(), "SUCCEEDED")).isEqualTo(1);
        assertThat(countAppliedDrafts(jobId)).isEqualTo(1);
    }

    @Test
    void reviewBatchSkipsEveryConflictingDraftForSameSubjectButAppliesOtherSubjects()
            throws Exception {
        ConversationId conversationId = ConversationId.generate();
        conversationStore.create(CreateConversationCommand.of(conversationId));
        scheduler.enqueue(
                conversationId.asString(),
                CompanionIdentity.YANHUO,
                MemoryReviewScheduler.Trigger.INTERVAL);
        String owner = "conflict-review-owner";
        setRunningLease(
                conversationId.asString(),
                1,
                owner,
                Instant.now().plus(MemoryReviewConstants.LEASE_DURATION));
        String jobId = onlyJobId(conversationId.asString());

        ApprovedMemoryChange firstClaim = reviewMemory("pref.tea", "用户喜欢龙井");
        ApprovedMemoryChange competingClaim = reviewMemory("pref.tea", "用户喜欢碧螺春");
        ApprovedMemoryChange unrelatedClaim = reviewMemory("pref.movie", "用户喜欢科幻片");

        assertThat(reviewBatchApplier.applyAndComplete(
                        new MemoryReviewLease(jobId, owner),
                        List.of(firstClaim, competingClaim, unrelatedClaim)))
                .isEqualTo(new MemoryReviewBatchApplier.ApplyResult.Completed(false));

        assertThat(memoryStore.listActive(CompanionIdentity.YANHUO))
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.subjectKey()).isEqualTo("pref.movie");
                            assertThat(row.claim()).isEqualTo("用户喜欢科幻片");
                        });
        assertThat(countAppliedDrafts(jobId)).isEqualTo(1);
        assertThat(countJobs(conversationId.asString(), "SUCCEEDED")).isEqualTo(1);
    }

    private static ApprovedMemoryChange reviewMemory(String subjectKey, String claim) {
        return new ApprovedMemoryChange(
                CompanionIdentity.YANHUO,
                subjectKey,
                claim,
                com.wannian.server.kernel.memory.ContentKind.USER_PREFERENCE,
                com.wannian.server.kernel.memory.SourceKind.OBSERVED,
                com.wannian.server.kernel.memory.MemoryScope.COMPANION,
                0.8,
                null,
                ApprovedMemoryChange.PROPOSE_LLM_REVIEW)
                .withExpectedGeneration(0);
    }

    private void completeOneTurn(ConversationId conversationId, String text) throws Exception {
        TurnId turnId = TurnId.generate();
        MessageId userMessageId = MessageId.generate();
        turnCommitter.receive(
                new ReceiveTurnPlan(
                        conversationId,
                        "req-" + UUID.randomUUID(),
                        turnId,
                        new ReceiveTurnPlan.UserMessageDraft(
                                userMessageId,
                                MessageRole.USER,
                                "{\"v\":1,\"text\":\"" + text + "\"}",
                                0)));
        Instant now = Instant.now();
        ExecutionClaim claim = ExecutionClaim.attempt(now.plusSeconds(60));

        Turn turn = turnRepository.find(turnId).orElseThrow();
        long revision = turn.revision();
        turn.claim(revision, claim, now);
        turnRepository.save(turn, revision, now);

        turn = turnRepository.find(turnId).orElseThrow();
        revision = turn.revision();
        turn.start(now);
        turnRepository.save(turn, revision, now);

        turn = turnRepository.find(turnId).orElseThrow();
        revision = turn.revision();
        MessageId assistantId = MessageId.generate();
        var assistant =
                new CommitTurnPlan.AssistantMessageDraft(
                        assistantId, MessageRole.ASSISTANT, "{\"v\":1,\"text\":\"ok\"}", 0);
        turnCommitter.freezeCommit(
                FreezeCommitPlan.of(
                        turnId,
                        revision,
                        claim.executionId(),
                        now,
                        assistant,
                        List.of(),
                        List.of(),
                        null));
        turnCommitter.commit(turnCommitter.frozenCommitPlan(turnId).orElseThrow());
    }

    private long countJobs(String conversationId, String status) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                """
                                SELECT COUNT(*) FROM memory_review_job
                                WHERE conversation_id = ? AND status = ?
                                """)) {
            ps.setString(1, conversationId);
            ps.setString(2, status);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private String jobTrigger(String conversationId) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                """
                                SELECT trigger FROM memory_review_job
                                WHERE conversation_id = ?
                                ORDER BY created_at DESC LIMIT 1
                                """)) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    private long countIdleJobs(String conversationId) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                "SELECT COUNT(*) FROM memory_review_job WHERE conversation_id = ? AND trigger = 'IDLE'")) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private int jobAttempt(String conversationId) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                "SELECT attempt FROM memory_review_job WHERE conversation_id = ? ORDER BY created_at DESC LIMIT 1")) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getInt(1);
            }
        }
    }

    private String reviewedActivityWatermark(String conversationId) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                "SELECT reviewed_activity_watermark FROM memory_review_job WHERE conversation_id = ?")) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    private String onlyJobId(String conversationId) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                "SELECT id FROM memory_review_job WHERE conversation_id = ?")) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                String id = rs.getString(1);
                assertThat(rs.next()).isFalse();
                return id;
            }
        }
    }

    private long countAppliedDrafts(String jobId) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                "SELECT COUNT(*) FROM memory_review_job_apply WHERE job_id = ?")) {
            ps.setString(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void setRunningLease(String conversationId, int attempt, String owner, Instant until)
            throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                "UPDATE memory_review_job SET status = 'RUNNING', attempt = ?, lease_owner = ?, lease_until = ? WHERE conversation_id = ?")) {
            ps.setInt(1, attempt);
            ps.setString(2, owner);
            ps.setString(3, until.toString());
            ps.setString(4, conversationId);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }

    private void setOnlyJobStatus(String conversationId, String status) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                "UPDATE memory_review_job SET status = ?, lease_owner = NULL, lease_until = NULL WHERE conversation_id = ?")) {
            ps.setString(1, status);
            ps.setString(2, conversationId);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }

    private void setLeaseUntil(String conversationId, Instant leaseUntil) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                "UPDATE memory_review_job SET lease_until = ? WHERE conversation_id = ?")) {
            ps.setString(1, leaseUntil.toString());
            ps.setString(2, conversationId);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }

    private void setLastActivity(String conversationId, Instant activity) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                "UPDATE conversation SET last_activity_at = ? WHERE id = ?")) {
            ps.setString(1, activity.toString());
            ps.setString(2, conversationId);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }
}
