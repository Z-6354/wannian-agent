package com.wannian.server.app.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wannian.server.kernel.conversation.ConversationSummary;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConversationDetailResponse(
        String result,
        ConversationSummaryBody conversation,
        Long outboxCursor,
        String reasonCode,
        String detail) {

    static ConversationDetailResponse from(ConversationSummary summary) {
        return new ConversationDetailResponse("ok", ConversationSummaryBody.from(summary), null, null, null);
    }

    static ConversationDetailResponse from(ConversationSummary summary, long outboxCursor) {
        return new ConversationDetailResponse(
                "ok", ConversationSummaryBody.from(summary), outboxCursor, null, null);
    }

    static ConversationDetailResponse rejected(String code, String detail) {
        return new ConversationDetailResponse("rejected", null, null, code, detail);
    }
}
