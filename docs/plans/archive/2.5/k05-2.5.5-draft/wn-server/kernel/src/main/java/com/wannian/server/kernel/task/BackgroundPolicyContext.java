package com.wannian.server.kernel.task;

import com.wannian.server.api.conversation.ConversationStatus;
import java.util.Objects;

/**
 * {@link BackgroundPolicy#decide} 最小上下文（2.5.5）。
 *
 * @param conversationStatus 可空；非 {@link ConversationStatus#ACTIVE} 时默认 Reject
 */
public record BackgroundPolicyContext(ConversationStatus conversationStatus) {

    public BackgroundPolicyContext {
        // conversationStatus 可空
    }

    /** 无会话状态信息时的空上下文。 */
    public static BackgroundPolicyContext empty() {
        return new BackgroundPolicyContext(null);
    }

    public static BackgroundPolicyContext of(ConversationStatus status) {
        return new BackgroundPolicyContext(Objects.requireNonNull(status, "status"));
    }
}
