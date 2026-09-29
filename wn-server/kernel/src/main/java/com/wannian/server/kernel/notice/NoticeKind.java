package com.wannian.server.kernel.notice;

/**
 * 消息中心通知种类（2.5.10）。
 *
 * <p>{@code TASK_SCHEDULED} 仅枚举位；本号生产者用 {@link #TASK_COMPLETED} + payload.scheduled（M3=A）。
 * {@link #VERSION} 仅预留，无生产者（M5=A）。
 */
public enum NoticeKind {
    ARCHIVE,
    TASK_COMPLETED,
    TASK_SCHEDULED,
    TASK_FAILED,
    VERSION,
    ERROR
}
