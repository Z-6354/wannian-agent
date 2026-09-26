package com.wannian.server.app.http;

import com.wannian.server.kernel.conversation.ConversationHistoryResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.wannian.server.kernel.persona.ConversationPersonaBinding;
import com.wannian.server.api.common.TurnId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 历史消息 HTTP 组装：公开 text + 可选按 turn 聚合的安全工具投影。
 */
@Component
public class ConversationHistoryAssembler {

    private final TurnToolCallProjector toolCallProjector;
    private ConversationPersonaBinding personaBindings;

    public ConversationHistoryAssembler(TurnToolCallProjector toolCallProjector) {
        this.toolCallProjector = Objects.requireNonNull(toolCallProjector, "toolCallProjector");
    }

    @Autowired(required=false) public void setPersonaBindings(ConversationPersonaBinding bindings) { this.personaBindings=bindings; }

    ConversationMessagesResponse toResponse(
            ConversationHistoryResult.Ok ok, boolean includeToolCalls) {
        Map<String, List<ToolCallView>> byTurn = new LinkedHashMap<>();
        if (includeToolCalls) {
            for (ConversationHistoryResult.HistoryMessage msg : ok.messages()) {
                if (msg.turnId() == null || msg.turnId().isBlank()) {
                    continue;
                }
                byTurn.computeIfAbsent(
                        msg.turnId(), turnId -> toolCallProjector.listForTurn(turnId));
            }
        }
        List<ConversationMessagesResponse.MessageBody> bodies = new ArrayList<>();
        for (ConversationHistoryResult.HistoryMessage msg : ok.messages()) {
            List<ToolCallView> tools =
                    includeToolCalls && msg.turnId() != null && !msg.turnId().isBlank()
                            ? byTurn.getOrDefault(msg.turnId(), List.of())
                            : null;
            if (tools != null && tools.isEmpty()) {
                tools = null;
            }
            bodies.add(
                    new ConversationMessagesResponse.MessageBody(
                            msg.id().asString(),
                            msg.role().name(),
                            msg.text(),
                            msg.sequenceNo(),
                            blankToNull(msg.turnId()),
                            blankToNull(msg.createdAt()),
                            tools,
                            personaBindings==null || msg.turnId()==null || msg.turnId().isBlank() ? null : personaBindings.personaIdForTurn(new TurnId(java.util.UUID.fromString(msg.turnId()))).map(p->p.value()).orElse("yanhuo")));
        }
        return new ConversationMessagesResponse(
                "ok",
                List.copyOf(bodies),
                ok.nextAfterSeq().orElse(null),
                null,
                null);
    }

    private static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }
}
