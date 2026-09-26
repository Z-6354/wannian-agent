package com.wannian.server.app.http;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 回合内一次工具调用的前端投影（来自 turn_step TOOL_CALL）。
 *
 * @param name 工具名
 * @param startedAt ISO-8601 开始时刻
 * @param finishedAt ISO-8601 结束时刻；未结束时可为 null
 * @param argumentsJson 服务端安全投影后的 JSON 文本（非内部原始参数）；
 *     可含 callId/operationId 与白名单业务字段，或 {@code parameters:hidden}；
 *     由 {@link TurnToolCallProjector#safeArgumentsJson} 生成，禁止前端再当原文信任
 * @param status SUCCEEDED / FAILED / REJECTED / UNKNOWN
 * @param errorCode 失败时的稳定码；成功为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolCallView(
        String name,
        String startedAt,
        String finishedAt,
        String argumentsJson,
        String status,
        String errorCode) {}
