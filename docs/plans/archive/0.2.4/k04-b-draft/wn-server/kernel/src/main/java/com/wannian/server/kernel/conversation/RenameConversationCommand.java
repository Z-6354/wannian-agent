package com.wannian.server.kernel.conversation;

import com.wannian.server.api.common.ConversationId;
import java.util.Objects;

public record RenameConversationCommand(
        ConversationId conversationId, long expectedRevision, String title) {

    public RenameConversationCommand {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(title, "title");
    }
}
