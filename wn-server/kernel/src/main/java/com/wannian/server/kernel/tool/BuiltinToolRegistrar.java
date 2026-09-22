package com.wannian.server.kernel.tool;

import com.wannian.server.kernel.tool.builtin.HttpReadToolAdapter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 显式登记内置工具（禁止扫包）。按启用名单从 {@link BuiltinToolPool} 取实现。
 */
public final class BuiltinToolRegistrar {

    private BuiltinToolRegistrar() {}

    /** 登记池内全部工具（测试 / 种子默认）。 */
    public static void registerAll(ToolCatalog catalog) {
        registerEnabled(catalog, BuiltinToolPool.allNames());
    }

    /**
     * 清空后仅登记 {@code enabled} 与池的交集（保持启用名单顺序）。
     *
     * @throws IllegalArgumentException 含不在池内的名字
     */
    public static void registerEnabled(ToolCatalog catalog, Set<String> enabled) {
        registerEnabled(catalog, enabled, HttpReadToolAdapter.DEFAULT_USER_AGENT);
    }

    /**
     * 同 {@link #registerEnabled(ToolCatalog, Set)}，但 {@code http_read} 使用 app 注入的 User-Agent。
     *
     * @param httpReadUserAgent 非空；空白则回落 {@link HttpReadToolAdapter#DEFAULT_USER_AGENT}
     */
    public static void registerEnabled(
            ToolCatalog catalog, Set<String> enabled, String httpReadUserAgent) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(enabled, "enabled");
        String userAgent = normalizeHttpUserAgent(httpReadUserAgent);
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String raw : enabled) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("enabled 不得含空白名");
            }
            String name = raw.trim();
            if (!BuiltinToolPool.contains(name)) {
                throw new IllegalArgumentException("不在内置池: " + name);
            }
            names.add(name);
        }
        catalog.clear();
        for (String name : names) {
            if (BuiltinToolNames.HTTP_READ.equals(name)) {
                requireAccepted(catalog.register(httpReadRegistration(userAgent)));
            } else {
                requireAccepted(catalog.register(BuiltinToolPool.registrationOf(name)));
            }
        }
    }

    private static String normalizeHttpUserAgent(String raw) {
        if (raw == null || raw.isBlank()) {
            return HttpReadToolAdapter.DEFAULT_USER_AGENT;
        }
        return raw.trim();
    }

    private static ToolRegistration httpReadRegistration(String userAgent) {
        BuiltinToolPool.Spec spec =
                BuiltinToolPool.find(BuiltinToolNames.HTTP_READ)
                        .orElseThrow(() -> new IllegalStateException("内置池缺少 http_read"));
        return new ToolRegistration(
                spec.name(),
                spec.description(),
                new ToolParameterSchema(spec.parameterSchemaJson()),
                spec.requiredCapabilities(),
                new HttpReadToolAdapter(userAgent));
    }

    private static void requireAccepted(RegisterToolResult result) {
        if (result instanceof RegisterToolResult.Rejected rejected) {
            throw new IllegalStateException(
                    "内置工具登记失败: " + rejected.code() + " " + rejected.message());
        }
    }

    /** 默认启用名单（与历史硬编码一致）。 */
    public static List<String> defaultEnabled() {
        return List.copyOf(BuiltinToolPool.allNames());
    }
}
