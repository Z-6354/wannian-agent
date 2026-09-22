package com.wannian.server.app.http;

/**
 * {@code POST /api/conversations/{conversationId}/turns} 的请求体。
 *
 * <p>只接收用户文本。服务端包成内容信封，不接受助手消息。接收成功后，若已启用模型，经 TurnEngine 完成一轮回答。
 *
 * @param clientRequestId 幂等键，必填
 * @param text 用户原文。空白会被拒绝，但前后空白和换行会原样保存
 * @param sequenceNo 已弃用。服务端分配消息序号，客户端数字不会被采用，也不参与幂等比较
 * @param turnId 可选 UUID；缺省由服务端生成。重试即使生成新 id 也会回放原回合
 * @param messageId 可选 UUID；缺省由服务端生成。它不是幂等身份
 */
public record ReceiveTurnRequest(
        String clientRequestId, String text, Integer sequenceNo, String turnId, String messageId) {}
