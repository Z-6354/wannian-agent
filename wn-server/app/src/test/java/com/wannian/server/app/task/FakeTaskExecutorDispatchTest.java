package com.wannian.server.app.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.wannian.server.api.common.BackgroundTaskId;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.SubAgentRunId;
import com.wannian.server.kernel.task.CancelDispatchResult;
import com.wannian.server.kernel.task.DispatchResult;
import com.wannian.server.kernel.task.SubAgentRunResult;
import com.wannian.server.kernel.task.SubAgentRunSpec;
import com.wannian.server.kernel.task.TaskExecutor;
import com.wannian.server.kernel.task.TaskType;
import org.junit.jupiter.api.Test;

/** S4：Runtime 只依赖 TaskExecutor 接口回报；Accepted 须可携带 result。 */
class FakeTaskExecutorDispatchTest {

    @Test
    void fakeSyncExecutorReturnsAcceptedWithResult() {
        TaskExecutor executor = new FakeSyncExecutor();
        SubAgentRunId runId = SubAgentRunId.generate();
        BackgroundTaskId taskId = BackgroundTaskId.generate();
        SubAgentRunSpec spec =
                new SubAgentRunSpec(
                        taskId,
                        runId,
                        1,
                        "lease-token",
                        SubAgentRunSpec.LOCAL_EXECUTOR_ID,
                        TaskType.USER_SCHEDULED_NOTIFY,
                        "{\"message\":\"看微信\"}",
                        ConversationId.generate(),
                        null);

        DispatchResult result = executor.dispatch(spec);

        assertThat(result).isInstanceOf(DispatchResult.Accepted.class);
        DispatchResult.Accepted accepted = (DispatchResult.Accepted) result;
        assertThat(accepted.runId()).isEqualTo(runId);
        assertThat(accepted.result()).isNotNull();
        assertThat(accepted.result().succeeded()).isTrue();
        assertThat(accepted.result().resultJson()).contains("fired");
    }

    @Test
    void acceptedWithoutConcreteLocalClassIsStillUsableByRuntimePattern() {
        TaskExecutor executor = new FakeSyncExecutor();
        SubAgentRunId runId = SubAgentRunId.generate();
        SubAgentRunSpec spec =
                new SubAgentRunSpec(
                        BackgroundTaskId.generate(),
                        runId,
                        1,
                        "lease",
                        SubAgentRunSpec.LOCAL_EXECUTOR_ID,
                        TaskType.USER_SCHEDULED_NOTIFY,
                        "{\"message\":\"x\"}",
                        ConversationId.generate(),
                        null);

        DispatchResult dispatched = executor.dispatch(spec);
        // 与 DefaultTaskRuntime 相同：只认接口封闭类型，不认 LocalTaskExecutor
        assertThat(executor).isNotInstanceOf(LocalTaskExecutor.class);
        if (dispatched instanceof DispatchResult.Accepted accepted && accepted.result() != null) {
            assertThat(accepted.result().runId()).isEqualTo(runId);
        } else {
            throw new AssertionError("同步 Fake 必须带回 result");
        }
    }

    /** 仅实现接口的同步执行器（无 LocalTaskExecutor 私有缓存）。 */
    static final class FakeSyncExecutor implements TaskExecutor {
        @Override
        public DispatchResult dispatch(SubAgentRunSpec spec) {
            SubAgentRunResult result =
                    SubAgentRunResult.success(
                            spec.taskId(),
                            spec.runId(),
                            spec.attemptNo(),
                            spec.leaseToken(),
                            "{\"kind\":\"USER_SCHEDULED_NOTIFY\",\"status\":\"fired\",\"message\":\"看微信\"}");
            return new DispatchResult.Accepted(spec.runId(), result);
        }

        @Override
        public CancelDispatchResult cancel(SubAgentRunId runId, String leaseToken) {
            return new CancelDispatchResult.NotRunning(runId);
        }
    }
}
