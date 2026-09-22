package com.wannian.server.app.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.MessageId;
import com.wannian.server.api.common.TurnId;
import com.wannian.server.api.conversation.MessageRole;
import com.wannian.server.api.turn.TurnStatus;
import com.wannian.server.app.chat.CompletedTurnReplyLoader;
import com.wannian.server.app.manage.AgentBudgetSettings;
import com.wannian.server.app.model.EnabledModelPortResolver;
import com.wannian.server.app.model.EnabledModelPortResolver.ResolveResult;
import com.wannian.server.kernel.agent.AgentBudget;
import com.wannian.server.kernel.error.ErrorCodes;
import com.wannian.server.kernel.turn.ExecuteTurn;
import com.wannian.server.kernel.turn.ExecuteTurnResult;
import com.wannian.server.kernel.turn.ReceiveTurnPlan;
import com.wannian.server.kernel.turn.ReceiveTurnResult;
import com.wannian.server.kernel.turn.Turn;
import com.wannian.server.kernel.turn.TurnCommitter;
import com.wannian.server.kernel.turn.TurnEngine;
import com.wannian.server.kernel.turn.TurnRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接收用户回合，并在已启用模型时经 {@link TurnEngine} 完成一轮回答。
 *
 * <p>未启用模型时回合停在 RECEIVED，响应里说明原因。不发 SSE。
 */
@RestController
@RequestMapping("/api/conversations/{conversationId}/turns")
public class TurnController {

    /** 本批固定人设；后续可改为 Companion 配置。 */
    private static final String SYSTEM_INSTRUCTIONS = "你是万年，一个有帮助的助手。";

    /** 本批固定认领租约；后续可进 wannian.json。 */
    private static final Duration CLAIM_LEASE = Duration.ofSeconds(60);

    private final TurnCommitter turnCommitter;
    private final TurnRepository turns;
    private final TurnEngine turnEngine;
    private final EnabledModelPortResolver modelPorts;
    private final AgentBudgetSettings budgetSettings;
    private final CompletedTurnReplyLoader completedReplies;
    private final ObjectMapper objectMapper;

    public TurnController(
            TurnCommitter turnCommitter,
            TurnRepository turns,
            TurnEngine turnEngine,
            EnabledModelPortResolver modelPorts,
            AgentBudgetSettings budgetSettings,
            CompletedTurnReplyLoader completedReplies,
            ObjectMapper objectMapper) {
        this.turnCommitter = turnCommitter;
        this.turns = turns;
        this.turnEngine = turnEngine;
        this.modelPorts = modelPorts;
        this.budgetSettings = budgetSettings;
        this.completedReplies = completedReplies;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<ReceiveTurnResponse> receive(
            @PathVariable String conversationId, @RequestBody(required = false) ReceiveTurnRequest request) {
        Optional<ConversationId> parsedConversation = HttpMapping.conversationId(conversationId);
        if (parsedConversation.isEmpty()) {
            return HttpMapping.rejectedTurn(ErrorCodes.ILLEGAL_ARGUMENT, "conversationId 不是合法 UUID");
        }
        if (request == null) {
            return HttpMapping.rejectedTurn(ErrorCodes.ILLEGAL_ARGUMENT, "请求体不能为空");
        }
        if (request.clientRequestId() == null || request.clientRequestId().isBlank()) {
            return HttpMapping.rejectedTurn(ErrorCodes.ILLEGAL_ARGUMENT, "clientRequestId 不能为空");
        }
        if (request.text() == null || request.text().isBlank()) {
            return HttpMapping.rejectedTurn(ErrorCodes.ILLEGAL_ARGUMENT, "text 不能为空");
        }

        TurnId turnId;
        if (request.turnId() == null || request.turnId().isBlank()) {
            turnId = TurnId.generate();
        } else {
            Optional<TurnId> parsed = HttpMapping.turnId(request.turnId());
            if (parsed.isEmpty()) {
                return HttpMapping.rejectedTurn(ErrorCodes.ILLEGAL_ARGUMENT, "turnId 不是合法 UUID");
            }
            turnId = parsed.get();
        }

        MessageId messageId;
        if (request.messageId() == null || request.messageId().isBlank()) {
            messageId = MessageId.generate();
        } else {
            Optional<MessageId> parsed = HttpMapping.messageId(request.messageId());
            if (parsed.isEmpty()) {
                return HttpMapping.rejectedTurn(ErrorCodes.ILLEGAL_ARGUMENT, "messageId 不是合法 UUID");
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
            return finishAccepted(accepted, request.text());
        }
        return HttpMapping.turn(received);
    }

    private ResponseEntity<ReceiveTurnResponse> finishAccepted(
            ReceiveTurnResult.Accepted accepted, String userMessage) {
        Optional<Turn> found = turns.find(accepted.turnId());
        if (found.isEmpty()) {
            return HttpMapping.accepted(accepted, null, ErrorCodes.TURN_NOT_FOUND, "回合不存在");
        }
        Turn turn = found.get();
        if (turn.status() == TurnStatus.RECEIVED) {
            ResolveResult resolved = modelPorts.resolve();
            if (resolved instanceof ResolveResult.Rejected rejected) {
                return HttpMapping.accepted(accepted, null, rejected.code(), rejected.detail());
            }
        }

        Instant now = Instant.now();
        AgentBudget budget = budgetSettings.createBudget(now);
        ExecuteTurnResult outcome =
                turnEngine.execute(
                        new ExecuteTurn(
                                accepted.turnId(),
                                userMessage,
                                SYSTEM_INSTRUCTIONS,
                                budget,
                                CLAIM_LEASE));
        return switch (outcome) {
            case ExecuteTurnResult.Replied replied ->
                    HttpMapping.accepted(accepted, replied.text(), null, null);
            case ExecuteTurnResult.AlreadyCompleted completed -> {
                Optional<String> text = completedReplies.loadText(completed.turnId());
                if (text.isPresent()) {
                    yield HttpMapping.accepted(accepted, text.get(), null, null);
                }
                yield HttpMapping.accepted(accepted, null, ErrorCodes.REPLY_MISSING, "已完成回合缺少助手正文");
            }
            case ExecuteTurnResult.Cancelled cancelled ->
                    HttpMapping.accepted(accepted, null, "CANCELLED", "回合已取消");
            case ExecuteTurnResult.Held held ->
                    HttpMapping.accepted(accepted, null, held.code(), held.detail());
        };
    }

    private String contentJson(String text) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("v", 1);
        node.put("text", text);
        return node.toString();
    }
}
