package com.wannian.server.kernel.tool;

import java.util.Objects;

/** 角色稳定 id（可扩展；本批装配烟火）。 */
public record RoleId(String value) {
    public static final RoleId YANHUO = new RoleId("yanhuo");

    public RoleId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("RoleId 不得空白");
        }
        value = value.trim();
    }
}
