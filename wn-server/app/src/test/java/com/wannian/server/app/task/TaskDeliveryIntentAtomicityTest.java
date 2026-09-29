package com.wannian.server.app.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.TaskDeliveryId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.app.notice.NoticeCenter;
import com.wannian.server.app.persistence.SqliteIdleDeliveryPendingRepository;
import com.wannian.server.app.persistence.SqliteTaskDeliveryPendingRepository;
import com.wannian.server.kernel.task.BackgroundTaskStatus;
import com.wannian.server.kernel.task.NotifyPolicy;
import com.wannian.server.kernel.task.TaskDeliveryPending;
import com.wannian.server.kernel.task.TaskDeliveryStatus;
import com.wannian.server.kernel.task.TaskDraft;
import com.wannian.server.kernel.task.TaskSource;
import com.wannian.server.kernel.task.TaskType;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/**
 * S2/S3：终态同事务写入交付意图；Busy abort 后 Idle 转移，不留永久 QUEUED。
 */
class TaskDeliveryIntentAtomicityTest {

    @TempDir Path dir;

    private DataSource dataSource;
    private SqliteTaskDeliveryPendingRepository busyRepo;
    private SqliteIdleDeliveryPendingRepository idleRepo;
    private TaskDeliveryService delivery;
    private final Instant now = Instant.parse("2026-09-29T04:00:00Z");
    private final AtomicBoolean busy = new AtomicBoolean(true);

    @BeforeEach
    void setUp() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("wannian.db").toAbsolutePath());
        dataSource = ds;
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        busyRepo = new SqliteTaskDeliveryPendingRepository(dataSource);
        idleRepo = new SqliteIdleDeliveryPendingRepository(dataSource);
        delivery =
                new TaskDeliveryService(
                        busyRepo,
                        idleRepo,
                        dataSource,
                        new ObjectMapper(),
                        Clock.fixed(now, ZoneOffset.UTC),
                        256,
                        cid -> busy.get(),
                        () -> {},
                        new NoticeCenter(16),
                        () -> null);
    }

    @Test
    void recordDeliveryIntentOnSameConnectionSurvivesCommit() throws Exception {
        ConversationId conversationId = ConversationId.generate();
        TurnId origin = TurnId.generate();
        BackgroundTaskId taskId = BackgroundTaskId.generate();
        insertConversation(conversationId);
        insertTurn(conversationId, origin);
        insertTask(taskId, conversationId, origin);
        TaskDraft draft = draft(taskId, conversationId, origin);
        busy.set(true);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            boolean wrote =
                    delivery.recordDeliveryIntentOn(
                            connection,
                            draft,
                            BackgroundTaskStatus.SUCCEEDED,
                            "{\"ok\":true}",
                            null,
                            "run-1");
            assertThat(wrote).isTrue();
            connection.commit();
        }

        List<TaskDeliveryPending> queued = busyRepo.listQueued(conversationId);
        assertThat(queued).hasSize(1);
        assertThat(queued.get(0).status()).isEqualTo(TaskDeliveryStatus.QUEUED);
        assertThat(queued.get(0).taskId()).isEqualTo(taskId);
    }

    @Test
    void flushOrTransferAfterAbortMovesBusyQueuedToIdleWhenConversationIdle() throws Exception {
        ConversationId conversationId = ConversationId.generate();
        TurnId origin = TurnId.generate();
        BackgroundTaskId taskId = BackgroundTaskId.generate();
        insertConversation(conversationId);
        insertTurn(conversationId, origin);
        insertTask(taskId, conversationId, origin);
        busy.set(true);
        TaskDeliveryId deliveryId = TaskDeliveryId.generate();
        busyRepo.insert(
                new TaskDeliveryPending(
                        deliveryId,
                        conversationId,
                        taskId,
                        BackgroundTaskStatus.SUCCEEDED,
                        "{\"taskId\":\""
                                + taskId.asString()
                                + "\",\"terminalStatus\":\"SUCCEEDED\",\"runId\":\"run-x\"}",
                        TaskDeliveryStatus.QUEUED,
                        now,
                        null));

        busy.set(false);
        delivery.flushOrTransferAfterTurnAborted(conversationId, TurnId.generate());

        assertThat(busyRepo.listQueued(conversationId)).isEmpty();
        assertThat(idleRepo.findActiveQueued(taskId, BackgroundTaskStatus.SUCCEEDED)).isPresent();
    }

    private TaskDraft draft(
            BackgroundTaskId taskId, ConversationId conversationId, TurnId origin) {
        return new TaskDraft(
                taskId,
                conversationId,
                origin,
                null,
                TaskSource.USER_LOOP,
                TaskType.READ_ONLY_TOOL_BATCH,
                NotifyPolicy.USER_VISIBLE,
                "{\"tools\":[]}",
                "{}",
                null,
                "UTC",
                null,
                BackgroundTaskStatus.SUCCEEDED);
    }

    private void insertConversation(ConversationId id) throws Exception {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                """
                                INSERT INTO conversation (
                                    id, title, status, revision, created_at, updated_at, next_message_seq
                                ) VALUES (?, 't', 'ACTIVE', 1, ?, ?, 2)
                                """)) {
            ps.setString(1, id.asString());
            ps.setString(2, now.toString());
            ps.setString(3, now.toString());
            ps.executeUpdate();
        }
    }

    private void insertTurn(ConversationId conversationId, TurnId turnId) throws Exception {
        String messageId = java.util.UUID.randomUUID().toString();
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps =
                    c.prepareStatement(
                            """
                            INSERT INTO message (
                                id, conversation_id, turn_id, role, content_json, sequence_no, created_at
                            ) VALUES (?, ?, NULL, 'USER', '{"v":1,"text":"hi"}', 1, ?)
                            """)) {
                ps.setString(1, messageId);
                ps.setString(2, conversationId.asString());
                ps.setString(3, now.toString());
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
                ps.setString(3, "cr-" + turnId.asString());
                ps.setString(4, messageId);
                ps.setString(5, now.toString());
                ps.setString(6, now.toString());
                ps.setString(7, now.toString());
                ps.executeUpdate();
            }
        }
    }

    private void insertTask(
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
                                ) VALUES (?, ?, ?, NULL, 'USER_LOOP', 'READ_ONLY_TOOL_BATCH',
                                    'SUCCEEDED', '{}', '{}', 'USER_VISIBLE', '{}',
                                    NULL, 'UTC', NULL, NULL, 1, ?, ?, ?)
                                """)) {
            ps.setString(1, taskId.asString());
            ps.setString(2, originTurnId.asString());
            ps.setString(3, conversationId.asString());
            ps.setString(4, now.toString());
            ps.setString(5, now.toString());
            ps.setString(6, now.toString());
            ps.executeUpdate();
        }
    }
}
