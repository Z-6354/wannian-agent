package com.wannian.server.app.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wannian.server.kernel.conversation.ConversationSearchResult;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConversationSearchResponse(
        String result,
        List<HitBody> hits,
        String nextCursor,
        String reasonCode,
        String detail) {

    static ConversationSearchResponse from(ConversationSearchResult result) {
        return new ConversationSearchResponse(
                "ok",
                result.hits().stream()
                        .map(
                                h ->
                                        new HitBody(
                                                h.conversationId().asString(),
                                                h.title(),
                                                h.status().name(),
                                                h.matchSource(),
                                                h.snippet(),
                                                h.messageId().map(id -> id.asString()).orElse(null)))
                        .toList(),
                result.nextCursor().orElse(null),
                null,
                null);
    }

    static ConversationSearchResponse rejected(String code, String detail) {
        return new ConversationSearchResponse("rejected", List.of(), null, code, detail);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HitBody(
            String conversationId,
            String title,
            String status,
            String matchSource,
            String snippet,
            String messageId) {}
}
