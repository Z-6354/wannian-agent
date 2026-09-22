package com.wannian.server.kernel.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 单次 {@link ModelPort#decide} 的调用上下文。
 *
 * <p>由 AgentLoop（或探测等调用方）在发起 decide 前组装；适配器只读本快照，
 * 不持有 {@code AgentBudget} 令牌引用。取消与截止的权威仍在调用方：Loop 在 decide
 * 前检查令牌；此处 {@code cancelled} 是调用瞬间的布尔快照，供适配器快速短路。
 *
 * <p>本类型不含密钥、厂商配置或消息正文；不得把 SDK 客户端塞进字段。
 *
 * @param turnId 本回合身份字符串（通常为 {@code TurnId} 的文本形式）；不得为 null
 * @param stepNumber 本轮 Loop 内第几次 decide（从 1 起）；供日志与幂等关联
 * @param deadline 本调用不得越过的截止时刻；通常取 {@code AgentBudget.hardDeadline}；不得为 null
 * @param cancelled 组装本上下文时是否已请求取消；为 true 时适配器应返回受控 Failure，不再打网
 * @param traceId 跨层关联 ID（可与 turnId 相同或另设）；不得为 null；不得含密钥
 */
public record ModelCallContext(
        String turnId, int stepNumber, Instant deadline, boolean cancelled, String traceId) {
    public ModelCallContext {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(traceId, "traceId");
    }
}
