package com.wannian.server.app.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.conversation.MessageRole;
import com.wannian.server.app.chat.TurnDialogue;
import com.wannian.server.kernel.turn.ReceiveTurnPlan;
import com.wannian.server.kernel.turn.ReceiveTurnResult;
import com.wannian.server.kernel.turn.TurnCommitter;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接收用户回合，并在已启用模型时完成一次直接回答。
 *
 * <p>不执行工具，也不实现 Agent Loop。没有启用模型时回合停在已接收，响应里说明原因。
 */
@RestController
@RequestMapping("/api/conversations/{conversationId}/turns")
public class TurnController {

    private final TurnCommitter turnCommitter;
    private final TurnDialogue turnDialogue;
    private final ObjectMapper objectMapper;

    public TurnController(TurnCommitter turnCommitter, TurnDialogue turnDialogue, ObjectMapper objectMapper) {
        this.turnCommitter = turnCommitter;
        this.turnDialogue = turnDialogue;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<ReceiveTurnResponse> receive(
            @PathVariable String conversationId, @RequestBody(required = false) ReceiveTurnRequest request) {
        Optional<ConversationId> parsedConversation = HttpMapping.conversationId(conversationId);
        if (parsedConversation.isEmpty()) {
            return HttpMapping.rejectedTurn("ILLEGAL_ARGUMENT", "conversationId 不是合法 UUID");
        }
        if (request == null) {
            return HttpMapping.rejectedTurn("ILLEGAL_ARGUMENT", "请求体不能为空");
        }
        if (request.clientRequestId() == null || request.clientRequestId().isBlank()) {
            return HttpMapping.rejectedTurn("ILLEGAL_ARGUMENT", "clientRequestId 不能为空");
        }
        if (request.text() == null || request.text().isBlank()) {
            return HttpMapping.rejectedTurn("ILLEGAL_ARGUMENT", "text 不能为空");
        }

        TurnId turnId;
        if (request.turnId() == null || request.turnId().isBlank()) {
            turnId = TurnId.generate();
        } else {
            Optional<TurnId> parsed = HttpMapping.turnId(request.turnId());
            if (parsed.isEmpty()) {
                return HttpMapping.rejectedTurn("ILLEGAL_ARGUMENT", "turnId 不是合法 UUID");
            }
            turnId = parsed.get();
        }

        MessageId messageId;
        if (request.messageId() == null || request.messageId().isBlank()) {
            messageId = MessageId.generate();
        } else {
            Optional<MessageId> parsed = HttpMapping.messageId(request.messageId());
            if (parsed.isEmpty()) {
                return HttpMapping.rejectedTurn("ILLEGAL_ARGUMENT", "messageId 不是合法 UUID");
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
        if (received instanceof ReceiveTurnResult.Accepted accepted) {
            return switch (turnDialogue.complete(accepted.turnId())) {
                case TurnDialogue.DialogueResult.Replied replied ->
                        HttpMapping.accepted(accepted, replied.text(), null, null);
                case TurnDialogue.DialogueResult.Held held ->
                        HttpMapping.accepted(accepted, null, held.code(), held.detail());
            };
        }
        return HttpMapping.turn(received);
    }

    private String contentJson(String text) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("v", 1);
        node.put("text", text);
        return node.toString();
    }
}
