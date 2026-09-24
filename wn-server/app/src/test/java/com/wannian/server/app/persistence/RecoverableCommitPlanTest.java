package com.wannian.server.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.conversation.MessageRole;
import com.wannian.server.api.turn.TurnStatus;
import com.wannian.server.kernel.conversation.ConversationStore;
import com.wannian.server.kernel.conversation.CreateConversationCommand;
import com.wannian.server.kernel.conversation.CreateConversationResult;
import com.wannian.server.kernel.memory.ApprovedMemoryChange;
import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.memory.ContentKind;
import com.wannian.server.kernel.memory.MemoryScope;
import com.wannian.server.kernel.memory.SourceKind;
import com.wannian.server.kernel.relationship.ApprovedRelationshipChange;
import com.wannian.server.kernel.turn.CommitTurnPlan;
import com.wannian.server.kernel.turn.CommitTurnResult;
import com.wannian.server.kernel.turn.ExecutionClaim;
import com.wannian.server.kernel.turn.FreezeCommitPlan;
import com.wannian.server.kernel.turn.FreezeCommitResult;
import com.wannian.server.kernel.turn.ReceiveTurnPlan;
import com.wannian.server.kernel.turn.ReceiveTurnResult;
import com.wannian.server.kernel.turn.SaveTurnResult;
import com.wannian.server.kernel.turn.Turn;
import com.wannian.server.kernel.turn.TurnCommitter;
import com.wannian.server.kernel.turn.TurnRepository;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 32 号 R03：冻结计划后丢掉内存对象，重加载只提交同一份答案。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RecoverableCommitPlanTest {

    @TempDir
    static Path tempDataDir;

    @DynamicPropertySource
    static void registerDataDir(DynamicPropertyRegistry registry) {
        registry.add("wannian.data-dir", () -> tempDataDir.toAbsolutePath().toString());
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ConversationStore conversationStore;

    @Autowired
    private TurnCommitter turnCommitter;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TurnRepository turnRepository;

    @BeforeEach
    void clearTables() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().executeUpdate("DELETE FROM outbox_event");
            connection.createStatement().executeUpdate("DELETE FROM memory_subject_generation");
            connection.createStatement().executeUpdate("DELETE FROM memory_record");
            connection.createStatement().executeUpdate("DELETE FROM turn_commit_plan");
            connection.createStatement().executeUpdate("DELETE FROM turn_step");
            connection.createStatement().executeUpdate("DELETE FROM turn");
            connection.createStatement().executeUpdate("DELETE FROM message");
            connection.createStatement().executeUpdate("DELETE FROM conversation");
        }
    }

    @Test
    void reloadFrozenPlanThenCommitOnce() throws Exception {
        Instant now = Instant.parse("2026-09-18T12:00:00Z");
        ConversationId conversationId = ConversationId.generate();
        assertThat(conversationStore.create(CreateConversationCommand.of(conversationId)))
                .isInstanceOf(CreateConversationResult.Created.class);
        TurnId turnId = TurnId.generate();
        assertThat(
                        turnCommitter.receive(
                                new ReceiveTurnPlan(
                                        conversationId,
                                        "recover-1",
                                        turnId,
                                        new ReceiveTurnPlan.UserMessageDraft(
                                                MessageId.generate(),
                                                MessageRole.USER,
                                                "{\"v\":1,\"text\":\"恢复\"}",
                                                0))))
                .isInstanceOf(ReceiveTurnResult.Accepted.class);

        Turn turn = turnRepository.find(turnId).orElseThrow();
        long revision = turn.revision();
        turn.claim(revision, new ExecutionClaim("owner-a", now.plusSeconds(60)), now);
        assertThat(turnRepository.save(turn, revision, now)).isInstanceOf(SaveTurnResult.Saved.class);
        turn = turnRepository.find(turnId).orElseThrow();
        revision = turn.revision();
        turn.start(now);
        assertThat(turnRepository.save(turn, revision, now)).isInstanceOf(SaveTurnResult.Saved.class);
        turn = turnRepository.find(turnId).orElseThrow();
        revision = turn.revision();

        MessageId assistantId = MessageId.generate();
        var assistant =
                new CommitTurnPlan.AssistantMessageDraft(
                        assistantId, MessageRole.ASSISTANT, "{\"v\":1,\"text\":\"冻结答案\"}", 0);
        FreezeCommitResult frozen =
                turnCommitter.freezeCommit(
                        FreezeCommitPlan.of(turnId, revision, "owner-a", now, assistant, List.of(), List.of(), null));
        assertThat(frozen).isInstanceOf(FreezeCommitResult.Frozen.class);

        // 丢掉全部内存对象，只保留 turnId，模拟进程退出后重加载。
        CommitTurnPlan recovered = turnCommitter.frozenCommitPlan(turnId).orElseThrow();
        assertThat(recovered.expectedExecutionId()).isEqualTo("owner-a");
        assertThat(recovered.assistantMessage().messageId()).isEqualTo(assistantId);
        assertThat(recovered.assistantMessage().contentJson()).isEqualTo("{\"v\":1,\"text\":\"冻结答案\"}");

        assertThat(turnCommitter.commit(recovered)).isInstanceOf(CommitTurnResult.Committed.class);

        try (Connection connection = dataSource.getConnection()) {
            assertThat(scalar(connection, "SELECT status FROM turn WHERE id = ?", turnId.asString()))
                    .isEqualTo(TurnStatus.COMPLETED.name());
            assertThat(count(connection, "SELECT COUNT(*) FROM message WHERE role = 'ASSISTANT'"))
                    .isEqualTo(1);
            assertThat(count(connection, "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'TurnCompleted'"))
                    .isEqualTo(1);
            assertThat(count(connection, "SELECT COUNT(*) FROM turn_commit_plan")).isZero();
        }

        CommitTurnResult replay =
                turnCommitter.commit(
                        CommitTurnPlan.completeTurn(
                                turnId,
                                1L,
                                "owner-a",
                                new CommitTurnPlan.AssistantMessageDraft(
                                        MessageId.generate(), MessageRole.ASSISTANT, "{\"v\":1,\"text\":\"另一份\"}", 0),
                                List.of(), List.of(), null));
        assertThat(replay).isInstanceOf(CommitTurnResult.Committed.class);
        try (Connection connection = dataSource.getConnection()) {
            assertThat(count(connection, "SELECT COUNT(*) FROM message WHERE role = 'ASSISTANT'"))
                    .isEqualTo(1);
            assertThat(count(connection, "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'TurnCompleted'"))
                    .isEqualTo(1);
        }
    }

    @Test
    void upgradesAndRecoversLegacyV1FrozenPlan() throws Exception {
        Instant now = Instant.parse("2026-09-18T12:00:00Z");
        FrozenTurn frozen = freezeRunning("legacy-v1", now, "{\"v\":1,\"text\":\"旧版恢复\"}");
        var legacy = objectMapper.createObjectNode();
        legacy.put("v", 1);
        legacy.put("executionId", frozen.plan().expectedExecutionId());
        legacy.put("assistantMessageId", frozen.plan().assistantMessage().messageId().asString());
        legacy.put("assistantRole", frozen.plan().assistantMessage().role().name());
        legacy.put("assistantContentJson", frozen.plan().assistantMessage().contentJson());
        legacy.putArray("additionalEvents");
        // v1 predates memory and relationship changes. Their absence must decode as empty/null.
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                "UPDATE turn_commit_plan SET format_version = 1, plan_json = ? WHERE turn_id = ?")) {
            ps.setString(1, objectMapper.writeValueAsString(legacy));
            ps.setString(2, frozen.turnId().asString());
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }

        CommitTurnPlan recovered = turnCommitter.frozenCommitPlan(frozen.turnId()).orElseThrow();
        assertThat(recovered.expectedExecutionId()).isEqualTo("owner-a");
        assertThat(recovered.assistantMessage()).isEqualTo(frozen.plan().assistantMessage());
        assertThat(recovered.approvedMemoryChanges()).isEmpty();
        assertThat(recovered.approvedRelationshipChange()).isNull();
        assertThat(turnCommitter.commit(recovered)).isInstanceOf(CommitTurnResult.Committed.class);
        try (Connection connection = dataSource.getConnection()) {
            assertThat(scalar(connection, "SELECT status FROM turn WHERE id = ?", frozen.turnId().asString()))
                    .isEqualTo(TurnStatus.COMPLETED.name());
            assertThat(count(connection, "SELECT COUNT(*) FROM message WHERE role = 'ASSISTANT'"))
                    .isEqualTo(1);
            assertThat(count(connection, "SELECT COUNT(*) FROM turn_commit_plan")).isZero();
        }
    }

    @Test
    void currentFrozenPlanRoundTripsMemoryGenerationAndRelationshipChanges() throws Exception {
        Instant now = Instant.parse("2026-09-18T12:00:00Z");
        TurnId turnId = seedRunning("v2-roundtrip", now);
        Turn turn = turnRepository.find(turnId).orElseThrow();
        var assistant =
                new CommitTurnPlan.AssistantMessageDraft(
                        MessageId.generate(), MessageRole.ASSISTANT, "{\"v\":1,\"text\":\"记住\"}", 0);
        var memory =
                new ApprovedMemoryChange(
                        CompanionIdentity.YANHUO,
                        "pref.tea",
                        "用户喜欢龙井",
                        ContentKind.USER_PREFERENCE,
                        SourceKind.EXPLICIT,
                        MemoryScope.COMPANION,
                        0.82,
                        null,
                        ApprovedMemoryChange.PROPOSE_TOOL_REMEMBER)
                        .withExpectedGeneration(0);
        var relationship =
                new ApprovedRelationshipChange(
                        CompanionIdentity.YANHUO,
                        "小年",
                        "不主动透露隐私",
                        "用户明确要求",
                        ApprovedRelationshipChange.PROPOSE_TOOL_RELATIONSHIP);
        FreezeCommitResult frozen =
                turnCommitter.freezeCommit(
                        FreezeCommitPlan.of(
                                turnId,
                                turn.revision(),
                                "owner-a",
                                now,
                                assistant,
                                List.of(),
                                List.of(memory),
                                relationship));
        assertThat(frozen).isInstanceOf(FreezeCommitResult.Frozen.class);

        CommitTurnPlan recovered = turnCommitter.frozenCommitPlan(turnId).orElseThrow();
        assertThat(recovered.approvedMemoryChanges()).containsExactly(memory);
        assertThat(recovered.approvedRelationshipChange()).isEqualTo(relationship);
        assertThat(turnCommitter.commit(recovered)).isInstanceOf(CommitTurnResult.Committed.class);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT generation FROM memory_subject_generation WHERE companion_identity_id = ? AND subject_key = ?")) {
            ps.setString(1, CompanionIdentity.YANHUO.value());
            ps.setString(2, "pref.tea");
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isEqualTo(1L);
            }
        }
    }

    @Test
    void legacyV2PlanWithoutGenerationSkipsMemoryButStillRecoversTurn() throws Exception {
        Instant now = Instant.parse("2026-09-18T12:00:00Z");
        FrozenTurn frozen = freezeRunning("legacy-v2-memory", now, "{\"v\":1,\"text\":\"恢复答案\"}");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement select = connection.prepareStatement(
                        "SELECT plan_json FROM turn_commit_plan WHERE turn_id = ?")) {
            select.setString(1, frozen.turnId().asString());
            String json;
            try (ResultSet rs = select.executeQuery()) {
                assertThat(rs.next()).isTrue();
                ObjectNode root = (ObjectNode) objectMapper.readTree(rs.getString(1));
                root.put("v", 2);
                var memoryNode = root.withArray("approvedMemoryChanges").addObject();
                memoryNode.put("companionIdentity", CompanionIdentity.YANHUO.value());
                memoryNode.put("subjectKey", "pref.tea");
                memoryNode.put("claim", "未带代次的历史草案");
                memoryNode.put("contentKind", ContentKind.USER_PREFERENCE.name());
                memoryNode.put("sourceKind", SourceKind.EXPLICIT.name());
                memoryNode.put("scope", MemoryScope.COMPANION.name());
                memoryNode.put("importance", 0.8);
                memoryNode.putNull("path");
                memoryNode.put("proposeId", ApprovedMemoryChange.PROPOSE_TOOL_REMEMBER);
                json = objectMapper.writeValueAsString(root);
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE turn_commit_plan SET format_version = 2, plan_json = ? WHERE turn_id = ?")) {
                update.setString(1, json);
                update.setString(2, frozen.turnId().asString());
                assertThat(update.executeUpdate()).isEqualTo(1);
            }
        }

        CommitTurnPlan recovered = turnCommitter.frozenCommitPlan(frozen.turnId()).orElseThrow();
        assertThat(recovered.approvedMemoryChanges()).singleElement()
                .extracting(ApprovedMemoryChange::expectedGeneration)
                .isNull();
        assertThat(turnCommitter.commit(recovered)).isInstanceOf(CommitTurnResult.Committed.class);
        try (Connection connection = dataSource.getConnection()) {
            assertThat(count(connection, "SELECT COUNT(*) FROM memory_record")).isZero();
            assertThat(scalar(connection, "SELECT status FROM turn WHERE id = ?", frozen.turnId().asString()))
                    .isEqualTo(TurnStatus.COMPLETED.name());
        }
    }

    @Test
    void rejectsMalformedV2FrozenPlanInsteadOfSilentlyDroppingMemory() throws Exception {
        Instant now = Instant.parse("2026-09-18T12:00:00Z");
        FrozenTurn frozen = freezeRunning("malformed-v2", now, "{\"v\":1,\"text\":\"坏计划\"}");
        var malformed = objectMapper.createObjectNode();
        malformed.put("v", 2);
        malformed.put("executionId", frozen.plan().expectedExecutionId());
        malformed.put("assistantMessageId", frozen.plan().assistantMessage().messageId().asString());
        malformed.put("assistantRole", frozen.plan().assistantMessage().role().name());
        malformed.put("assistantContentJson", frozen.plan().assistantMessage().contentJson());
        malformed.putArray("additionalEvents");
        var memories = malformed.putArray("approvedMemoryChanges");
        memories.addObject().put("subjectKey", "pref.tea"); // importance and other required fields are absent
        malformed.putNull("approvedRelationshipChange");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement("UPDATE turn_commit_plan SET plan_json = ? WHERE turn_id = ?")) {
            ps.setString(1, objectMapper.writeValueAsString(malformed));
            ps.setString(2, frozen.turnId().asString());
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }

        assertThat(turnCommitter.frozenCommitPlan(frozen.turnId())).isEmpty();
        CommitTurnResult rejected = turnCommitter.commit(frozen.plan());
        assertThat(rejected).isInstanceOf(CommitTurnResult.Rejected.class);
        assertThat(((CommitTurnResult.Rejected) rejected).reasonCode()).isEqualTo("PERSISTENCE_FAILED");
        try (Connection connection = dataSource.getConnection()) {
            assertThat(scalar(connection, "SELECT status FROM turn WHERE id = ?", frozen.turnId().asString()))
                    .isEqualTo(TurnStatus.COMMITTING.name());
            assertThat(count(connection, "SELECT COUNT(*) FROM message WHERE role = 'ASSISTANT'"))
                    .isZero();
            assertThat(count(connection, "SELECT COUNT(*) FROM turn_commit_plan")).isEqualTo(1);
        }
    }

    @Test
    void wrongOwnerCannotCommitFrozenPlan() throws Exception {
        Instant now = Instant.parse("2026-09-18T12:00:00Z");
        ConversationId conversationId = ConversationId.generate();
        assertThat(conversationStore.create(CreateConversationCommand.of(conversationId)))
                .isInstanceOf(CreateConversationResult.Created.class);
        TurnId turnId = TurnId.generate();
        turnCommitter.receive(
                new ReceiveTurnPlan(
                        conversationId,
                        "recover-2",
                        turnId,
                        new ReceiveTurnPlan.UserMessageDraft(
                                MessageId.generate(), MessageRole.USER, "{\"v\":1,\"text\":\"x\"}", 0)));
        Turn turn = turnRepository.find(turnId).orElseThrow();
        turn.claim(1L, new ExecutionClaim("owner-a", now.plusSeconds(60)), now);
        turnRepository.save(turn, 1L, now);
        turn = turnRepository.find(turnId).orElseThrow();
        turn.start(now);
        turnRepository.save(turn, 2L, now);
        var assistant =
                new CommitTurnPlan.AssistantMessageDraft(
                        MessageId.generate(), MessageRole.ASSISTANT, "{\"v\":1,\"text\":\"答\"}", 0);
        assertThat(
                        turnCommitter.freezeCommit(
                                FreezeCommitPlan.of(turnId, 3L, "owner-a", now, assistant, List.of(), List.of(), null)))
                .isInstanceOf(FreezeCommitResult.Frozen.class);

        CommitTurnPlan recovered = turnCommitter.frozenCommitPlan(turnId).orElseThrow();
        CommitTurnResult rejected =
                turnCommitter.commit(
                        CommitTurnPlan.completeTurn(
                                turnId,
                                recovered.expectedTurnRevision(),
                                "someone-else",
                                recovered.assistantMessage(),
                                List.of(), List.of(), null));
        assertThat(rejected).isInstanceOf(CommitTurnResult.Rejected.class);
        assertThat(((CommitTurnResult.Rejected) rejected).reasonCode()).isEqualTo("OWNER_MISMATCH");
        try (Connection connection = dataSource.getConnection()) {
            assertThat(scalar(connection, "SELECT status FROM turn WHERE id = ?", turnId.asString()))
                    .isEqualTo(TurnStatus.COMMITTING.name());
            assertThat(count(connection, "SELECT COUNT(*) FROM message WHERE role = 'ASSISTANT'")).isZero();
            assertThat(count(connection, "SELECT COUNT(*) FROM outbox_event")).isZero();
            assertThat(count(connection, "SELECT COUNT(*) FROM turn_commit_plan")).isEqualTo(1);
        }
    }

    @Test
    void genericSaveCannotEnterCommittingWithoutPlan() throws Exception {
        Instant now = Instant.parse("2026-09-18T12:00:00Z");
        ConversationId conversationId = ConversationId.generate();
        conversationStore.create(CreateConversationCommand.of(conversationId));
        TurnId turnId = TurnId.generate();
        turnCommitter.receive(
                new ReceiveTurnPlan(
                        conversationId,
                        "recover-3",
                        turnId,
                        new ReceiveTurnPlan.UserMessageDraft(
                                MessageId.generate(), MessageRole.USER, "{\"v\":1,\"text\":\"y\"}", 0)));
        Turn turn = turnRepository.find(turnId).orElseThrow();
        turn.claim(1L, new ExecutionClaim("owner-a", now.plusSeconds(60)), now);
        turnRepository.save(turn, 1L, now);
        turn = turnRepository.find(turnId).orElseThrow();
        turn.start(now);
        turnRepository.save(turn, 2L, now);
        turn = turnRepository.find(turnId).orElseThrow();
        turn.beginCommit(now);
        SaveTurnResult rejected = turnRepository.save(turn, 3L, now);
        assertThat(rejected).isInstanceOf(SaveTurnResult.Rejected.class);
        assertThat(((SaveTurnResult.Rejected) rejected).reasonCode()).isEqualTo("ILLEGAL_TRANSITION");
        try (Connection connection = dataSource.getConnection()) {
            assertThat(scalar(connection, "SELECT status FROM turn WHERE id = ?", turnId.asString()))
                    .isEqualTo(TurnStatus.RUNNING.name());
            assertThat(count(connection, "SELECT COUNT(*) FROM turn_commit_plan")).isZero();
        }
    }

    @Test
    void commitSucceedsAfterFrozenLeaseExpires() throws Exception {
        Instant now = Instant.parse("2026-09-18T12:00:00Z");
        FrozenTurn frozen = freezeRunning("lease-after", now, "{\"v\":1,\"text\":\"仍可提交\"}");

        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                "UPDATE turn SET claim_expires_at = ? WHERE id = ?")) {
            ps.setString(1, "2000-01-01T00:00:00Z");
            ps.setString(2, frozen.turnId().asString());
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }

        assertThat(turnCommitter.commit(frozen.plan())).isInstanceOf(CommitTurnResult.Committed.class);
        try (Connection connection = dataSource.getConnection()) {
            assertThat(scalar(connection, "SELECT status FROM turn WHERE id = ?", frozen.turnId().asString()))
                    .isEqualTo(TurnStatus.COMPLETED.name());
            assertThat(count(connection, "SELECT COUNT(*) FROM message WHERE role = 'ASSISTANT'"))
                    .isEqualTo(1);
            assertThat(count(connection, "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'TurnCompleted'"))
                    .isEqualTo(1);
        }
    }

    @Test
    void rejectsCommitWhenAssistantTextDiffersFromFrozenPlan() throws Exception {
        Instant now = Instant.parse("2026-09-18T12:00:00Z");
        FrozenTurn frozen = freezeRunning("mismatch-text", now, "{\"v\":1,\"text\":\"冻结\"}");
        CommitTurnPlan recovered = frozen.plan();
        CommitTurnResult rejected =
                turnCommitter.commit(
                        CommitTurnPlan.completeTurn(
                                frozen.turnId(),
                                recovered.expectedTurnRevision(),
                                recovered.expectedExecutionId(),
                                new CommitTurnPlan.AssistantMessageDraft(
                                        recovered.assistantMessage().messageId(),
                                        MessageRole.ASSISTANT,
                                        "{\"v\":1,\"text\":\"另一份\"}",
                                        0),
                                List.of(), List.of(), null));
        assertThat(rejected).isInstanceOf(CommitTurnResult.Rejected.class);
        assertThat(((CommitTurnResult.Rejected) rejected).reasonCode()).isEqualTo("PLAN_MISMATCH");
        try (Connection connection = dataSource.getConnection()) {
            assertThat(scalar(connection, "SELECT status FROM turn WHERE id = ?", frozen.turnId().asString()))
                    .isEqualTo(TurnStatus.COMMITTING.name());
            assertThat(count(connection, "SELECT COUNT(*) FROM message WHERE role = 'ASSISTANT'")).isZero();
            assertThat(count(connection, "SELECT COUNT(*) FROM outbox_event")).isZero();
            assertThat(count(connection, "SELECT COUNT(*) FROM turn_commit_plan")).isEqualTo(1);
        }
    }

    @Test
    void rejectsCommitWhenCommittingRowHasNoPlan() throws Exception {
        Instant now = Instant.parse("2026-09-18T12:00:00Z");
        FrozenTurn frozen = freezeRunning("missing-plan", now, "{\"v\":1,\"text\":\"丢计划\"}");
        CommitTurnPlan recovered = frozen.plan();

        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement("DELETE FROM turn_commit_plan WHERE turn_id = ?")) {
            ps.setString(1, frozen.turnId().asString());
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }

        CommitTurnResult rejected = turnCommitter.commit(recovered);
        assertThat(rejected).isInstanceOf(CommitTurnResult.Rejected.class);
        assertThat(((CommitTurnResult.Rejected) rejected).reasonCode()).isEqualTo("MISSING_COMMIT_PLAN");
        try (Connection connection = dataSource.getConnection()) {
            assertThat(scalar(connection, "SELECT status FROM turn WHERE id = ?", frozen.turnId().asString()))
                    .isEqualTo(TurnStatus.COMMITTING.name());
            assertThat(count(connection, "SELECT COUNT(*) FROM message WHERE role = 'ASSISTANT'")).isZero();
            assertThat(count(connection, "SELECT COUNT(*) FROM outbox_event")).isZero();
        }
    }

    @Test
    void cancelAndFreezeHaveASingleWinner() throws Exception {
        Instant now = Instant.parse("2026-09-18T12:00:00Z");
        TurnId turnId = seedRunning("race-cancel", now);
        var assistant =
                new CommitTurnPlan.AssistantMessageDraft(
                        MessageId.generate(), MessageRole.ASSISTANT, "{\"v\":1,\"text\":\"竞争\"}", 0);
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<FreezeCommitResult> freezeFuture =
                    pool.submit(
                            () -> {
                                barrier.await();
                                Turn turn = turnRepository.find(turnId).orElseThrow();
                                return turnCommitter.freezeCommit(
                                        FreezeCommitPlan.of(
                                                turnId, turn.revision(), "owner-a", now, assistant, List.of(), List.of(), null));
                            });
            java.util.concurrent.Future<SaveTurnResult> cancelFuture =
                    pool.submit(
                            () -> {
                                barrier.await();
                                Turn turn = turnRepository.find(turnId).orElseThrow();
                                long expected = turn.revision();
                                try {
                                    turn.cancel(now);
                                    return turnRepository.save(turn, expected, now);
                                } catch (com.wannian.server.kernel.turn.TurnTransitionException rejected) {
                                    return new SaveTurnResult.Rejected(
                                            turnId, rejected.reasonCode(), rejected.getMessage());
                                }
                            });
            FreezeCommitResult frozen = freezeFuture.get();
            SaveTurnResult cancelled = cancelFuture.get();
            boolean freezeWon = frozen instanceof FreezeCommitResult.Frozen;
            boolean cancelWon = cancelled instanceof SaveTurnResult.Saved;
            assertThat(freezeWon).isNotEqualTo(cancelWon);
            try (Connection connection = dataSource.getConnection()) {
                String status =
                        scalar(connection, "SELECT status FROM turn WHERE id = ?", turnId.asString());
                assertThat(count(connection, "SELECT COUNT(*) FROM message WHERE role = 'ASSISTANT'"))
                        .isZero();
                assertThat(count(connection, "SELECT COUNT(*) FROM outbox_event")).isZero();
                if (freezeWon) {
                    assertThat(status).isEqualTo(TurnStatus.COMMITTING.name());
                    assertThat(count(connection, "SELECT COUNT(*) FROM turn_commit_plan")).isEqualTo(1);
                    assertThat(cancelled).isNotInstanceOf(SaveTurnResult.Saved.class);
                } else {
                    assertThat(status).isEqualTo(TurnStatus.CANCELLED.name());
                    assertThat(count(connection, "SELECT COUNT(*) FROM turn_commit_plan")).isZero();
                    assertThat(frozen).isNotInstanceOf(FreezeCommitResult.Frozen.class);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private TurnId seedRunning(String clientRequestId, Instant now) throws Exception {
        ConversationId conversationId = ConversationId.generate();
        assertThat(conversationStore.create(CreateConversationCommand.of(conversationId)))
                .isInstanceOf(CreateConversationResult.Created.class);
        TurnId turnId = TurnId.generate();
        assertThat(
                        turnCommitter.receive(
                                new ReceiveTurnPlan(
                                        conversationId,
                                        clientRequestId,
                                        turnId,
                                        new ReceiveTurnPlan.UserMessageDraft(
                                                MessageId.generate(),
                                                MessageRole.USER,
                                                "{\"v\":1,\"text\":\"seed\"}",
                                                0))))
                .isInstanceOf(ReceiveTurnResult.Accepted.class);
        Turn turn = turnRepository.find(turnId).orElseThrow();
        turn.claim(turn.revision(), new ExecutionClaim("owner-a", now.plusSeconds(60)), now);
        assertThat(turnRepository.save(turn, 1L, now)).isInstanceOf(SaveTurnResult.Saved.class);
        turn = turnRepository.find(turnId).orElseThrow();
        turn.start(now);
        assertThat(turnRepository.save(turn, turn.revision() - 1, now))
                .isInstanceOf(SaveTurnResult.Saved.class);
        return turnId;
    }

    private FrozenTurn freezeRunning(String clientRequestId, Instant now, String assistantJson)
            throws Exception {
        ConversationId conversationId = ConversationId.generate();
        assertThat(conversationStore.create(CreateConversationCommand.of(conversationId)))
                .isInstanceOf(CreateConversationResult.Created.class);
        TurnId turnId = TurnId.generate();
        assertThat(
                        turnCommitter.receive(
                                new ReceiveTurnPlan(
                                        conversationId,
                                        clientRequestId,
                                        turnId,
                                        new ReceiveTurnPlan.UserMessageDraft(
                                                MessageId.generate(),
                                                MessageRole.USER,
                                                "{\"v\":1,\"text\":\"seed\"}",
                                                0))))
                .isInstanceOf(ReceiveTurnResult.Accepted.class);
        Turn turn = turnRepository.find(turnId).orElseThrow();
        turn.claim(turn.revision(), new ExecutionClaim("owner-a", now.plusSeconds(60)), now);
        assertThat(turnRepository.save(turn, 1L, now)).isInstanceOf(SaveTurnResult.Saved.class);
        turn = turnRepository.find(turnId).orElseThrow();
        turn.start(now);
        assertThat(turnRepository.save(turn, turn.revision() - 1, now))
                .isInstanceOf(SaveTurnResult.Saved.class);
        turn = turnRepository.find(turnId).orElseThrow();
        var assistant =
                new CommitTurnPlan.AssistantMessageDraft(
                        MessageId.generate(), MessageRole.ASSISTANT, assistantJson, 0);
        FreezeCommitResult frozen =
                turnCommitter.freezeCommit(
                        FreezeCommitPlan.of(turnId, turn.revision(), "owner-a", now, assistant, List.of(), List.of(), null));
        assertThat(frozen).isInstanceOf(FreezeCommitResult.Frozen.class);
        return new FrozenTurn(turnId, turnCommitter.frozenCommitPlan(turnId).orElseThrow());
    }

    private record FrozenTurn(TurnId turnId, CommitTurnPlan plan) {}

    private static int count(Connection connection, String sql) throws Exception {
        try (ResultSet rs = connection.createStatement().executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static String scalar(Connection connection, String sql, String arg) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
