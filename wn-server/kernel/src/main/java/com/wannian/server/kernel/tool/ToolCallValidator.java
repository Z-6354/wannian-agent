package com.wannian.server.kernel.tool;

import com.wannian.server.kernel.error.ErrorCodes;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 工具参数校验（行为 3）。非法则拒绝，不进入执行。
 */
final class ToolCallValidator {

    private static final Pattern SAFE_EXPRESSION =
            Pattern.compile("^[0-9+\\-*/().\\s]+$");
    private static final Set<String> POWERSHELL_FORBIDDEN_KEYS =
            Set.of(
                    "script",
                    "command",
                    "code",
                    "execute",
                    "powershell",
                    "args",
                    "file",
                    "path",
                    "expression");

    private ToolCallValidator() {}

    static ValidationResult validate(
            ToolCatalog.CatalogEntry entry, String argumentsJson) {
        Map<String, String> fields;
        try {
            fields = ToolJson.parseFlatObject(argumentsJson);
        } catch (ToolJson.ToolJsonException ex) {
            return ValidationResult.reject(ex.getMessage());
        }
        return switch (entry.toolName()) {
            case BuiltinToolNames.CURRENT_TIME -> validateCurrentTime(fields);
            case BuiltinToolNames.CALCULATE -> validateCalculate(fields);
            case BuiltinToolNames.HTTP_READ -> validateHttpRead(fields);
            case BuiltinToolNames.POWERSHELL_RESOLVE_5, BuiltinToolNames.POWERSHELL_RESOLVE_7 ->
                    validatePowershellResolve(fields);
            case BuiltinToolNames.REMEMBER_FACT -> validateRememberFact(fields);
            case BuiltinToolNames.UPDATE_RELATIONSHIP -> validateUpdateRelationship(fields);
            case BuiltinToolNames.SEARCH_MEMORY -> validateSearchMemory(fields);
            default -> ValidationResult.ok(fields);
        };
    }

    private static ValidationResult validateSearchMemory(Map<String, String> fields) {
        for (String key : fields.keySet()) {
            if (!"query".equals(key) && !"limit".equals(key)) {
                return ValidationResult.reject("search_memory 仅允许字段 query / limit");
            }
        }
        if (blank(fields.get("query"))) {
            return ValidationResult.reject("search_memory 缺少非空 query");
        }
        String rawLimit = fields.get("limit");
        if (rawLimit == null || rawLimit.isBlank()) {
            return ValidationResult.ok(fields);
        }
        try {
            int limit = Integer.parseInt(rawLimit.trim());
            int max = com.wannian.server.kernel.memory.MemorySearchLimits.HARD_MAX_LIMIT;
            if (limit < 1 || limit > max) {
                return ValidationResult.reject("search_memory limit 须在 1.." + max);
            }
        } catch (NumberFormatException ex) {
            return ValidationResult.reject("search_memory limit 须为整数");
        }
        return ValidationResult.ok(fields);
    }

    private static ValidationResult validateRememberFact(Map<String, String> fields) {
        if (blank(fields.get("claim"))
                || blank(fields.get("subjectKey"))
                || blank(fields.get("importance"))
                || blank(fields.get("contentKind"))) {
            return ValidationResult.reject("remember_fact 缺少 claim/subjectKey/importance/contentKind");
        }
        try {
            Double.parseDouble(fields.get("importance"));
        } catch (NumberFormatException ex) {
            return ValidationResult.reject("importance 须为数字");
        }
        try {
            com.wannian.server.kernel.memory.MemoryAxisNames.parseContentKind(fields.get("contentKind"));
            if (!blank(fields.get("sourceKind"))) {
                com.wannian.server.kernel.memory.MemoryAxisNames.parseSourceKind(fields.get("sourceKind"));
            }
            if (!blank(fields.get("scope"))) {
                com.wannian.server.kernel.memory.MemoryAxisNames.parseScope(fields.get("scope"));
            }
        } catch (IllegalArgumentException ex) {
            return ValidationResult.reject(ex.getMessage());
        }
        return ValidationResult.ok(fields);
    }

    private static ValidationResult validateUpdateRelationship(Map<String, String> fields) {
        if (blank(fields.get("reason"))) {
            return ValidationResult.reject("update_relationship 缺少 reason");
        }
        if (blank(fields.get("preferredAddress")) && blank(fields.get("boundaries"))) {
            return ValidationResult.reject("须提供 preferredAddress 或 boundaries");
        }
        return ValidationResult.ok(fields);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ValidationResult validateCurrentTime(Map<String, String> fields) {
        for (String key : fields.keySet()) {
            if (!"timezone".equals(key)) {
                return ValidationResult.reject("current_time 仅允许可选字段 timezone");
            }
        }
        var tz = ToolJson.optionalString(fields, "timezone");
        if (tz.isPresent()) {
            try {
                java.time.ZoneId.of(tz.get());
            } catch (Exception ex) {
                return ValidationResult.reject("非法 timezone: " + tz.get());
            }
        }
        return ValidationResult.ok(fields);
    }

    private static ValidationResult validateCalculate(Map<String, String> fields) {
        String expression;
        try {
            expression = ToolJson.requireString(fields, "expression");
        } catch (ToolJson.ToolJsonException ex) {
            return ValidationResult.reject(ex.getMessage());
        }
        for (String key : fields.keySet()) {
            if (!"expression".equals(key)) {
                return ValidationResult.reject("calculate 仅允许字段 expression");
            }
        }
        String trimmed = expression.trim();
        if (trimmed.length() > 200) {
            return ValidationResult.reject("expression 过长");
        }
        if (!SAFE_EXPRESSION.matcher(trimmed).matches()) {
            return ValidationResult.reject("expression 含非法字符");
        }
        return ValidationResult.ok(fields);
    }

    private static ValidationResult validateHttpRead(Map<String, String> fields) {
        String urlText;
        try {
            urlText = ToolJson.requireString(fields, "url");
        } catch (ToolJson.ToolJsonException ex) {
            return ValidationResult.reject(ex.getMessage());
        }
        for (String key : fields.keySet()) {
            if (!"url".equals(key)) {
                return ValidationResult.reject("http_read 仅允许字段 url");
            }
        }
        URI uri;
        try {
            uri = URI.create(urlText.trim());
        } catch (Exception ex) {
            return ValidationResult.reject("url 无法解析");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            return ValidationResult.reject("仅允许 http/https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return ValidationResult.reject("url 缺少 host");
        }
        if (isBlockedHost(host)) {
            return ValidationResult.reject("禁止访问内网或本机地址");
        }
        return ValidationResult.ok(fields);
    }

    private static ValidationResult validatePowershellResolve(Map<String, String> fields) {
        for (String key : fields.keySet()) {
            String lower = key.toLowerCase(Locale.ROOT);
            if (POWERSHELL_FORBIDDEN_KEYS.contains(lower)) {
                return ValidationResult.reject("powershell_resolve_* 禁止脚本/执行类参数: " + key);
            }
            return ValidationResult.reject("powershell_resolve_* 本批不接受参数字段: " + key);
        }
        return ValidationResult.ok(fields);
    }

    private static boolean isBlockedHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(h) || h.endsWith(".localhost") || h.endsWith(".local")) {
            return true;
        }
        try {
            InetAddress addr = InetAddress.getByName(h);
            return addr.isAnyLocalAddress()
                    || addr.isLoopbackAddress()
                    || addr.isLinkLocalAddress()
                    || addr.isSiteLocalAddress();
        } catch (UnknownHostException ex) {
            // 主机名暂不可解析：仍允许进入执行（执行期再失败）；不在此误拒公网 DNS
            return false;
        }
    }

    record ValidationResult(boolean ok, String message, Map<String, String> fields) {
        static ValidationResult ok(Map<String, String> fields) {
            return new ValidationResult(true, "", fields);
        }

        static ValidationResult reject(String message) {
            return new ValidationResult(false, message, Map.of());
        }

        String code() {
            return ErrorCodes.TOOL_INVALID_ARGUMENTS;
        }
    }
}
