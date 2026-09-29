package com.wannian.server.kernel.task;

/**
 * Task 来源。
 *
 * <p>本号 {@code prepare} 仅允许 {@link #USER_LOOP}；{@link #SYSTEM} 预留，成功路径拒绝。
 */
public enum TaskSource {
    /** 模型经 Loop 起草。 */
    USER_LOOP,
    /** 系统源预留（本号不真跑）。 */
    SYSTEM
}
