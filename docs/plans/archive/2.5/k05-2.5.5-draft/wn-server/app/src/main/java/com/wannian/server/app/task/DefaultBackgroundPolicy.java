package com.wannian.server.app.task;

import com.wannian.server.api.conversation.ConversationStatus;
import com.wannian.server.kernel.task.BackgroundPolicy;
import com.wannian.server.kernel.task.BackgroundPolicyContext;
import com.wannian.server.kernel.task.BackgroundPolicyDecision;
import com.wannian.server.kernel.task.TaskProposal;
import com.wannian.server.kernel.task.TaskSource;
import com.wannian.server.kernel.task.TaskType;
import java.util.Objects;

/**
 * 默认后台化裁决（2.5.5）：拒绝 SYSTEM / WORLD_TICK / MEMORY_REVIEW；会话非 ACTIVE 拒绝。
 */
public final class DefaultBackgroundPolicy implements BackgroundPolicy {

    @Override
    public BackgroundPolicyDecision decide(TaskProposal proposal, BackgroundPolicyContext context) {
        Objects.requireNonNull(proposal, "proposal");
        Objects.requireNonNull(context, "context");

        if (proposal.source() != TaskSource.USER_LOOP) {
            return new BackgroundPolicyDecision.Reject("仅允许 USER_LOOP 来源");
        }
        TaskType type = proposal.taskType();
        if (type == TaskType.WORLD_TICK || type == TaskType.MEMORY_REVIEW) {
            return new BackgroundPolicyDecision.Reject("taskType=" + type + " 本路径不可后台化");
        }
        if (type == TaskType.USER_SCHEDULED_NOTIFY && proposal.scheduleSpec() == null) {
            return new BackgroundPolicyDecision.Reject("USER_SCHEDULED_NOTIFY 须带定时");
        }
        if (proposal.inputJson() == null || proposal.inputJson().isBlank()) {
            return new BackgroundPolicyDecision.Reject("inputJson 不能为空");
        }
        ConversationStatus status = context.conversationStatus();
        if (status != null && status != ConversationStatus.ACTIVE) {
            return new BackgroundPolicyDecision.Reject("会话状态为 " + status + "，不能创建后台任务");
        }
        return BackgroundPolicyDecision.Accept.of();
    }
}
