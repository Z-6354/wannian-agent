package com.wannian.server.kernel.persona;

import java.time.Instant;
import java.util.Objects;

public record PersonaDefinition(PersonaId id, String displayName, Status status, int revision,
                                PersonaProfileV1 profile, String sourceId, Instant updatedAt) {
    public PersonaDefinition {
        Objects.requireNonNull(id); Objects.requireNonNull(displayName); Objects.requireNonNull(status); Objects.requireNonNull(profile); Objects.requireNonNull(updatedAt);
        if (displayName.isBlank() || displayName.length()>80 || revision<(id.equals(PersonaId.YANHUO)?0:1)) throw new IllegalArgumentException("角色定义字段非法");
        if (id.equals(PersonaId.YANHUO) && status==Status.ARCHIVED) throw new IllegalArgumentException("yanhuo 不可归档");
    }
    public enum Status { DRAFT, ACTIVE, ARCHIVED }
}
