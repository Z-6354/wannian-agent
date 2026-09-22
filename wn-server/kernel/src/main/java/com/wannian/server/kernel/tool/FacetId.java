package com.wannian.server.kernel.tool;

import java.util.Objects;

/** 面相/模式稳定 id（按角色挂载；对外文案称「模式」）。 */
public record FacetId(String value) {
    public static final FacetId CHAT = new FacetId("chat");
    public static final FacetId WORK = new FacetId("work");
    public static final FacetId RESEARCH = new FacetId("research");

    public FacetId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("FacetId 不得空白");
        }
        value = value.trim();
    }
}
