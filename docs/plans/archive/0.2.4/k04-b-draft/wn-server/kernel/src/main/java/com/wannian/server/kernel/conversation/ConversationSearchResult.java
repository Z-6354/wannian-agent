package com.wannian.server.kernel.conversation;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.conversation.ConversationStatus;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ConversationSearchResult(
        List<Hit> hits, Optional<String> nextCursor) {

    public ConversationSearchResult {
        Objects.requireNonNull(hits, "hits");
        Objects.requireNonNull(nextCursor, "nextCursor");
        hits = List.copyOf(hits);
    }

    /**
     * @param matchSource title | body
     * @param snippet 截断片段
     * @param messageId 标题占位行可 empty
     */
    public record Hit(
            ConversationId conversationId,
            String title,
            ConversationStatus status,
            String matchSource,
            String snippet,
            Optional<MessageId> messageId) {
        public Hit {
            Objects.requireNonNull(conversationId, "conversationId");
            title = title == null ? "" : title;
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(matchSource, "matchSource");
            snippet = snippet == null ? "" : snippet;
            Objects.requireNonNull(messageId, "messageId");
        }
    }
}
