package com.wannian.server.kernel.task;

/**
 * {@code background_task.status} 状态机取值。
 *
 * <p>本号不写库行；枚举供 V024 CHECK 与后号跃迁共用（D3：一次列全）。
 * 含 guide/04-kernel-reference 中的中间态 {@link #WAITING}、{@link #CANCEL_REQUESTED}。
 * {@code prepare} 产出 Draft 的 {@code initialStatus}：立即={@link #CREATED}，定时未到期={@link #SCHEDULED}。
 */
public enum BackgroundTaskStatus {
    /** 定时未到期，等待 Ticker 晋升。 */
    SCHEDULED,
    /** 已接单可执行（立即，或定时到期晋升后）。 */
    CREATED,
    /** 已入队待 dispatch。 */
    READY,
    /** 等待条件（后号；本号不跃迁至此）。 */
    WAITING,
    /** 正在执行 SubAgentRun。 */
    RUNNING,
    /** 取消已请求、尚未落终态（后号）。 */
    CANCEL_REQUESTED,
    /** 成功终态。 */
    SUCCEEDED,
    /** 失败终态。 */
    FAILED,
    /** 已取消终态。 */
    CANCELLED
}
