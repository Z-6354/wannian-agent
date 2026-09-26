package com.wannian.server.app.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConversationMessagesResponse(
        String result,
        List<MessageBody> messages,
        Integer nextAfterSeq,
        String reasonCode,
        String detail) {

    static ConversationMessagesResponse rejected(String code, String detail) {
        return new ConversationMessagesResponse("rejected", List.of(), null, code, detail);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MessageBody(
            String id,
            String role,
            String text,
            int sequenceNo,
            String turnId,
            String createdAt,
            List<ToolCallView> toolCalls,
            String personaId) {}
}
