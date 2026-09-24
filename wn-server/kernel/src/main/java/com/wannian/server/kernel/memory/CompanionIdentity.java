package com.wannian.server.kernel.memory;

import java.util.Objects;

/**
 * 伴身稳定 id（产品：烟火）。
 *
 * <p>本批仅装配 {@link #YANHUO}；第二套独立人格才新建第二个 CompanionIdentity。
 */
public record CompanionIdentity(String value) {
    public static final CompanionIdentity YANHUO = new CompanionIdentity("yanhuo");

    public CompanionIdentity {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("CompanionIdentity 不得空白");
        }
        value = value.trim();
    }
}
