package com.wannian.server.api.conversation;

/**
 * 会话生命周期状态，对应表 {@code conversation.status}。
 *
 * <p>本批仅 {@link #ACTIVE}；归档等状态待产品需要时再扩展。
 */
public enum ConversationStatus {

    /** 可接收新 Turn 的会话。 */
    ACTIVE
}
