package com.wannian.server.app.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.TaskReviewId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.app.persistence.SqliteTaskReviewPendingRepository;
import com.wannian.server.kernel.task.NotifyPolicy;
import com.wannian.server.kernel.task.OriginTurn;
import com.wannian.server.kernel.task.TaskDraft;
import com.wannian.server.kernel.task.TaskProposal;
import com.wannian.server.kernel.task.TaskReviewPending;
import com.wannian.server.kernel.task.TaskReviewStatus;
import com.wannian.server.kernel.task.TaskRuntime;
import com.wannian.server.kernel.task.TaskSource;
import com.wannian.server.kernel.task.TaskType;
import com.wannian.server.kernel.turn.ExecuteTurnResult;
import com.wannian.server.kernel.turn.TurnEngine;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.sqlite.SQLiteDataSource;

/** S1：CLAIMED 中间态、确认失败回 PENDING、启动对账。 */
class TaskReviewClaimedReconcileTest {

    @TempDir Path dir;

    private DataSource dataSource;
    private SqliteTaskReviewPendingRepository reviews;
    private TaskRuntime taskRuntime;
    private TurnEngine turnEngine;
    private TaskReviewService service;
    private final Instant now = Instant.parse("2026-09-29T03:00:00Z");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("wannian.db").toAbsolutePath());
        dataSource = ds;
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        reviews = new SqliteTaskReviewPendingRepository(dataSource, new ObjectMapper());
        taskRuntime = mock(TaskRuntime.class);
        turnEngine = mock(TurnEngine.class);
        ObjectProvider<TurnEngine> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(turnEngine);
        service =
                new TaskReviewService(
                        reviews,
                        taskRuntime,
                        provider,
                        dataSource,
                        Clock.fixed(now, ZoneOffset.UTC),
                        Duration.ofSeconds(60));
    }

    @Test
    void confirmUsesClaimedThenConfirmedOnSuccess() throws Exception {
        Fixture f = seedPendingReview();
        TaskDraft draft =
                new TaskDraft(
                        BackgroundTaskId.generate(),
                        f.conversationId,
                        f.originTurnId,
                        null,
                        TaskSource.USER_LOOP,
                        TaskType.USER_SCHEDULED_NOTIFY,
                        NotifyPolicy.USER_VISIBLE,
                        "{\"message\":\"看微信\"}",
                        "{}",
                        null,
                        "UTC",
                        null,
                        BackgroundTaskStatusCreated());
        when(taskRuntime.prepare(any())).thenReturn(draft);
        when(turnEngine.commitTaskReviewAcceptance(
                        eq(f.conversationId),
                        eq(TaskReviewService.confirmClientRequestId(f.reviewId)),
                        anyString(),
                        eq(draft),
                        any()))
                .thenReturn(new ExecuteTurnResult.Replied("已确认后台任务。"));

        TaskReviewService.ReviewActionResult result =
                service.confirm(f.conversationId, f.reviewId);

        assertThat(result.success()).isTrue();
        assertThat(result.taskId()).isEqualTo(draft.taskId().asString());
        assertThat(reviews.findById(f.reviewId).orElseThrow().status())
                .isEqualTo(TaskReviewStatus.CONFIRMED);
    }

    @Test
    void confirmRestoresPendingWhenCommitFailsWithoutDurableTurn() throws Exception {
        Fixture f = seedPendingReview();
        TaskDraft draft =
                new TaskDraft(
                        BackgroundTaskId.generate(),
                        f.conversationId,
                        f.originTurnId,
                        null,
                        TaskSource.USER_LOOP,
                        TaskType.USER_SCHEDULED_NOTIFY,
                        NotifyPolicy.USER_VISIBLE,
                        "{\"message\":\"看微信\"}",
                        "{}",
                        null,
                        "UTC",
                        null,
                        BackgroundTaskStatusCreated());
        when(taskRuntime.prepare(any())).thenReturn(draft);
        when(turnEngine.commitTaskReviewAcceptance(any(), anyString(), anyString(), any(), any()))
                .thenReturn(new ExecuteTurnResult.Held("COMMIT_FAILED", "boom"));

        TaskReviewService.ReviewActionResult result =
                service.confirm(f.conversationId, f.reviewId);

        assertThat(result.success()).isFalse();
        assertThat(reviews.findById(f.reviewId).orElseThrow().status())
                .isEqualTo(TaskReviewStatus.PENDING);
    }

    @Test
    void reconcileClaimedWithoutTurnRestoresPending() throws Exception {
        Fixture f = seedPendingReview();
        assertThat(
                        reviews.casStatus(
                                f.reviewId,
                                TaskReviewStatus.PENDING,
                                TaskReviewStatus.CLAIMED,
                                now))
                .isTrue();

        service.reconcileClaimed();

        assertThat(reviews.findById(f.reviewId).orElseThrow().status())
                .isEqualTo(TaskReviewStatus.PENDING);
    }

    @Test
    void reconcileClaimedWithCompletedTurnAndTaskMarksConfirmed() throws Exception {
        Fixture f = seedPendingReview();
        assertThat(
                        reviews.casStatus(
                                f.reviewId,
                                TaskReviewStatus.PENDING,
                                TaskReviewStatus.CLAIMED,
                                now))
                .isTrue();
        String crid = TaskReviewService.confirmClientRequestId(f.reviewId);
        TurnId confirmTurn = TurnId.generate();
        BackgroundTaskId taskId = BackgroundTaskId.generate();
        insertCompletedConfirmTurn(f.conversationId, confirmTurn, crid);
        insertBackgroundTask(taskId, f.conversationId, confirmTurn);

        service.reconcileClaimed();

        assertThat(reviews.findById(f.reviewId).orElseThrow().status())
                .isEqualTo(TaskReviewStatus.CONFIRMED);
    }

    private static com.wannian.server.kernel.task.BackgroundTaskStatus BackgroundTaskStatusCreated() {
        return com.wannian.server.kernel.task.BackgroundTaskStatus.CREATED;
    }

    private Fixture seedPendingReview() throws Exception {
        ConversationId conversationId = ConversationId.generate();
        TurnId originTurnId = TurnId.generate();
        TaskReviewId reviewId = TaskReviewId.generate();
        insertConversation(conversationId);
        insertTurn(conversationId, originTurnId, "origin-" + originTurnId.asString(), 1);
        TaskProposal proposal =
                new TaskProposal(
                        TaskSource.USER_LOOP,
                        TaskType.USER_SCHEDULED_NOTIFY,
                        NotifyPolicy.USER_VISIBLE,
                        "{\"message\":\"看微信\"}",
                        null,
                        new OriginTurn(originTurnId, conversationId, null),
                        null);
        reviews.insert(
                new TaskReviewPending(
                        reviewId,
                        conversationId,
                        originTurnId,
                        proposal,
                        "将在稍后提醒你。",
                        TaskReviewStatus.PENDING,
                        now,
                        now.plus(Duration.ofMinutes(30))));
        return new Fixture(conversationId, originTurnId, reviewId);
    }

    private void insertConversation(ConversationId id) throws Exception {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                """
                                INSERT INTO conversation (
                                    id, title, status, revision, created_at, updated_at, next_message_seq
                                ) VALUES (?, 't', 'ACTIVE', 1, ?, ?, 3)
                                """)) {
            ps.setString(1, id.asString());
            ps.setString(2, now.toString());
            ps.setString(3, now.toString());
            ps.executeUpdate();
        }
    }

    private void insertTurn(
            ConversationId conversationId, TurnId turnId, String clientRequestId, long sequenceNo)
            throws Exception {
        String messageId = UUID.randomUUID().toString();
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps =
                    c.prepareStatement(
                            """
                            INSERT INTO message (
                                id, conversation_id, turn_id, role, content_json, sequence_no, created_at
                            ) VALUES (?, ?, NULL, 'USER', '{"v":1,"text":"hi"}', ?, ?)
                            """)) {
                ps.setString(1, messageId);
                ps.setString(2, conversationId.asString());
                ps.setLong(3, sequenceNo);
                ps.setString(4, now.toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps =
                    c.prepareStatement(
                            """
                            INSERT INTO turn (
                                id, conversation_id, client_request_id, status, input_message_id,
                                output_message_id, execution_id, claim_expires_at, revision, error_code,
                                created_at, updated_at, completed_at
                            ) VALUES (?, ?, ?, 'COMPLETED', ?, NULL, NULL, NULL, 1, NULL, ?, ?, ?)
                            """)) {
                ps.setString(1, turnId.asString());
                ps.setString(2, conversationId.asString());
                ps.setString(3, clientRequestId);
                ps.setString(4, messageId);
                ps.setString(5, now.toString());
                ps.setString(6, now.toString());
                ps.setString(7, now.toString());
                ps.executeUpdate();
            }
        }
    }

    private void insertCompletedConfirmTurn(
            ConversationId conversationId, TurnId turnId, String clientRequestId) throws Exception {
        insertTurn(conversationId, turnId, clientRequestId, 2);
    }

    private void insertBackgroundTask(
            BackgroundTaskId taskId, ConversationId conversationId, TurnId originTurnId)
            throws Exception {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                """
                                INSERT INTO background_task (
                                    id, origin_turn_id, conversation_id, companion_id, source, task_type,
                                    status, input_json, result_json, notify_policy, retry_policy_json,
                                    schedule_spec_json, timezone, next_fire_at, last_fired_at, revision,
                                    created_at, updated_at, completed_at
                                ) VALUES (?, ?, ?, NULL, 'USER_LOOP', 'USER_SCHEDULED_NOTIFY',
                                    'CREATED', '{}', NULL, 'USER_VISIBLE', '{}',
                                    NULL, 'UTC', NULL, NULL, 1, ?, ?, NULL)
                                """)) {
            ps.setString(1, taskId.asString());
            ps.setString(2, originTurnId.asString());
            ps.setString(3, conversationId.asString());
            ps.setString(4, now.toString());
            ps.setString(5, now.toString());
            ps.executeUpdate();
        }
    }

    private record Fixture(
            ConversationId conversationId, TurnId originTurnId, TaskReviewId reviewId) {}
}
