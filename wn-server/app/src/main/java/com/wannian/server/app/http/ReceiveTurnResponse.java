package com.wannian.server.app.http;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 接收回合的 HTTP 结果。{@code result} 为 {@code accepted}、{@code conflict} 或 {@code rejected}。
 *
 * <p>{@code reply} 仅在本回合已经完成并有助手正文时出现。没有启用模型或模型失败时它为空，原因在 {@code detail}。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReceiveTurnResponse(
        String result, String turnId, Boolean replayed, String reasonCode, String detail, String reply) {}
