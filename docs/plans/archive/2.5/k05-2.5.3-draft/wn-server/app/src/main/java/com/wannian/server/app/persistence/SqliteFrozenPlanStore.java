package com.wannian.server.app.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.conversation.MessageRole;
import com.wannian.server.kernel.memory.ApprovedMemoryChange;
import com.wannian.server.kernel.memory.CompanionIdentity;
import com.wannian.server.kernel.memory.ContentKind;
import com.wannian.server.kernel.memory.MemoryScope;
import com.wannian.server.kernel.memory.SourceKind;
import com.wannian.server.kernel.relationship.ApprovedRelationshipChange;
import com.wannian.server.kernel.task.BackgroundTaskStatus;
import com.wannian.server.kernel.task.NotifyPolicy;
import com.wannian.server.kernel.task.ScheduleSpec;
import com.wannian.server.kernel.task.TaskDraft;
import com.wannian.server.kernel.task.TaskSource;
import com.wannian.server.kernel.task.TaskType;
import com.wannian.server.kernel.turn.CommitTurnPlan;
import com.wannian.server.kernel.turn.FreezeCommitPlan;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Stores and decodes the durable completion plan without owning the caller's transaction. */
final class SqliteFrozenPlanStore {

    private static final int CURRENT_FORMAT = 4;
    private final ObjectMapper objectMapper;

    SqliteFrozenPlanStore(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    void insert(Connection connection, FreezeCommitPlan plan)
            throws SQLException, JsonProcessingException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("v", CURRENT_FORMAT);
        root.put("executionId", plan.expectedExecutionId());
        root.put("assistantMessageId", plan.assistantMessage().messageId().asString());
        root.put("assistantRole", plan.assistantMessage().role().name());
        root.put("assistantContentJson", plan.assistantMessage().contentJson());
        ArrayNode events = root.putArray("additionalEvents");
        for (CommitTurnPlan.OutboxEventDraft event : plan.additionalOutboxEvents()) {
            ObjectNode node = events.addObject();
            node.put("eventId", event.eventId());
            node.put("aggregateType", event.aggregateType());
            node.put("aggregateId", event.aggregateId());
            node.put("eventType", event.eventType());
            node.put("payloadJson", event.payloadJson());
        }
        ArrayNode memories = root.putArray("approvedMemoryChanges");
        for (ApprovedMemoryChange change : plan.approvedMemoryChanges()) {
            memories.add(writeMemoryChange(change));
        }
        if (plan.approvedRelationshipChange() != null) {
            root.set("approvedRelationshipChange", writeRelationshipChange(plan.approvedRelationshipChange()));
        } else {
            root.putNull("approvedRelationshipChange");
        }
        if (plan.taskDraft() != null) {
            root.set("taskDraft", writeTaskDraft(plan.taskDraft()));
        } else {
            root.putNull("taskDraft");
        }
        String sql =
                """
                INSERT INTO turn_commit_plan (
                    turn_id, format_version, execution_id, plan_json, frozen_at
                ) VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, plan.turnId().asString());
            ps.setInt(2, CURRENT_FORMAT);
            ps.setString(3, plan.expectedExecutionId());
            ps.setString(4, objectMapper.writeValueAsString(root));
            ps.setString(5, plan.now().toString());
            ps.executeUpdate();
        }
    }

    FrozenPlan load(Connection connection, TurnId turnId) throws SQLException, JsonProcessingException {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        """
                        SELECT format_version, execution_id, plan_json
                        FROM turn_commit_plan
                        WHERE turn_id = ?
                        """)) {
            ps.setString(1, turnId.asString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                int formatVersion = rs.getInt("format_version");
                if (formatVersion < 1 || formatVersion > CURRENT_FORMAT) {
                    throw invalid("不支持的完成计划格式: " + formatVersion);
                }
                JsonNode root = objectMapper.readTree(rs.getString("plan_json"));
                if (root == null || !root.isObject()) {
                    throw invalid("plan_json 必须是对象");
                }
                if (!root.path("v").isIntegralNumber() || root.path("v").asInt() != formatVersion) {
                    throw invalid("plan_json 版本与 format_version 不一致");
                }
                try {
                    String executionId = requiredText(root, "executionId");
                    String rowExecutionId = rs.getString("execution_id");
                    if (!executionId.equals(rowExecutionId)) {
                        throw invalid("plan_json executionId 与冻结计划行不一致");
                    }
                    MessageId messageId = new MessageId(UUID.fromString(requiredText(root, "assistantMessageId")));
                    MessageRole role = MessageRole.valueOf(requiredText(root, "assistantRole"));
                    String content = requiredText(root, "assistantContentJson");
                    JsonNode eventNodes = root.get("additionalEvents");
                    if (eventNodes == null || !eventNodes.isArray()) {
                        throw invalid("plan_json additionalEvents 必须是数组");
                    }
                    List<CommitTurnPlan.OutboxEventDraft> events = new ArrayList<>();
                    for (JsonNode node : eventNodes) {
                        if (!node.isObject()) {
                            throw invalid("plan_json additionalEvents 项必须是对象");
                        }
                        events.add(
                                new CommitTurnPlan.OutboxEventDraft(
                                        requiredText(node, "eventId"),
                                        requiredText(node, "aggregateType"),
                                        requiredText(node, "aggregateId"),
                                        requiredText(node, "eventType"),
                                        requiredText(node, "payloadJson"),
                                        0L));
                    }

                    List<ApprovedMemoryChange> memories = new ArrayList<>();
                    JsonNode memoryNodes = root.get("approvedMemoryChanges");
                    if (memoryNodes != null) {
                        if (!memoryNodes.isArray()) {
                            throw invalid("plan_json approvedMemoryChanges 必须是数组");
                        }
                        for (JsonNode node : memoryNodes) {
                            memories.add(readMemoryChange(node));
                        }
                    } else if (formatVersion >= 2) {
                        throw invalid("v2 plan_json 缺少 approvedMemoryChanges");
                    }

                    ApprovedRelationshipChange relationship = null;
                    JsonNode relNode = root.get("approvedRelationshipChange");
                    if (relNode != null && !relNode.isNull()) {
                        relationship = readRelationshipChange(relNode);
                    } else if (relNode == null && formatVersion >= 2) {
                        throw invalid("v2 plan_json 缺少 approvedRelationshipChange");
                    }
                    TaskDraft taskDraft = null;
                    if (formatVersion >= 4) {
                        JsonNode taskNode = root.get("taskDraft");
                        if (taskNode != null && !taskNode.isNull()) {
                            taskDraft = readTaskDraft(taskNode);
                        }
                    }
                    return new FrozenPlan(
                            rowExecutionId,
                            new CommitTurnPlan.AssistantMessageDraft(messageId, role, content, 0),
                            List.copyOf(events),
                            List.copyOf(memories),
                            relationship,
                            taskDraft);
                } catch (IllegalArgumentException ex) {
                    throw invalid("plan_json 含非法完成计划数据", ex);
                }
            }
        }
    }

    void delete(Connection connection, TurnId turnId) throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement("DELETE FROM turn_commit_plan WHERE turn_id = ?")) {
            ps.setString(1, turnId.asString());
            ps.executeUpdate();
        }
    }

    private ObjectNode writeMemoryChange(ApprovedMemoryChange change) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("companionIdentity", change.companionIdentity().value());
        node.put("subjectKey", change.subjectKey());
        node.put("claim", change.claim());
        node.put("contentKind", change.contentKind().name());
        node.put("sourceKind", change.sourceKind().name());
        node.put("scope", change.scope().name());
        node.put("importance", change.importance());
        if (change.path() != null) {
            node.put("path", change.path());
        } else {
            node.putNull("path");
        }
        node.put("proposeId", change.proposeId());
        if (change.expectedGeneration() != null) {
            node.put("expectedGeneration", change.expectedGeneration());
        } else {
            node.putNull("expectedGeneration");
        }
        return node;
    }

    private static ApprovedMemoryChange readMemoryChange(JsonNode node) throws SQLException {
        if (!node.isObject()) {
            throw invalid("plan_json memory change 必须是对象");
        }
        JsonNode importanceNode = node.get("importance");
        if (importanceNode == null || importanceNode.isNull() || !importanceNode.isNumber()) {
            throw invalid("plan_json memory change 缺少有效 importance");
        }
        double importance = importanceNode.asDouble();
        if (!Double.isFinite(importance)) {
            throw invalid("plan_json memory change importance 非有限数");
        }
        JsonNode pathNode = node.get("path");
        String path = pathNode == null || pathNode.isNull() ? null : requiredText(node, "path");
        JsonNode generationNode = node.get("expectedGeneration");
        Long expectedGeneration = null;
        if (generationNode != null && !generationNode.isNull()) {
            if (!generationNode.isIntegralNumber()) {
                throw invalid("plan_json memory change expectedGeneration 必须是整数或 null");
            }
            expectedGeneration = generationNode.asLong();
        }
        return new ApprovedMemoryChange(
                new CompanionIdentity(requiredText(node, "companionIdentity")),
                requiredText(node, "subjectKey"),
                requiredText(node, "claim"),
                ContentKind.valueOf(requiredText(node, "contentKind")),
                SourceKind.valueOf(requiredText(node, "sourceKind")),
                MemoryScope.valueOf(requiredText(node, "scope")),
                importance,
                path,
                requiredText(node, "proposeId"),
                expectedGeneration);
    }

    private ObjectNode writeRelationshipChange(ApprovedRelationshipChange change) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("companionIdentity", change.companionIdentity().value());
        if (change.preferredAddress() != null) {
            node.put("preferredAddress", change.preferredAddress());
        } else {
            node.putNull("preferredAddress");
        }
        if (change.boundaries() != null) {
            node.put("boundaries", change.boundaries());
        } else {
            node.putNull("boundaries");
        }
        node.put("reason", change.reason());
        node.put("proposeId", change.proposeId());
        return node;
    }

    private static ApprovedRelationshipChange readRelationshipChange(JsonNode node) throws SQLException {
        if (!node.isObject()) {
            throw invalid("plan_json approvedRelationshipChange 必须是对象或 null");
        }
        return new ApprovedRelationshipChange(
                new CompanionIdentity(requiredText(node, "companionIdentity")),
                optionalText(node, "preferredAddress"),
                optionalText(node, "boundaries"),
                requiredText(node, "reason"),
                requiredText(node, "proposeId"));
    }

    private ObjectNode writeTaskDraft(TaskDraft draft) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("taskId", draft.taskId().asString());
        node.put("conversationId", draft.conversationId().asString());
        node.put("originTurnId", draft.originTurnId().asString());
        if (draft.companionId() != null) {
            node.put("companionId", draft.companionId().value());
        } else {
            node.putNull("companionId");
        }
        node.put("source", draft.source().name());
        node.put("taskType", draft.taskType().name());
        node.put("notifyPolicy", draft.notifyPolicy().name());
        node.put("inputJson", draft.inputJson());
        node.put("retryPolicyJson", draft.retryPolicyJson());
        if (draft.scheduleSpec() != null) {
            node.set("scheduleSpec", ScheduleSpecJson.toObjectNode(draft.scheduleSpec()));
        } else {
            node.putNull("scheduleSpec");
        }
        node.put("timezone", draft.timezone());
        if (draft.nextFireAt() != null) {
            node.put("nextFireAt", draft.nextFireAt().toString());
        } else {
            node.putNull("nextFireAt");
        }
        node.put("initialStatus", draft.initialStatus().name());
        return node;
    }

    private static TaskDraft readTaskDraft(JsonNode node) throws SQLException {
        if (!node.isObject()) {
            throw invalid("plan_json taskDraft 必须是对象或 null");
        }
        CompanionIdentity companion = null;
        JsonNode companionNode = node.get("companionId");
        if (companionNode != null && !companionNode.isNull()) {
            companion = new CompanionIdentity(requiredText(node, "companionId"));
        }
        ScheduleSpec scheduleSpec = null;
        JsonNode specNode = node.get("scheduleSpec");
        if (specNode != null && !specNode.isNull()) {
            try {
                scheduleSpec = ScheduleSpecJson.fromNode(specNode);
            } catch (IllegalArgumentException ex) {
                throw invalid("plan_json taskDraft.scheduleSpec 非法", ex);
            }
        }
        Instant nextFireAt = null;
        JsonNode nextNode = node.get("nextFireAt");
        if (nextNode != null && !nextNode.isNull()) {
            nextFireAt = Instant.parse(requiredText(node, "nextFireAt"));
        }
        try {
            return new TaskDraft(
                    new BackgroundTaskId(UUID.fromString(requiredText(node, "taskId"))),
                    new ConversationId(UUID.fromString(requiredText(node, "conversationId"))),
                    new TurnId(UUID.fromString(requiredText(node, "originTurnId"))),
                    companion,
                    TaskSource.valueOf(requiredText(node, "source")),
                    TaskType.valueOf(requiredText(node, "taskType")),
                    NotifyPolicy.valueOf(requiredText(node, "notifyPolicy")),
                    requiredText(node, "inputJson"),
                    requiredText(node, "retryPolicyJson"),
                    scheduleSpec,
                    requiredText(node, "timezone"),
                    nextFireAt,
                    BackgroundTaskStatus.valueOf(requiredText(node, "initialStatus")));
        } catch (IllegalArgumentException ex) {
            throw invalid("plan_json taskDraft 含非法字段", ex);
        }
    }

    private static String optionalText(JsonNode node, String field) throws SQLException {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid("plan_json 字段 " + field + " 必须是字符串或 null");
        }
        return value.asText();
    }

    private static String requiredText(JsonNode node, String field) throws SQLException {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid("plan_json 缺少有效字符串字段 " + field);
        }
        return value.asText();
    }

    private static SQLException invalid(String message) {
        return new SQLException(message);
    }

    private static SQLException invalid(String message, Throwable cause) {
        return new SQLException(message, cause);
    }

    record FrozenPlan(
            String executionId,
            CommitTurnPlan.AssistantMessageDraft assistantMessage,
            List<CommitTurnPlan.OutboxEventDraft> additionalEvents,
            List<ApprovedMemoryChange> approvedMemoryChanges,
            ApprovedRelationshipChange approvedRelationshipChange,
            TaskDraft taskDraft) {}
}
