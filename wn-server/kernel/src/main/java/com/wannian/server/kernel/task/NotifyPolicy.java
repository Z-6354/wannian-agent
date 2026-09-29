package com.wannian.server.kernel.task;

/**
 * 完成/触发后的通知策略。
 *
 * <p>本号 {@code prepare} 成功路径默认 {@link #USER_VISIBLE}；{@link #SILENT} 预留。
 */
public enum NotifyPolicy {
    /** 用户向可见（默认）。 */
    USER_VISIBLE,
    /** 静默（预留）。 */
    SILENT
}
