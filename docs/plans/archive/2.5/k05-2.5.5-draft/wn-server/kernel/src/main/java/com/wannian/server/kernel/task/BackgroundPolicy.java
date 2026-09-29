package com.wannian.server.kernel.task;

import java.util.Objects;

/**
 * 后台化最终裁决（2.5.5）。
 *
 * <p>模型只提案；本接口决定是否允许 {@code AgentOutcome.BackgroundAccepted}。
 * 无写库副作用。默认实现见 app 层 {@code DefaultBackgroundPolicy}。
 */
public interface BackgroundPolicy {

    /**
     * 裁决提案是否允许进入用户审核 / BackgroundAccepted。
     *
     * @param proposal 非空结构化提案
     * @param context 非空最小上下文（会话状态等）
     * @return Accept 或 Reject；不得为 null
     */
    BackgroundPolicyDecision decide(TaskProposal proposal, BackgroundPolicyContext context);
}
