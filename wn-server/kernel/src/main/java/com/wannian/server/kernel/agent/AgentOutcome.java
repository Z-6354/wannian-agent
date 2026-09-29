package com.wannian.server.kernel.agent;

import com.wannian.server.kernel.model.ModelUsage;
import com.wannian.server.kernel.task.TaskProposal;
import java.util.Objects;

/**
 * Agent Loop 的封闭结果。只表达本轮决策结果，不表示已向用户正式完成。
 *
 * <p>TurnEngine 将本结果转为 {@code CommitTurnPlan} 后由 TurnCommitter 提交；
 * Loop 不得自行写库或发 SSE。编程缺陷仍走异常通道。
 *
 * <p>2.5.5：{@link BackgroundAccepted} 携带结构化 {@link TaskProposal}；须经用户审核后才落库。
 */
public sealed interface AgentOutcome {

    /**
     * 模型形成最终回答；正文由实现校验（空白应收口为 ControlledFailure）。
     *
     * @param text 助手最终正文；不得为 null（空白由 Loop 另行收口）
     * @param modelUsage 累计 token 用量；null 视为 {@code (0, 0)}
     * @param trace 脱敏步骤记录；不得含密钥或私密全文
     */
    record FinalResponse(String text, ModelUsage modelUsage, AgentTrace trace) implements AgentOutcome {
        public FinalResponse {
            Objects.requireNonNull(text, "text");
            modelUsage = modelUsage == null ? new ModelUsage(0, 0) : modelUsage;
            Objects.requireNonNull(trace, "trace");
        }
    }

    /**
     * 工作应转后台任务；须用户确认后才 {@code prepare}+落库。
     *
     * @param proposal 结构化任务提案
     * @param acknowledgementText 待审卡片预览短文（非空）；确认回合助手气泡用系统固定文案
     * @param trace 脱敏步骤记录；不得含密钥或私密全文
     */
    record BackgroundAccepted(TaskProposal proposal, String acknowledgementText, AgentTrace trace)
            implements AgentOutcome {
        public BackgroundAccepted {
            Objects.requireNonNull(proposal, "proposal");
            Objects.requireNonNull(acknowledgementText, "acknowledgementText");
            Objects.requireNonNull(trace, "trace");
            acknowledgementText = acknowledgementText.trim();
            if (acknowledgementText.isEmpty()) {
                throw new IllegalArgumentException("acknowledgementText 不能为空");
            }
        }
    }

    /**
     * 受控业务失败（预算打满、无效模型输出、真实 Refusal/Failure 映射等）。
     *
     * @param errorCode 稳定机器码（全进程唯一登记处）；不得为 null
     * @param safeUserMessage 可展示给用户的短文案；不得含密钥 / SQL / 堆栈
     * @param retryable 调用方是否可按策略重试（如限流）
     * @param trace 脱敏步骤记录；不得含密钥或私密全文
     */
    record ControlledFailure(String errorCode, String safeUserMessage, boolean retryable, AgentTrace trace)
            implements AgentOutcome {
        public ControlledFailure {
            Objects.requireNonNull(errorCode, "errorCode");
            Objects.requireNonNull(safeUserMessage, "safeUserMessage");
            Objects.requireNonNull(trace, "trace");
        }
    }

    /**
     * 在合法取消点停止；取消不得把 COMMITTING 打回。
     *
     * @param trace 脱敏步骤记录；不得含密钥或私密全文
     */
    record Cancelled(AgentTrace trace) implements AgentOutcome {
        public Cancelled {
            Objects.requireNonNull(trace, "trace");
        }
    }
}
