package com.wannian.server.app.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.IdleDeliveryId;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TaskDeliveryId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.conversation.MessageRole;
import com.wannian.server.app.notice.NoticeCenter;
import com.wannian.server.app.persistence.ConversationSearchSync;
import com.wannian.server.app.persistence.SqliteTaskDeliveryPendingRepository;
import com.wannian.server.kernel.task.BackgroundTaskStatus;
import com.wannian.server.kernel.task.IdleDeliveryPending;
import com.wannian.server.kernel.task.IdleDeliveryPendingRepository;
import com.wannian.server.kernel.task.IdleDeliveryStatus;
import com.wannian.server.kernel.task.NotifyPolicy;
import com.wannian.server.kernel.task.TaskDeliveryPending;
import com.wannian.server.kernel.task.TaskDeliveryPendingRepository;
import com.wannian.server.kernel.task.TaskDeliveryStatus;
import com.wannian.server.kernel.task.TaskDraft;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Busy 虚线交付（2.5.6）+ Idle 入队（2.5.7）：终态分流；Busy flush 附带；Idle 由 worker 唤模。
 *
 * <p>非 final：{@link DurableTurnScheduler} 以 {@code @Lazy} 注入，需可被 Spring CGLIB 代理。
 */
public class TaskDeliveryService {

    public static final String DASH_PREFIX = "───\n";
    /** Idle 重试代数写入 payload，驱动新的 clientRequestId。 */
    public static final String WAKE_ATTEMPT_FIELD = "_wakeAttempt";

    private static final System.Logger LOG = System.getLogger(TaskDeliveryService.class.getName());

    private final TaskDeliveryPendingRepository deliveries;
    private final SqliteTaskDeliveryPendingRepository sqliteDeliveries;
    private final IdleDeliveryPendingRepository idleDeliveries;
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int previewChars;
    private final ConversationBusyProbe busyProbe;
    private final Runnable idleNudge;
    private final NoticeCenter noticeCenter;

    public TaskDeliveryService(
            SqliteTaskDeliveryPendingRepository deliveries,
            IdleDeliveryPendingRepository idleDeliveries,
            DataSource dataSource,
            ObjectMapper objectMapper,
            Clock clock,
            int previewChars,
            ConversationBusyProbe busyProbe,
            Runnable idleNudge,
            NoticeCenter noticeCenter) {
        this.deliveries = Objects.requireNonNull(deliveries, "deliveries");
        this.sqliteDeliveries = deliveries;
        this.idleDeliveries = Objects.requireNonNull(idleDeliveries, "idleDeliveries");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (previewChars < 64) {
            throw new IllegalArgumentException("previewChars 须 ≥ 64");
        }
        this.previewChars = previewChars;
        this.busyProbe = Objects.requireNonNull(busyProbe, "busyProbe");
        this.idleNudge = idleNudge == null ? () -> {} : idleNudge;
        this.noticeCenter = Objects.requireNonNull(noticeCenter, "noticeCenter");
    }

    /** acceptResult / cancel 终态后调用。 */
    public void onTaskTerminal(
            TaskDraft task,
            BackgroundTaskStatus terminalStatus,
            String resultJson,
            String errorCode) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(terminalStatus, "terminalStatus");
        if (terminalStatus != BackgroundTaskStatus.SUCCEEDED
                && terminalStatus != BackgroundTaskStatus.FAILED
                && terminalStatus != BackgroundTaskStatus.CANCELLED) {
            return;
        }
        if (task.notifyPolicy() == NotifyPolicy.SILENT) {
            return;
        }
        Instant now = clock.instant();
        boolean scheduled = task.scheduleSpec() != null;
        String payload =
                buildPayload(task.taskId(), terminalStatus, resultJson, errorCode, scheduled);
        if (!busyProbe.isBusyForDelivery(task.conversationId())) {
            enqueueIdle(task, terminalStatus, payload, now);
            return;
        }
        if (hasActiveBusyDelivery(task, terminalStatus)) {
            return;
        }
        TaskDeliveryId busyId = TaskDeliveryId.generate();
        deliveries.insert(
                new TaskDeliveryPending(
                        busyId,
                        task.conversationId(),
                        task.taskId(),
                        terminalStatus,
                        payload,
                        TaskDeliveryStatus.QUEUED,
                        now,
                        null));
        // Busy 判定与 insert 非原子：若已 Idle（flush 窗口已过），改走 Idle 唤模，避免 Busy 表永久滞留
        if (!busyProbe.isBusyForDelivery(task.conversationId())) {
            if (deliveries.casCancelled(busyId)) {
                enqueueIdle(task, terminalStatus, payload, now);
            }
        }
    }

    private boolean hasActiveBusyDelivery(TaskDraft task, BackgroundTaskStatus terminalStatus) {
        // 多次开火：仅 QUEUED 挡重复；单次：任一历史行仍挡（防双 notify）
        if (task.scheduleSpec() != null && task.scheduleSpec().recurring()) {
            return deliveries.findActiveQueued(task.taskId(), terminalStatus).isPresent();
        }
        return deliveries.findByTaskTerminal(task.taskId(), terminalStatus).isPresent();
    }

    private void enqueueIdle(
            TaskDraft task,
            BackgroundTaskStatus terminalStatus,
            String payload,
            Instant now) {
        if (task.scheduleSpec() != null && task.scheduleSpec().recurring()) {
            if (idleDeliveries.findActiveQueued(task.taskId(), terminalStatus).isPresent()) {
                return;
            }
        } else if (idleDeliveries.findByTaskTerminal(task.taskId(), terminalStatus).isPresent()) {
            return;
        }
        idleDeliveries.insert(
                new IdleDeliveryPending(
                        IdleDeliveryId.generate(),
                        task.conversationId(),
                        task.taskId(),
                        terminalStatus,
                        payload,
                        IdleDeliveryStatus.QUEUED,
                        now,
                        null,
                        null));
        try {
            idleNudge.run();
        } catch (RuntimeException ignored) {
            // nudge 失败由 tick 兜底
        }
    }

    /** Turn 成功完成后 flush 该会话队列。 */
    public void flushAfterTurnCompleted(ConversationId conversationId, TurnId completedTurnId) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(completedTurnId, "completedTurnId");
        List<TaskDeliveryPending> queued = deliveries.listQueued(conversationId);
        for (TaskDeliveryPending item : queued) {
            try {
                deliverOne(item, completedTurnId);
            } catch (RuntimeException ex) {
                // 单条失败保持 QUEUED，不假 DELIVERED
                System.getLogger(TaskDeliveryService.class.getName())
                        .log(
                                System.Logger.Level.WARNING,
                                "flush delivery failed id=" + item.deliveryId().asString(),
                                ex);
            }
        }
    }

    private void deliverOne(TaskDeliveryPending item, TurnId completedTurnId) {
        Instant now = clock.instant();
        String text = formatUserText(item);
        String contentJson = contentEnvelope(text, item);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                ConversationMeta meta = loadConversation(connection, item.conversationId().asString());
                if (meta == null || !"ACTIVE".equals(meta.status())) {
                    connection.rollback();
                    deliveries.casCancelled(item.deliveryId());
                    return;
                }
                int seq = allocateMessageSequence(connection, item.conversationId().asString());
                MessageId messageId = MessageId.generate();
                insertAssistantMessage(
                        connection,
                        messageId.asString(),
                        item.conversationId().asString(),
                        completedTurnId.asString(),
                        contentJson,
                        seq,
                        now.toString());
                ConversationSearchSync.upsertMessageRow(
                        connection,
                        item.conversationId().asString(),
                        messageId.asString(),
                        meta.status(),
                        meta.title(),
                        text);
                ObjectNode payload = objectMapper.createObjectNode();
                payload.put("v", 1);
                payload.put("conversationId", item.conversationId().asString());
                payload.put("turnId", completedTurnId.asString());
                payload.put("messageId", messageId.asString());
                payload.put("role", MessageRole.ASSISTANT.name());
                payload.put("text", text);
                payload.put("textPreview", clip(text, 120));
                payload.put("taskDelivery", true);
                payload.put("taskId", item.taskId().asString());
                payload.put("deliveryId", item.deliveryId().asString());
                insertOutbox(
                        connection,
                        UUID.randomUUID().toString(),
                        "Message",
                        messageId.asString(),
                        "MessageCommitted",
                        objectMapper.writeValueAsString(payload),
                        allocateOutboxSequence(connection),
                        now.toString());
                if (!sqliteDeliveries.casDeliveredOn(connection, item.deliveryId(), now)) {
                    connection.rollback();
                    return;
                }
                connection.commit();
            } catch (Exception ex) {
                connection.rollback();
                throw new IllegalStateException("deliverOne 失败", ex);
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("deliverOne 无法打开连接", ex);
        }
        publishNoticeAfterVisibleDelivery(item);
    }

    /** Busy 虚线已落 Message+Outbox 后投喂消息中心（2.5.10）。 */
    private void publishNoticeAfterVisibleDelivery(TaskDeliveryPending item) {
        try {
            noticeCenter.publishTaskTerminal(
                    item.conversationId(),
                    item.taskId(),
                    item.terminalStatus(),
                    errorCodeFromPayload(item.payloadJson()),
                    previewFromPayload(item.payloadJson()),
                    scheduledFromPayload(item.payloadJson()));
        } catch (RuntimeException ex) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    "消息中心 publish 失败 delivery=" + item.deliveryId().asString(),
                    ex);
        }
    }

    private String buildPayload(
            BackgroundTaskId taskId,
            BackgroundTaskStatus terminalStatus,
            String resultJson,
            String errorCode,
            boolean scheduled) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("taskId", taskId.asString());
            node.put("terminalStatus", terminalStatus.name());
            if (errorCode != null && !errorCode.isBlank()) {
                node.put("errorCode", errorCode);
            }
            if (resultJson != null && !resultJson.isBlank()) {
                node.put("resultPreview", clip(sanitizePreview(resultJson), previewChars));
            }
            if (scheduled) {
                node.put("scheduled", true);
            }
            node.put(WAKE_ATTEMPT_FIELD, 0);
            return objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            return "{\"taskId\":\"" + taskId.asString() + "\",\"terminalStatus\":\""
                    + terminalStatus.name() + "\",\"" + WAKE_ATTEMPT_FIELD + "\":0}";
        }
    }

    private String errorCodeFromPayload(String payloadJson) {
        try {
            var node = objectMapper.readTree(payloadJson);
            if (node.hasNonNull("errorCode")) {
                return node.get("errorCode").asText();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    private boolean scheduledFromPayload(String payloadJson) {
        try {
            var node = objectMapper.readTree(payloadJson);
            return node.path("scheduled").asBoolean(false);
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 去掉疑似密钥行与堆栈帧，避免原样进用户气泡 / Idle 汇报块。 */
    static String sanitizePreview(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (String line : raw.split("\\R", -1)) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.matches("(?i).*(api[_-]?key|secret|password|passwd|token)\\s*[:=].*")) {
                out.append("[已脱敏]\n");
                continue;
            }
            if (t.startsWith("at ") || t.startsWith("Caused by:")) {
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString().trim();
    }

    /** 供 Idle 失败重试：递增 payload 内 {@link #WAKE_ATTEMPT_FIELD}。 */
    public String bumpWakeAttemptPayload(String payloadJson) {
        try {
            ObjectNode node =
                    payloadJson == null || payloadJson.isBlank()
                            ? objectMapper.createObjectNode()
                            : (ObjectNode) objectMapper.readTree(payloadJson);
            int attempt = node.path(WAKE_ATTEMPT_FIELD).asInt(0) + 1;
            node.put(WAKE_ATTEMPT_FIELD, attempt);
            return objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            LOG.log(System.Logger.Level.WARNING, "bumpWakeAttemptPayload 失败，回退原 payload", ex);
            return payloadJson == null ? "{}" : payloadJson;
        }
    }

    private String formatUserText(TaskDeliveryPending item) {
        String taskLine = "任务：" + item.taskId().asString();
        String body =
                switch (item.terminalStatus()) {
                    case SUCCEEDED ->
                            "后台任务已完成。\n" + taskLine + "\n" + previewFromPayload(item.payloadJson());
                    case FAILED ->
                            "后台任务失败"
                                    + errorSuffix(item.payloadJson())
                                    + "。\n"
                                    + taskLine
                                    + "\n"
                                    + previewFromPayload(item.payloadJson());
                    case CANCELLED -> "后台任务已取消。\n" + taskLine;
                    default ->
                            "后台任务已结束（"
                                    + item.terminalStatus().name()
                                    + "）。\n"
                                    + taskLine;
                };
        return DASH_PREFIX + body;
    }

    private String previewFromPayload(String payloadJson) {
        try {
            var node = objectMapper.readTree(payloadJson);
            if (node.hasNonNull("resultPreview")) {
                return clip(node.get("resultPreview").asText(), previewChars);
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "";
    }

    private String errorSuffix(String payloadJson) {
        try {
            var node = objectMapper.readTree(payloadJson);
            if (node.hasNonNull("errorCode")) {
                return "（" + node.get("errorCode").asText() + "）";
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "";
    }

    private String contentEnvelope(String text, TaskDeliveryPending item) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("v", 1);
            node.put("text", text);
            ObjectNode td = node.putObject("taskDelivery");
            td.put("deliveryId", item.deliveryId().asString());
            td.put("taskId", item.taskId().asString());
            td.put("terminalStatus", item.terminalStatus().name());
            return objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            return "{\"v\":1,\"text\":" + quoteJson(text) + "}";
        }
    }

    /** H3：交付 Busy = RECEIVED ∪ CLAIMED ∪ RUNNING ∪ COMMITTING。 */
    @FunctionalInterface
    public interface ConversationBusyProbe {
        boolean isBusyForDelivery(ConversationId conversationId);
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, max) + "…";
    }

    private static String quoteJson(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        out.append('"');
        return out.toString();
    }

    private static ConversationMeta loadConversation(Connection connection, String conversationId)
            throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        "SELECT status, title FROM conversation WHERE id = ?")) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new ConversationMeta(rs.getString("status"), rs.getString("title"));
            }
        }
    }

    private static int allocateMessageSequence(Connection connection, String conversationId)
            throws SQLException {
        String sql =
                """
                UPDATE conversation
                SET next_message_seq = next_message_seq + 1
                WHERE id = ?
                RETURNING next_message_seq - 1
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("会话不存在: " + conversationId);
                }
                return rs.getInt(1);
            }
        }
    }

    private static long allocateOutboxSequence(Connection connection) throws SQLException {
        String sql =
                """
                UPDATE sequence_counter
                SET next_value = next_value + 1
                WHERE name = 'outbox'
                RETURNING next_value - 1
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                throw new SQLException("outbox 序号计数器不存在");
            }
            return rs.getLong(1);
        }
    }

    private static void insertAssistantMessage(
            Connection connection,
            String messageId,
            String conversationId,
            String turnId,
            String contentJson,
            int sequenceNo,
            String now)
            throws SQLException {
        String sql =
                """
                INSERT INTO message (
                    id, conversation_id, turn_id, role, content_json, sequence_no, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, messageId);
            ps.setString(2, conversationId);
            ps.setString(3, turnId);
            ps.setString(4, MessageRole.ASSISTANT.name());
            ps.setString(5, contentJson);
            ps.setInt(6, sequenceNo);
            ps.setString(7, now);
            ps.executeUpdate();
        }
    }

    private static void insertOutbox(
            Connection connection,
            String eventId,
            String aggregateType,
            String aggregateId,
            String eventType,
            String payloadJson,
            long sequenceNo,
            String now)
            throws SQLException {
        String sql =
                """
                INSERT INTO outbox_event (
                    id, aggregate_type, aggregate_id, event_type, payload_json, sequence_no, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, eventId);
            ps.setString(2, aggregateType);
            ps.setString(3, aggregateId);
            ps.setString(4, eventType);
            ps.setString(5, payloadJson);
            ps.setLong(6, sequenceNo);
            ps.setString(7, now);
            ps.executeUpdate();
        }
    }

    private record ConversationMeta(String status, String title) {}
}
