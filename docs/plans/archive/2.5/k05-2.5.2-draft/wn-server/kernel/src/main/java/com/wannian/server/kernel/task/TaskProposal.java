package com.wannian.server.kernel.task;

import java.util.Objects;

/**
 * {@link TaskRuntime#prepare} 入参（P2 §4.5）。
 *
 * <p>{@code scheduleSpec == null} 表示立即；非空表示定时。
 * 是否多次由 spec 推断（{@link ScheduleSpec#recurring()}），<strong>不</strong>另开布尔字段。
 * {@code retryPolicyJson} 可空，空则 prepare 填默认常量。
 */
public record TaskProposal(
        TaskSource source,
        TaskType taskType,
        NotifyPolicy notifyPolicy,
        String inputJson,
        ScheduleSpec scheduleSpec,
        OriginTurn origin,
        String retryPolicyJson) {

    /**
     * @param source 非空
     * @param taskType 非空
     * @param notifyPolicy 非空（调用方默认 {@link NotifyPolicy#USER_VISIBLE}）
     * @param inputJson 非空（进一步长度/组合由 prepare 校验）
     * @param scheduleSpec 可空=立即
     * @param origin 非空
     * @param retryPolicyJson 可空
     */
    public TaskProposal {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(taskType, "taskType");
        Objects.requireNonNull(notifyPolicy, "notifyPolicy");
        Objects.requireNonNull(inputJson, "inputJson");
        Objects.requireNonNull(origin, "origin");
    }
}
