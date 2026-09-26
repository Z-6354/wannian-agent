package com.wannian.server.kernel.persona;

import java.util.Objects;

/** 稳定的人设标识；yanhuo 是永久保留的默认人设。 */
public record PersonaId(String value) {
    public static final PersonaId YANHUO = new PersonaId("yanhuo");
    public PersonaId {
        Objects.requireNonNull(value, "value");
        if (!value.matches("[a-zA-Z0-9_-]{1,80}")) throw new IllegalArgumentException("非法 persona id");
    }
    public String asString() { return value; }
}
