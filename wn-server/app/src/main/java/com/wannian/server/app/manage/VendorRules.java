package com.wannian.server.app.manage;

import java.net.URI;
import java.util.regex.Pattern;

/** 供应商字段校验。拒绝结果只用 {@link ManageReason} 里的 code。 */
final class VendorRules {

    private static final Pattern VENDOR_ID = Pattern.compile("^[a-z][a-z0-9-]{1,31}$");
    private static final Pattern API_KEY_ENV = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    private VendorRules() {}

    sealed interface Check permits Check.OkFields, Check.Bad {
        record OkFields(String displayName, String baseUrl, String apiKeyEnv) implements Check {}

        record Bad(String code, String detail) implements Check {}
    }

    static boolean validId(String id) {
        return id != null && VENDOR_ID.matcher(id).matches();
    }

    static Check check(String displayName, String protocol, String baseUrl, String apiKeyEnv) {
        if (displayName == null) {
            return new Check.Bad(ManageReason.ILLEGAL_ARGUMENT, "显示名不能为空");
        }
        String name = displayName.trim();
        if (name.isEmpty() || name.length() > 80) {
            return new Check.Bad(ManageReason.ILLEGAL_ARGUMENT, "显示名长度必须在 1 到 80 之间");
        }
        if (protocol == null || !StubModelCatalog.PROTOCOL.equals(protocol)) {
            return new Check.Bad(ManageReason.PROTOCOL_UNSUPPORTED, "当前只接受 openai-compatible");
        }
        String normalized = normalizeBaseUrl(baseUrl);
        if (normalized == null) {
            return new Check.Bad(ManageReason.ILLEGAL_ARGUMENT, "Base URL 只允许 http 或 https，且不能带账号或查询串");
        }
        if (apiKeyEnv == null || !API_KEY_ENV.matcher(apiKeyEnv).matches()) {
            return new Check.Bad(ManageReason.ILLEGAL_ARGUMENT, "密钥变量名不合法");
        }
        return new Check.OkFields(name, normalized, apiKeyEnv);
    }

    private static String normalizeBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            return null;
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return null;
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return null;
        }
        String withoutSlash = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        return withoutSlash;
    }
}
