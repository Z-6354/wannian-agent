package com.wannian.server.app.http;

/**
 * {@code POST /api/conversations} 的请求体。字段都可省略：缺 id 则服务端生成，缺标题则用默认「会话N」。
 *
 * @param conversationId 可选 UUID
 * @param title 可选标题
 */
public record CreateConversationRequest(String conversationId, String title) {}
