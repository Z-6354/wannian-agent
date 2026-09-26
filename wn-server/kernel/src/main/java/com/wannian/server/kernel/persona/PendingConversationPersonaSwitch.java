package com.wannian.server.kernel.persona;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.TurnId;

/** Queues an AI-requested persona switch to apply only after its source turn commits successfully. */
public interface PendingConversationPersonaSwitch {
    void schedule(ConversationId conversationId, PersonaId personaId, long expectedBindingRevision,
            TurnId sourceTurnId, String operationId);
    void turnCompleted(ConversationId conversationId, TurnId turnId);
    void turnAborted(ConversationId conversationId, TurnId turnId);
    void recoverTerminalTurns();
}
