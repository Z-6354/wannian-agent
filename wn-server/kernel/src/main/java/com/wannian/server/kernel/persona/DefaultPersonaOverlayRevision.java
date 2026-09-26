package com.wannian.server.kernel.persona;

import java.time.Instant;
import java.util.Objects;

/** Immutable, applied SOUL/VOICE supplement for the stable default persona. */
public record DefaultPersonaOverlayRevision(long revision, PersonaId sourcePersonaId, String soul, String voice,
                                            String evidenceSummary, Instant createdAt) {
    public DefaultPersonaOverlayRevision {
        if (revision < 1) throw new IllegalArgumentException("overlay revision must be positive");
        Objects.requireNonNull(sourcePersonaId);
        Objects.requireNonNull(soul);
        Objects.requireNonNull(voice);
        Objects.requireNonNull(evidenceSummary);
        Objects.requireNonNull(createdAt);
        if (soul.length() + voice.length() > 1600) throw new IllegalArgumentException("overlay exceeds character budget");
    }
}
