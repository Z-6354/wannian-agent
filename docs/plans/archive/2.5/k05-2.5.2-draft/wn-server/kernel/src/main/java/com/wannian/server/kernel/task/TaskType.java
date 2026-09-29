package com.wannian.server.kernel.task;

/**
 * Task 类型。
 *
 * <p>本号 {@code prepare}：{@link #READ_ONLY_TOOL_BATCH} 允许（立即或定时）；
 * {@link #USER_SCHEDULED_NOTIFY} 允许但须带定时 spec；
 * {@link #WORLD_TICK} / {@link #MEMORY_REVIEW} 占位，成功路径拒绝。
 */
public enum TaskType {
    /** 只读长工具批。 */
    READ_ONLY_TOOL_BATCH,
    /** 定时到点后以提醒/汇报为主（可无工具或空批）；须定时。 */
    USER_SCHEDULED_NOTIFY,
    /** 世界侧占位（本号不真跑）。 */
    WORLD_TICK,
    /** Memory Review 占位（本号不真跑）。 */
    MEMORY_REVIEW
}
