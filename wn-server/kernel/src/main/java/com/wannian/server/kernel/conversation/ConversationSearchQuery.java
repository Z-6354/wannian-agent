package com.wannian.server.kernel.conversation;

import com.wannian.server.api.conversation.ConversationStatus;
import java.util.Objects;
import java.util.Optional;

/**
 * @param query 非空；空白应在 Controller 拦下并走列表
 * @param statusFilter 空 = ACTIVE+ARCHIVED（排除 TRASHED）；显式可含 TRASHED
 */
public record ConversationSearchQuery(
        String query,
        Optional<ConversationStatus> statusFilter,
        Optional<String> cursor,
        int limit) {

    public ConversationSearchQuery {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(statusFilter, "statusFilter");
        Objects.requireNonNull(cursor, "cursor");
        if (limit < 1 || limit > 50) {
            throw new IllegalArgumentException("search limit 须在 1..50");
        }
    }
}
