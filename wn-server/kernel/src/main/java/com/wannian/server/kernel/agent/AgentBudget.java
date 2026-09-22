package com.wannian.server.kernel.agent;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单次 Agent Loop 尝试的执行闸门：决策次数、软硬截止与取消令牌。
 *
 * <p>与 {@link AgentInput} 分列：Input 是冻结的上下文材料，Budget 是本轮尝试的控制面。
 * 由 TurnEngine / 调用方提供，不由 ContextAssembler 拼装。
 *
 * <p>次数与秒数不得在本类型内写死；由 app 配置变量（数据目录 {@code wannian.json} 的
 * {@code agentBudget} / 管理页）注入后，
 * 经 {@link #of(int, int, int, Instant)} 构造。
 *
 * @param maxModelDecisions 允许的模型 {@code decide} 次数上界；须为正
 * @param softDeadline 软截止；到达后宜收口或告警，仍可在硬截止前完成当前步
 * @param hardDeadline 硬截止；到达或超过后不得再发起新的 decide；须不早于 softDeadline
 * @param cancelToken 取消令牌；Loop 在 decide 前检查；置位后应收口为 {@link AgentOutcome.Cancelled}
 */
public record AgentBudget(
        int maxModelDecisions, Instant softDeadline, Instant hardDeadline, CancelToken cancelToken) {

    public AgentBudget {
        if (maxModelDecisions <= 0) {
            throw new IllegalArgumentException("maxModelDecisions 须为正");
        }
        Objects.requireNonNull(softDeadline, "softDeadline");
        Objects.requireNonNull(hardDeadline, "hardDeadline");
        Objects.requireNonNull(cancelToken, "cancelToken");
        if (hardDeadline.isBefore(softDeadline)) {
            throw new IllegalArgumentException("hardDeadline 不得早于 softDeadline");
        }
    }

    /**
     * 用配置变量构造本轮预算：截止 = {@code now} + 对应秒数；新取消令牌。
     *
     * @param maxModelDecisions 配置中的 decide 上界
     * @param softDeadlineSeconds 配置中的软截止秒数
     * @param hardDeadlineSeconds 配置中的硬截止秒数
     * @param now 本轮尝试起点
     */
    public static AgentBudget of(
            int maxModelDecisions, int softDeadlineSeconds, int hardDeadlineSeconds, Instant now) {
        Objects.requireNonNull(now, "now");
        if (softDeadlineSeconds <= 0) {
            throw new IllegalArgumentException("softDeadlineSeconds 须为正");
        }
        if (hardDeadlineSeconds < softDeadlineSeconds) {
            throw new IllegalArgumentException("hardDeadlineSeconds 不得小于 softDeadlineSeconds");
        }
        return new AgentBudget(
                maxModelDecisions,
                now.plusSeconds(softDeadlineSeconds),
                now.plusSeconds(hardDeadlineSeconds),
                new CancelToken());
    }

    /**
     * 本轮尝试的取消令牌。记录本身不可变，令牌内部状态可被外部置位。
     *
     * <p>取消与 {@code COMMITTING} 的竞态由 TurnEngine CAS 处理；普通取消不得打回已冻结计划。
     */
    public static final class CancelToken {

        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        /** @return 是否已请求取消 */
        public boolean isCancelled() {
            return cancelled.get();
        }

        /** 请求取消；幂等。 */
        public void cancel() {
            cancelled.set(true);
        }
    }
}
