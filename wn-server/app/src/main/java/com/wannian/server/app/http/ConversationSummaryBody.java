package com.wannian.server.app.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wannian.server.kernel.conversation.ConversationSummary;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConversationSummaryBody(
        String id,
        String title,
        String status,
        String titleSource,
        long revision,
        String createdAt,
        String updatedAt,
        String lastActivityAt,
        String trashedAt,
        boolean pinned) {

    static ConversationSummaryBody from(ConversationSummary s) {
        return new ConversationSummaryBody(
                s.id().asString(),
                s.title(),
                s.status().name(),
                s.titleSource().name(),
                s.revision(),
                s.createdAt(),
                s.updatedAt(),
                blankToNull(s.lastActivityAt()),
                blankToNull(s.trashedAt()),
                s.pinned());
    }

    private static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }
}
