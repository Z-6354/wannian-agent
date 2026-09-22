package com.wannian.server.kernel.tool;

import com.wannian.server.kernel.error.ErrorCodes;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * 封闭工具目录：仅显式 {@link #register}，禁止扫包或覆盖同名。
 *
 * <p>对 AgentLoop 不可见；由 ToolRuntime 内部持有。
 */
public final class ToolCatalog {

    private static final Pattern TOOL_NAME = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    private final ConcurrentMap<String, CatalogEntry> entries = new ConcurrentHashMap<>();

    /**
     * 登记一条工具。同名已存在则拒绝，原条目不变。
     *
     * @param registration 登记载荷；null 或字段非法 → {@link ErrorCodes#ILLEGAL_ARGUMENT}
     */
    public RegisterToolResult register(ToolRegistration registration) {
        if (registration == null) {
            return rejectIllegal("registration 不得为 null");
        }
        String toolName = registration.toolName();
        String description = registration.description();
        ToolParameterSchema parameters = registration.parameters();
        Set<String> requiredCapabilities = registration.requiredCapabilities();
        ToolAdapter executor = registration.executor();
        if (toolName == null
                || description == null
                || parameters == null
                || requiredCapabilities == null
                || executor == null) {
            return rejectIllegal("registration 字段不得为 null");
        }

        String name = toolName.trim();
        if (!TOOL_NAME.matcher(name).matches()) {
            return rejectIllegal("toolName 须匹配 ^[a-z][a-z0-9_]{0,63}$");
        }

        String trimmedDescription = description.trim();
        if (trimmedDescription.isEmpty()) {
            return rejectIllegal("description 不得为空");
        }

        String schemaText = parameters.jsonSchemaObject();
        if (schemaText == null || schemaText.trim().isEmpty()) {
            return rejectIllegal("parameters.jsonSchemaObject 不得为空");
        }

        LinkedHashSet<String> normalizedCaps = new LinkedHashSet<>();
        for (String capability : requiredCapabilities) {
            if (capability == null || capability.trim().isEmpty()) {
                return rejectIllegal("requiredCapabilities 不得含 null 或空白");
            }
            normalizedCaps.add(capability.trim());
        }

        CatalogEntry entry =
                new CatalogEntry(
                        name,
                        trimmedDescription,
                        schemaText.trim(),
                        Set.copyOf(normalizedCaps),
                        executor);
        CatalogEntry existing = entries.putIfAbsent(name, entry);
        if (existing != null) {
            return new RegisterToolResult.Rejected(
                    ErrorCodes.TOOL_ALREADY_REGISTERED, "工具已注册: " + name);
        }
        return new RegisterToolResult.Accepted(name);
    }

    /**
     * 按工具名查找已登记条目（行为 2）。
     *
     * @param toolName 原始调用名；会 trim，不做大小写折叠
     * @return 命中条目；未知名或空白 → empty
     */
    public java.util.Optional<CatalogEntry> findByName(String toolName) {
        if (toolName == null) {
            return java.util.Optional.empty();
        }
        String name = toolName.trim();
        if (name.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(entries.get(name));
    }

    /** 清空目录（管理页热重建前调用）。 */
    public void clear() {
        entries.clear();
    }

    private static RegisterToolResult.Rejected rejectIllegal(String message) {
        return new RegisterToolResult.Rejected(ErrorCodes.ILLEGAL_ARGUMENT, message);
    }

    /** 目录内部不可变条目。 */
    public record CatalogEntry(
            String toolName,
            String description,
            String parameterSchemaJson,
            Set<String> requiredCapabilities,
            ToolAdapter executor) {}
}
