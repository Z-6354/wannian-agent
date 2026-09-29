package com.wannian.server.kernel.task;

/**
 * 后台执行槽门闩（2.5.4 P2 §4.5 / §5.5）。
 *
 * <p>约束：{@code activeBgRuns + activeReview ≤ 1}。不管模型并发（多发送窗口）。
 */
public interface BackgroundConcurrencyGate {

    /** 当前是否允许新派一个 Background Run。 */
    boolean canStartBackgroundRun();
}
