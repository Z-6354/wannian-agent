package com.wannian.server.api.conversation;

/**
 * 会话生命周期状态，对应表 {@code conversation.status}。
 *
 * <p>2.4.3：{@link #ACTIVE} / {@link #ARCHIVED} / {@link #TRASHED}。
 */
public enum ConversationStatus {

    /** 可接收新 Turn 的会话。 */
    ACTIVE,

    /** 已归档；保留历史，不接收新 Turn，默认列表排除。 */
    ARCHIVED,

    /** 回收站；默认列表/搜索/最近排除；可恢复或清空。 */
    TRASHED
}
