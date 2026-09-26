package com.wannian.server.kernel.agent;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.TurnId;

/**
 * Agent Loop 对外的可选活动钩子（工具开始/结束）。用于运行中 SSE 投影；不得写入 Outbox。
 *
 * <p>{@code onToolStarted} 的末参可为工具原始 {@code argumentsJson}；生产侧 Listener
 *（如 StreamingActivityListener）必须先脱敏再对外发布，禁止把密钥原文推给客户端。
 */
public interface AgentActivityListener {

    AgentActivityListener NOOP = new AgentActivityListener() {};

    default void onToolStarted(
            ConversationId conversationId,
            TurnId turnId,
            String executionHint,
            String callId,
            String operationId,
            String toolName,
            String argumentsJson) {}

    default void onToolUpdated(
            ConversationId conversationId,
            TurnId turnId,
            String executionHint,
            String callId,
            String operationId,
            String toolName,
            String status,
            String errorCode,
            String safeResultSummary) {}
}
