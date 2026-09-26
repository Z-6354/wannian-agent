package com.wannian.server.kernel.persona;

import java.util.List;
import java.util.Optional;

/** Import 与 HTTP 共用的角色目录端口；仅接收经 Core schema 校验的画像。 */
public interface PersonaCatalog {
    PersonaDefinition createDraft(PersonaProfileV1 validatedProfile, String requestKey);
    PersonaDefinition activateDraft(PersonaId personaId, int expectedRevision);
    Optional<PersonaDefinition> get(PersonaId personaId);
    List<PersonaDefinition> list();
    PersonaDefinition archive(PersonaId personaId, int expectedRevision);
}
