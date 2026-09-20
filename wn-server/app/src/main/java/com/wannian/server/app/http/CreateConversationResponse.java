package com.wannian.server.app.http;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 建会话的 HTTP 结果。{@code result} 为 {@code created}、{@code already_exists} 或 {@code rejected}。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateConversationResponse(
        String result, String conversationId, String reasonCode, String detail) {}
