package com.wannian.server.kernel.persona;

import com.wannian.server.api.common.ConversationId;
import com.wannian.server.api.common.TurnId;
import java.util.Optional;

/** 会话绑定与首次认领快照端口；实现需 CAS 并持久化快照以支持重试。 */
public interface ConversationPersonaBinding {
    BindingSnapshot bind(ConversationId conversationId, PersonaId activePersonaId, long expectedBindingRevision);
    BindingSnapshot current(ConversationId conversationId);
    PersonaTurnSnapshot resolveForTurn(ConversationId conversationId, TurnId turnId);
    /** Historical projection: snapshots created by Core, with legacy missing rows mapped to yanhuo. */
    Optional<PersonaId> personaIdForTurn(TurnId turnId);
    record BindingSnapshot(ConversationId conversationId, PersonaId personaId, long revision) {}
}
