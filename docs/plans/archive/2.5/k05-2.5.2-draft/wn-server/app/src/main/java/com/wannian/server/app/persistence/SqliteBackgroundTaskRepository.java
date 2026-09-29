package com.wannian.server.app.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.task.BackgroundTaskRepository;
import com.wannian.server.kernel.task.BackgroundTaskStatus;
import com.wannian.server.kernel.task.NotifyPolicy;
import com.wannian.server.kernel.task.ScheduleSpec;
import com.wannian.server.kernel.task.TaskDraft;
import com.wannian.server.kernel.task.TaskSource;
import com.wannian.server.kernel.task.TaskType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * {@link BackgroundTaskRepository} SQLite 实现（P2 §8）。
 *
 * <p>本号可 INSERT/find；不被 Committer 调用。{@code schedule_spec_json} 用手写判别 JSON
 *（type=relative|at|every|cron），不依赖 Jackson 多态。
 */
public final class SqliteBackgroundTaskRepository implements BackgroundTaskRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;

    public SqliteBackgroundTaskRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void insertFromDraft(Connection connection, TaskDraft draft, Instant now) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(now, "now");
        String nowText = now.toString();
        String sql =
                """
                INSERT INTO background_task(
                    id, origin_turn_id, conversation_id, companion_id,
                    source, task_type, status, input_json, result_json,
                    notify_policy, retry_policy_json, schedule_spec_json,
                    timezone, next_fire_at, last_fired_at,
                    revision, created_at, updated_at, completed_at
                ) VALUES (?,?,?,?,?,?,?,?,NULL,?,?,?,?,?,NULL,0,?,?,NULL)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, draft.taskId().asString());
            ps.setString(2, draft.originTurnId().asString());
            ps.setString(3, draft.conversationId().asString());
            if (draft.companionId() == null) {
                ps.setNull(4, Types.VARCHAR);
            } else {
                ps.setString(4, draft.companionId().value());
            }
            ps.setString(5, draft.source().name());
            ps.setString(6, draft.taskType().name());
            ps.setString(7, draft.initialStatus().name());
            ps.setString(8, draft.inputJson());
            ps.setString(9, draft.notifyPolicy().name());
            ps.setString(10, draft.retryPolicyJson());
            if (draft.scheduleSpec() == null) {
                ps.setNull(11, Types.VARCHAR);
            } else {
                ps.setString(11, scheduleSpecToJson(draft.scheduleSpec()));
            }
            ps.setString(12, draft.timezone());
            if (draft.nextFireAt() == null) {
                ps.setNull(13, Types.VARCHAR);
            } else {
                ps.setString(13, draft.nextFireAt().toString());
            }
            ps.setString(14, nowText);
            ps.setString(15, nowText);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException(
                    "插入 background_task 失败: " + draft.taskId().asString(), ex);
        }
    }

    @Override
    public Optional<TaskDraft> findById(BackgroundTaskId id) {
        Objects.requireNonNull(id, "id");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                """
                                SELECT id, origin_turn_id, conversation_id, companion_id,
                                       source, task_type, status, input_json,
                                       notify_policy, retry_policy_json, schedule_spec_json,
                                       timezone, next_fire_at
                                FROM background_task WHERE id = ?
                                """)) {
            ps.setString(1, id.asString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapDraft(rs));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("加载 background_task 失败: " + id.asString(), ex);
        }
    }

    @Override
    public List<TaskDraft> listByConversation(ConversationId conversationId) {
        Objects.requireNonNull(conversationId, "conversationId");
        List<TaskDraft> out = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps =
                        connection.prepareStatement(
                                """
                                SELECT id, origin_turn_id, conversation_id, companion_id,
                                       source, task_type, status, input_json,
                                       notify_policy, retry_policy_json, schedule_spec_json,
                                       timezone, next_fire_at
                                FROM background_task
                                WHERE conversation_id = ?
                                ORDER BY created_at ASC
                                """)) {
            ps.setString(1, conversationId.asString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapDraft(rs));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(
                    "按会话列出 background_task 失败: " + conversationId.asString(), ex);
        }
        return List.copyOf(out);
    }

    private static TaskDraft mapDraft(ResultSet rs) throws SQLException {
        String companionRaw = rs.getString("companion_id");
        CompanionIdentity companion =
                companionRaw == null || companionRaw.isBlank()
                        ? null
                        : new CompanionIdentity(companionRaw);
        String specRaw = rs.getString("schedule_spec_json");
        ScheduleSpec spec =
                specRaw == null || specRaw.isBlank() ? null : scheduleSpecFromJson(specRaw);
        String nextFireRaw = rs.getString("next_fire_at");
        Instant nextFire =
                nextFireRaw == null || nextFireRaw.isBlank() ? null : Instant.parse(nextFireRaw);
        BackgroundTaskStatus status = BackgroundTaskStatus.valueOf(rs.getString("status"));
        return new TaskDraft(
                new BackgroundTaskId(UUID.fromString(rs.getString("id"))),
                new ConversationId(UUID.fromString(rs.getString("conversation_id"))),
                new TurnId(UUID.fromString(rs.getString("origin_turn_id"))),
                companion,
                TaskSource.valueOf(rs.getString("source")),
                TaskType.valueOf(rs.getString("task_type")),
                NotifyPolicy.valueOf(rs.getString("notify_policy")),
                rs.getString("input_json"),
                rs.getString("retry_policy_json"),
                spec,
                rs.getString("timezone"),
                nextFire,
                status);
    }

    static String scheduleSpecToJson(ScheduleSpec spec) {
        try {
            ObjectNode node = JSON.createObjectNode();
            switch (spec) {
                case ScheduleSpec.Relative relative -> {
                    node.put("type", "relative");
                    node.put("offset", relative.offset());
                    if (relative.resolvedAt() != null) {
                        node.put("resolvedAt", relative.resolvedAt().toString());
                    }
                }
                case ScheduleSpec.At at -> {
                    node.put("type", "at");
                    node.put("at", at.at().toString());
                }
                case ScheduleSpec.Every every -> {
                    node.put("type", "every");
                    node.put("everyMs", every.everyMs());
                    if (every.anchorAt() != null) {
                        node.put("anchorAt", every.anchorAt().toString());
                    }
                }
                case ScheduleSpec.Cron cron -> {
                    node.put("type", "cron");
                    node.put("expr", cron.expr());
                    node.put("tz", cron.tz());
                }
            }
            return JSON.writeValueAsString(node);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化 ScheduleSpec 失败", ex);
        }
    }

    static ScheduleSpec scheduleSpecFromJson(String json) {
        try {
            JsonNode node = JSON.readTree(json);
            String type = text(node, "type");
            return switch (type) {
                case "relative" -> new ScheduleSpec.Relative(
                        text(node, "offset"), optionalInstant(node, "resolvedAt"));
                case "at" -> new ScheduleSpec.At(Instant.parse(text(node, "at")));
                case "every" -> new ScheduleSpec.Every(
                        node.path("everyMs").asLong(), optionalInstant(node, "anchorAt"));
                case "cron" -> new ScheduleSpec.Cron(text(node, "expr"), text(node, "tz"));
                default -> throw new IllegalArgumentException("未知 schedule type: " + type);
            };
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("非法 schedule_spec_json: " + json, ex);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            throw new IllegalArgumentException("schedule_spec 缺少字段: " + field);
        }
        return v.asText();
    }

    private static Instant optionalInstant(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            return null;
        }
        return Instant.parse(v.asText());
    }
}
