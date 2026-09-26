package com.wannian.server.kernel.conversation;

/**
 * 启发式归档评估占位（可增值）。
 *
 * <p>本阶段不装配为 Spring Bean；后续实现后与 LLM 实现并列，由配置
 * {@code wannian.conversation.archive-evaluator=heuristic|llm} 切换。
 * 不得在此类中依赖模型端口。
 */
public final class HeuristicConversationArchiveEvaluator implements ConversationArchiveEvaluator {

    @Override
    public ArchiveDecision evaluate(ArchiveCandidate candidate) {
        // 占位：未启用前恒 KEEP；真正规则在 0.2.5+ 补齐。
        return ArchiveDecision.keep("heuristic evaluator not enabled");
    }
}
