package com.wannian.server.app.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wannian.server.kernel.conversation.ConversationListResult;
import com.wannian.server.kernel.conversation.ConversationSummary;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConversationListResponse(
        String result,
        List<ConversationSummaryBody> items,
        String nextCursor,
        String reasonCode,
        String detail) {

    static ConversationListResponse from(ConversationListResult result) {
        return new ConversationListResponse(
                "ok",
                result.items().stream().map(ConversationSummaryBody::from).toList(),
                result.nextCursor().orElse(null),
                null,
                null);
    }

    static ConversationListResponse rejected(String code, String detail) {
        return new ConversationListResponse("rejected", List.of(), null, code, detail);
    }
}
