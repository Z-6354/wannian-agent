package com.wannian.server.app.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Objects;

/** 运行中临时事件（非 Outbox）。 */
public record RunEvent(
        String type,
        String conversationId,
        String turnId,
        String executionId,
        long runSeq,
        String payloadJson,
        Instant at) {

    public RunEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(payloadJson, "payloadJson");
        Objects.requireNonNull(at, "at");
    }

    public static RunEvent turnStarted(
            String conversationId, String turnId, String executionId, long runSeq) {
        ObjectNode n = base(conversationId, turnId, executionId, runSeq);
        return new RunEvent(
                "turn.started",
                conversationId,
                turnId,
                executionId,
                runSeq,
                n.toString(),
                Instant.now());
    }

    public static RunEvent replyDelta(
            String conversationId, String turnId, String executionId, long runSeq, String text) {
        ObjectNode n = base(conversationId, turnId, executionId, runSeq);
        n.put("text", text == null ? "" : text);
        return new RunEvent(
                "reply.delta",
                conversationId,
                turnId,
                executionId,
                runSeq,
                n.toString(),
                Instant.now());
    }

    public static RunEvent toolStarted(
            String conversationId,
            String turnId,
            String executionId,
            long runSeq,
            String callId,
            String operationId,
            String name,
            String argumentsSummary) {
        ObjectNode n = base(conversationId, turnId, executionId, runSeq);
        n.put("callId", callId);
        n.put("operationId", operationId);
        n.put("name", name);
        n.put("argumentsSummary", argumentsSummary == null ? "{}" : argumentsSummary);
        return new RunEvent(
                "tool.started",
                conversationId,
                turnId,
                executionId,
                runSeq,
                n.toString(),
                Instant.now());
    }

    public static RunEvent toolUpdated(
            String conversationId,
            String turnId,
            String executionId,
            long runSeq,
            String callId,
            String operationId,
            String name,
            String status,
            String errorCode,
            String resultSummary) {
        ObjectNode n = base(conversationId, turnId, executionId, runSeq);
        n.put("callId", callId);
        n.put("operationId", operationId);
        n.put("name", name);
        n.put("status", status);
        if (errorCode != null) {
            n.put("errorCode", errorCode);
        }
        if (resultSummary != null) {
            n.put("resultSummary", resultSummary);
        }
        return new RunEvent(
                "tool.updated",
                conversationId,
                turnId,
                executionId,
                runSeq,
                n.toString(),
                Instant.now());
    }

    private static ObjectNode base(
            String conversationId, String turnId, String executionId, long runSeq) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode n = mapper.createObjectNode();
        n.put("conversationId", conversationId);
        n.put("turnId", turnId);
        n.put("executionId", executionId);
        n.put("runSeq", runSeq);
        n.put("at", Instant.now().toString());
        return n;
    }
}
