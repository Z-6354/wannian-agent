package com.wannian.server.kernel.conversation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ConversationListResult(
        List<ConversationSummary> items, Optional<String> nextCursor) {

    public ConversationListResult {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(nextCursor, "nextCursor");
        items = List.copyOf(items);
    }
}
