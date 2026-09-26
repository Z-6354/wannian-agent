package com.wannian.server.kernel.persona;

import java.util.Objects;

/** Strict finite trait vocabulary accepted for default-persona runtime rendering. */
public record DefaultPersonaOverlayTraits(InteractionStyle interactionStyle, ResponsePace responsePace,
                                          Initiative initiative, Humor humor) {
    public DefaultPersonaOverlayTraits {
        Objects.requireNonNull(interactionStyle, "interactionStyle");
        Objects.requireNonNull(responsePace, "responsePace");
        Objects.requireNonNull(initiative, "initiative");
        Objects.requireNonNull(humor, "humor");
    }

    /** UNKNOWN means evidence was insufficient; Core leaves that dimension out of the prompt. */
    public enum InteractionStyle { UNKNOWN, CALM, GENTLE, RESERVED, THOUGHTFUL, DIRECT, PLAYFUL }
    public enum ResponsePace { UNKNOWN, CONCISE, BALANCED, REFLECTIVE, DETAILED }
    public enum Initiative { UNKNOWN, LOW, BALANCED, HIGH }
    public enum Humor { UNKNOWN, NONE, LIGHT, DRY }
}
