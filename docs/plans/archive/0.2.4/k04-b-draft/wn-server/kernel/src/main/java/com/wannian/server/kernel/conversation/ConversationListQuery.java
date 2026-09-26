package com.wannian.server.kernel.conversation;

import com.wannian.server.api.conversation.ConversationStatus;
import java.util.Objects;
import java.util.Optional;

/**
 * @param statusFilter 空 = 默认 ACTIVE
 * @param cursor 上一页末条的 sortKey（activityAt|id）；首页 empty
 * @param limit 1..100
 */
public record ConversationListQuery(
        Optional<ConversationStatus> statusFilter, Optional<String> cursor, int limit) {

    public ConversationListQuery {
        Objects.requireNonNull(statusFilter, "statusFilter");
        Objects.requireNonNull(cursor, "cursor");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit 须在 1..100");
        }
    }

    public static ConversationListQuery defaults(int limit) {
        return new ConversationListQuery(Optional.empty(), Optional.empty(), limit);
    }
}
