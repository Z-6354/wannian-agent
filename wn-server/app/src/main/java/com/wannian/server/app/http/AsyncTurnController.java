package com.wannian.server.app.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.conversation.MessageRole;
import com.wannian.server.api.turn.TurnStatus;
import com.wannian.server.app.stream.DurableTurnScheduler;
import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.turn.ReceiveTurnPlan;
import com.wannian.server.kernel.turn.ReceiveTurnResult;
import com.wannian.server.kernel.turn.Turn;
import com.wannian.server.kernel.turn.TurnCommitter;
import com.wannian.server.kernel.turn.TurnRepository;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 异步 receive：只落库并唤醒调度，不等待模型。
 */
@RestController
@RequestMapping("/api/conversations/{conversationId}/turns")
public class AsyncTurnController {

    private final TurnCommitter turnCommitter;
    private final TurnRepository turns;
    private final DurableTurnScheduler scheduler;
    private final ObjectMapper objectMapper;

    public AsyncTurnController(
            TurnCommitter turnCommitter,
            TurnRepository turns,
            DurableTurnScheduler scheduler,
            ObjectMapper objectMapper) {
        this.turnCommitter = turnCommitter;
        this.turns = turns;
        this.scheduler = scheduler;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/async")
    public ResponseEntity<AsyncReceiveResponse> receiveAsync(
            @PathVariable String conversationId,
            @RequestBody(required = false) ReceiveTurnRequest request) {
        Optional<ConversationId> parsedConversation = HttpMapping.conversationId(conversationId);
        if (parsedConversation.isEmpty()) {
            return asyncRejected(ErrorCodes.ILLEGAL_ARGUMENT, "conversationId 不是合法 UUID");
        }
        if (request == null) {
            return asyncRejected(ErrorCodes.ILLEGAL_ARGUMENT, "请求体不能为空");
        }
        if (request.clientRequestId() == null || request.clientRequestId().isBlank()) {
            return asyncRejected(ErrorCodes.ILLEGAL_ARGUMENT, "clientRequestId 不能为空");
        }
        if (request.text() == null || request.text().isBlank()) {
            return asyncRejected(ErrorCodes.ILLEGAL_ARGUMENT, "text 不能为空");
        }

        TurnId turnId;
        if (request.turnId() == null || request.turnId().isBlank()) {
            turnId = TurnId.generate();
        } else {
            Optional<TurnId> parsed = HttpMapping.turnId(request.turnId());
            if (parsed.isEmpty()) {
                return asyncRejected(ErrorCodes.ILLEGAL_ARGUMENT, "turnId 不是合法 UUID");
            }
            turnId = parsed.get();
        }

        MessageId messageId;
        if (request.messageId() == null || request.messageId().isBlank()) {
            messageId = MessageId.generate();
        } else {
            Optional<MessageId> parsed = HttpMapping.messageId(request.messageId());
            if (parsed.isEmpty()) {
                return asyncRejected(ErrorCodes.ILLEGAL_ARGUMENT, "messageId 不是合法 UUID");
            }
            messageId = parsed.get();
        }

        ReceiveTurnPlan plan =
                new ReceiveTurnPlan(
                        parsedConversation.get(),
                        request.clientRequestId(),
                        turnId,
                        new ReceiveTurnPlan.UserMessageDraft(
                                messageId, MessageRole.USER, contentJson(request.text()), 0));
        ReceiveTurnResult received = turnCommitter.receive(plan);
        return switch (received) {
            case ReceiveTurnResult.Accepted accepted -> {
                scheduler.wake();
                String status = TurnStatus.RECEIVED.name();
                Optional<Turn> found = turns.find(accepted.turnId());
                if (found.isPresent()) {
                    status = found.get().status().name();
                }
                yield ResponseEntity.status(accepted.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                        .body(
                                new AsyncReceiveResponse(
                                        "accepted",
                                        parsedConversation.get().asString(),
                                        accepted.turnId().asString(),
                                        status,
                                        accepted.replayed(),
                                        null,
                                        null));
            }
            case ReceiveTurnResult.Conflict conflict ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(
                                    new AsyncReceiveResponse(
                                            "conflict",
                                            parsedConversation.get().asString(),
                                            conflict.existingTurnId().asString(),
                                            null,
                                            null,
                                            ErrorCodes.CLIENT_REQUEST_CONFLICT,
                                            conflict.detail()));
            case ReceiveTurnResult.Rejected rejected ->
                    ResponseEntity.status(HttpMapping.statusForPublic(rejected.reasonCode()))
                            .body(
                                    new AsyncReceiveResponse(
                                            "rejected",
                                            parsedConversation.get().asString(),
                                            null,
                                            null,
                                            null,
                                            rejected.reasonCode(),
                                            rejected.detail()));
        };
    }

    private static ResponseEntity<AsyncReceiveResponse> asyncRejected(String code, String detail) {
        return ResponseEntity.status(HttpMapping.statusForPublic(code))
                .body(new AsyncReceiveResponse("rejected", null, null, null, null, code, detail));
    }

    private String contentJson(String text) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("v", 1);
        node.put("text", text);
        return node.toString();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AsyncReceiveResponse(
            String result,
            String conversationId,
            String turnId,
            String status,
            Boolean replayed,
            String reasonCode,
            String detail) {}
}
