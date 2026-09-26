package com.wannian.server.kernel.conversation;

import com.wannian.server.api.common.ConversationId;
import java.util.Objects;
import java.util.Optional;

/**
 * @param afterSeq 仅返回 sequence_no &gt; afterSeq；empty 从头
 * @param limit 1..100
 */
public record ConversationHistoryQuery(
        ConversationId conversationId, Optional<Integer> afterSeq, int limit) {

    public ConversationHistoryQuery {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(afterSeq, "afterSeq");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit 须在 1..100");
        }
    }
}
