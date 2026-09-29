package com.wannian.server.app.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.TaskReviewId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.task.NotifyPolicy;
import com.wannian.server.kernel.task.OriginTurn;
import com.wannian.server.kernel.task.ScheduleSpec;
import com.wannian.server.kernel.task.TaskProposal;
import com.wannian.server.kernel.task.TaskReviewPending;
import com.wannian.server.kernel.task.TaskReviewPendingRepository;
import com.wannian.server.kernel.task.TaskReviewStatus;
import com.wannian.server.kernel.task.TaskSource;
import com.wannian.server.kernel.task.TaskType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** {@link TaskReviewPendingRepository} SQLite 实现（V025）。 */
public final class SqliteTaskReviewPendingRepository implements TaskReviewPendingRepository {

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public SqliteTaskReviewPendingRepository(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public void insert(TaskReviewPending pending) {
        Objects.requireNonNull(pending, "pending");
        String sql =
                """
                INSERT INTO task_review_pending(
                    id, conversation_id, turn_id, proposal_json,
                    acknowledgement_text, status, created_at, expires_at
                ) VALUES (?,?,?,?,?,?,?,?)
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, pending.reviewId().asString());
            ps.setString(2, pending.conversationId().asString());
            ps.setString(3, pending.turnId().asString());
            ps.setString(4, writeProposal(pending.proposal()));
            ps.setString(5, pending.acknowledgementText());
            ps.setString(6, pending.status().name());
            ps.setString(7, pending.createdAt().toString());
            ps.setString(8, pending.expiresAt().toString());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("insert task_review_pending 失败", ex);
        }
    }

    @Override
    public Optional<TaskReviewPending> findById(TaskReviewId id) {
        Objects.requireNonNull(id, "id");
        String sql =
                """
                SELECT id, conversation_id, turn_id, proposal_json,
                       acknowledgement_text, status, created_at, expires_at
                FROM task_review_pending WHERE id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id.asString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRow(rs));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("find task_review_pending 失败", ex);
        }
    }

    @Override
    public List<TaskReviewPending> listByConversation(
            ConversationId conversationId, TaskReviewStatus status) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(status, "status");
        String sql =
                """
                SELECT id, conversation_id, turn_id, proposal_json,
                       acknowledgement_text, status, created_at, expires_at
                FROM task_review_pending
                WHERE conversation_id = ? AND status = ?
                ORDER BY created_at ASC
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, conversationId.asString());
            ps.setString(2, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                List<TaskReviewPending> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapRow(rs));
                }
                return List.copyOf(out);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("list task_review_pending 失败", ex);
        }
    }

    @Override
    public List<TaskReviewPending> listByStatus(TaskReviewStatus status) {
        Objects.requireNonNull(status, "status");
        String sql =
                """
                SELECT id, conversation_id, turn_id, proposal_json,
                       acknowledgement_text, status, created_at, expires_at
                FROM task_review_pending
                WHERE status = ?
                ORDER BY created_at ASC
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                List<TaskReviewPending> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapRow(rs));
                }
                return List.copyOf(out);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("listByStatus task_review_pending 失败", ex);
        }
    }

    @Override
    public boolean casStatus(
            TaskReviewId id, TaskReviewStatus expected, TaskReviewStatus next, Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(now, "now");
        String sql =
                """
                UPDATE task_review_pending
                SET status = ?
                WHERE id = ? AND status = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, next.name());
            ps.setString(2, id.asString());
            ps.setString(3, expected.name());
            return ps.executeUpdate() == 1;
        } catch (SQLException ex) {
            throw new IllegalStateException("casStatus task_review_pending 失败", ex);
        }
    }

    private TaskReviewPending mapRow(ResultSet rs) throws SQLException {
        try {
            return new TaskReviewPending(
                    TaskReviewId.parse(rs.getString("id")),
                    new ConversationId(UUID.fromString(rs.getString("conversation_id"))),
                    new TurnId(UUID.fromString(rs.getString("turn_id"))),
                    readProposal(rs.getString("proposal_json")),
                    rs.getString("acknowledgement_text"),
                    TaskReviewStatus.valueOf(rs.getString("status")),
                    Instant.parse(rs.getString("created_at")),
                    Instant.parse(rs.getString("expires_at")));
        } catch (RuntimeException ex) {
            throw new SQLException("task_review_pending 行损坏", ex);
        }
    }

    private String writeProposal(TaskProposal proposal) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("source", proposal.source().name());
            node.put("taskType", proposal.taskType().name());
            node.put("notifyPolicy", proposal.notifyPolicy().name());
            node.put("inputJson", proposal.inputJson());
            if (proposal.scheduleSpec() != null) {
                node.set("scheduleSpec", ScheduleSpecJson.toObjectNode(proposal.scheduleSpec()));
            } else {
                node.putNull("scheduleSpec");
            }
            ObjectNode origin = node.putObject("origin");
            origin.put("turnId", proposal.origin().turnId().asString());
            origin.put("conversationId", proposal.origin().conversationId().asString());
            if (proposal.origin().companionId() != null) {
                origin.put("companionId", proposal.origin().companionId().value());
            } else {
                origin.putNull("companionId");
            }
            if (proposal.retryPolicyJson() != null) {
                node.put("retryPolicyJson", proposal.retryPolicyJson());
            } else {
                node.putNull("retryPolicyJson");
            }
            return objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化 TaskProposal 失败", ex);
        }
    }

    private TaskProposal readProposal(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            ScheduleSpec schedule = null;
            JsonNode specNode = node.get("scheduleSpec");
            if (specNode != null && !specNode.isNull()) {
                schedule = ScheduleSpecJson.fromNode(specNode);
            }
            JsonNode originNode = node.get("origin");
            CompanionIdentity companion = null;
            JsonNode companionNode = originNode.get("companionId");
            if (companionNode != null && !companionNode.isNull()) {
                companion = new CompanionIdentity(companionNode.asText());
            }
            OriginTurn origin =
                    new OriginTurn(
                            new TurnId(UUID.fromString(originNode.get("turnId").asText())),
                            new ConversationId(
                                    UUID.fromString(originNode.get("conversationId").asText())),
                            companion);
            JsonNode retryNode = node.get("retryPolicyJson");
            String retry =
                    retryNode == null || retryNode.isNull() || retryNode.asText().isBlank()
                            ? null
                            : retryNode.asText();
            return new TaskProposal(
                    TaskSource.valueOf(node.get("source").asText()),
                    TaskType.valueOf(node.get("taskType").asText()),
                    NotifyPolicy.valueOf(node.get("notifyPolicy").asText()),
                    node.get("inputJson").asText(),
                    schedule,
                    origin,
                    retry);
        } catch (Exception ex) {
            throw new IllegalArgumentException("非法 proposal_json", ex);
        }
    }
}
