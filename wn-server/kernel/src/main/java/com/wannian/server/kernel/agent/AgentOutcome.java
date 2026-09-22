package com.wannian.server.kernel.agent;

import com.wannian.server.kernel.model.ModelUsage;
import java.util.Objects;

/**
 * Agent Loop 的封闭结果。只表达本轮决策结果，不表示已向用户正式完成。
 *
 * <p>TurnEngine 将本结果转为 {@code CommitTurnPlan} 后由 TurnCommitter 提交；
 * Loop 不得自行写库或发 SSE。编程缺陷仍走异常通道。
 *
 * <p>{@link BackgroundAccepted} 在 0.2.1 为占位形状；完整 BackgroundTask 语义后续批次再接。
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
     * 工作应转后台任务；0.2.1 占位。
     *
     * @param taskProposal 不透明任务提案（后续接 TaskRuntime 再定型）
     * @param acknowledgementText 可先回给用户的确认文案
     * @param trace 脱敏步骤记录；不得含密钥或私密全文
     */
    record BackgroundAccepted(String taskProposal, String acknowledgementText, AgentTrace trace)
            implements AgentOutcome {
        public BackgroundAccepted {
            Objects.requireNonNull(taskProposal, "taskProposal");
            Objects.requireNonNull(acknowledgementText, "acknowledgementText");
            Objects.requireNonNull(trace, "trace");
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
