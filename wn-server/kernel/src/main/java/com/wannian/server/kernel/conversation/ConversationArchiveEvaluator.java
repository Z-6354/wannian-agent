package com.wannian.server.kernel.conversation;

/**
 * 会话自动归档评估端口。
 *
 * <p>调度器只依赖本接口。本阶段生产装配 {@code LlmConversationArchiveEvaluator}；
 * 启发式实现另类注册，通过配置切换，禁止在调度器里写死算法分支。
 *
 * <p>失败 / 不确定时必须返回 {@link ArchiveDecision#keep}，不得抛到调度器外导致误归档。
 */
@FunctionalInterface
public interface ConversationArchiveEvaluator {

    ArchiveDecision evaluate(ArchiveCandidate candidate);
}
