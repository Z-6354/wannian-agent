package com.wannian.server.kernel.memory;

import java.util.Arrays;
import java.util.stream.Collectors;

/** 记忆轴枚举的模型可见名表（schema / 拒写文案共用）。 */
public final class MemoryAxisNames {

    public static final String CONTENT_KINDS =
            Arrays.stream(ContentKind.values()).map(Enum::name).collect(Collectors.joining(", "));

    public static final String SOURCE_KINDS =
            Arrays.stream(SourceKind.values()).map(Enum::name).collect(Collectors.joining(", "));

    public static final String SCOPES =
            Arrays.stream(MemoryScope.values()).map(Enum::name).collect(Collectors.joining(", "));

    private MemoryAxisNames() {}

    public static ContentKind parseContentKind(String raw) {
        String value = requireToken(raw, "contentKind");
        try {
            return ContentKind.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "contentKind 无效: " + value + "；允许: " + CONTENT_KINDS);
        }
    }

    public static SourceKind parseSourceKind(String raw) {
        String value = requireToken(raw, "sourceKind");
        try {
            return SourceKind.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "sourceKind 无效: " + value + "；允许: " + SOURCE_KINDS);
        }
    }

    public static MemoryScope parseScope(String raw) {
        String value = requireToken(raw, "scope");
        try {
            return MemoryScope.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("scope 无效: " + value + "；允许: " + SCOPES);
        }
    }

    private static String requireToken(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return raw.trim();
    }
}
