package com.wannian.server.kernel.persona;

import java.util.Optional;

/** Versioned CAS port for augmenting yanhuo without replacing user-owned prompt files. */
public interface DefaultPersonaOverlayPort {
    DefaultPersonaOverlayRevision apply(PersonaDefinition validatedDraft, long expectedOverlayRevision, String operationId);
    Optional<DefaultPersonaOverlayRevision> current();
    Optional<DefaultPersonaOverlayRevision> rollback(long revision, long expectedCurrentRevision);
}
