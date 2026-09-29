package com.wannian.server.kernel.task;

/**
 * {@code sub_agent_run.status} 取值。
 *
 * <p>对齐 guide/04-kernel-reference §4：CREATED → LEASED → RUNNING → 终态。
 * 本号建表不插行；枚举供 V024 CHECK 与后号执行共用。
 */
public enum SubAgentRunStatus {
    /** Run 行已创建，尚未租约。 */
    CREATED,
    /** 已持有 lease。 */
    LEASED,
    /** 正在执行。 */
    RUNNING,
    /** 成功终态。 */
    SUCCEEDED,
    /** 失败终态。 */
    FAILED,
    /** 已取消。 */
    CANCELLED,
    /** lease 丢失 / 过期不可复活。 */
    LOST
}
