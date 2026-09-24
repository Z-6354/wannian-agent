package com.wannian.server.app.http;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.kernel.conversation.CreateConversationResult;
import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.turn.ReceiveTurnResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** 把领域结果映射成 HTTP。不改 Turn 状态，不调用模型。 */
final class HttpMapping {

    private HttpMapping() {}

    static ResponseEntity<CreateConversationResponse> conversation(CreateConversationResult result) {
        return switch (result) {
            case CreateConversationResult.Created created ->
                    ResponseEntity.status(HttpStatus.CREATED)
                            .body(new CreateConversationResponse(
                                    "created", created.conversationId().asString(), null, null));
            case CreateConversationResult.AlreadyExists existing ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(new CreateConversationResponse(
                                    "already_exists", existing.conversationId().asString(), null, null));
            case CreateConversationResult.Rejected rejected ->
                    ResponseEntity.status(statusFor(rejected.reasonCode()))
                            .body(new CreateConversationResponse(
                                    "rejected", null, rejected.reasonCode(), rejected.detail()));
        };
    }

    static ResponseEntity<ReceiveTurnResponse> turn(ReceiveTurnResult result) {
        return switch (result) {
            case ReceiveTurnResult.Accepted accepted ->
                    ResponseEntity.status(accepted.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                            .body(new ReceiveTurnResponse(
                                    "accepted",
                                    accepted.turnId().asString(),
                                    accepted.replayed(),
                                    null,
                                    null,
                                    null,
                                    null));
            case ReceiveTurnResult.Conflict conflict ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(new ReceiveTurnResponse(
                                    "conflict",
                                    conflict.existingTurnId().asString(),
                                    null,
                                    ErrorCodes.CLIENT_REQUEST_CONFLICT,
                                    conflict.detail(),
                                    null,
                                    null));
            case ReceiveTurnResult.Rejected rejected ->
                    ResponseEntity.status(statusFor(rejected.reasonCode()))
                            .body(new ReceiveTurnResponse(
                                    "rejected",
                                    null,
                                    null,
                                    rejected.reasonCode(),
                                    rejected.detail(),
                                    null,
                                    null));
        };
    }

    static ResponseEntity<ReceiveTurnResponse> accepted(
            ReceiveTurnResult.Accepted accepted,
            String reply,
            String reasonCode,
            String detail,
            List<ToolCallView> toolCalls) {
        List<ToolCallView> tools =
                toolCalls == null || toolCalls.isEmpty() ? null : List.copyOf(toolCalls);
        return ResponseEntity.status(accepted.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(new ReceiveTurnResponse(
                        "accepted",
                        accepted.turnId().asString(),
                        accepted.replayed(),
                        reasonCode,
                        detail,
                        reply,
                        tools));
    }

    static ResponseEntity<ReceiveTurnResponse> rejectedTurn(String reasonCode, String detail) {
        return ResponseEntity.status(statusFor(reasonCode))
                .body(new ReceiveTurnResponse("rejected", null, null, reasonCode, detail, null, null));
    }

    static ResponseEntity<CreateConversationResponse> rejectedConversation(String reasonCode, String detail) {
        return ResponseEntity.status(statusFor(reasonCode))
                .body(new CreateConversationResponse("rejected", null, reasonCode, detail));
    }

    static Optional<ConversationId> conversationId(String raw) {
        return uuid(raw).map(ConversationId::new);
    }

    static Optional<TurnId> turnId(String raw) {
        return uuid(raw).map(TurnId::new);
    }

    static Optional<MessageId> messageId(String raw) {
        return uuid(raw).map(MessageId::new);
    }

    private static Optional<UUID> uuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw.trim()));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static HttpStatus statusFor(String reasonCode) {
        return switch (reasonCode) {
            case ErrorCodes.CONVERSATION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ErrorCodes.PERSISTENCE_FAILED, ErrorCodes.RETRYABLE_BUSY -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
