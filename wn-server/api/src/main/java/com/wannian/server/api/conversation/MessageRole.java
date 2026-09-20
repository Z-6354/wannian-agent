package com.wannian.server.api.conversation;

/**
 * 消息角色，对应表 {@code message.role}（库中存枚举名字符串）。
 *
 * <p>本批仅 USER / ASSISTANT；SYSTEM、TOOL 等待真实写入需求再扩展，避免空枚举膨胀。
 *
 * <p>持久化层应写入 {@link #name()}。
 */
public enum MessageRole {

    /** 用户输入。 */
    USER,

    /** 助手（烟火）回复。 */
    ASSISTANT
}
