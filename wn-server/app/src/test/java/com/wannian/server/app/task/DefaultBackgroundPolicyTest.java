package com.wannian.server.app.task;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.kernel.task.BackgroundPolicyContext;
import com.wannian.server.kernel.task.BackgroundPolicyDecision;
import com.wannian.server.kernel.task.NotifyPolicy;
import com.wannian.server.kernel.task.OriginTurn;
import com.wannian.server.kernel.task.TaskProposal;
import com.wannian.server.kernel.task.TaskSource;
import com.wannian.server.kernel.task.TaskType;
import org.junit.jupiter.api.Test;

class DefaultBackgroundPolicyTest {

    @Test
    void worldTickCannotEnterUserTaskFlow() {
        OriginTurn origin = new OriginTurn(TurnId.generate(), ConversationId.generate(), null);
        TaskProposal proposal = new TaskProposal(
                TaskSource.USER_LOOP,
                TaskType.WORLD_TICK,
                NotifyPolicy.USER_VISIBLE,
                "{}",
                null,
                origin,
                null);

        assertInstanceOf(
                BackgroundPolicyDecision.Reject.class,
                new DefaultBackgroundPolicy().decide(proposal, BackgroundPolicyContext.empty()));
    }
}
