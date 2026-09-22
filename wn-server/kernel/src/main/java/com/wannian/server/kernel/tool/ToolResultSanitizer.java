package com.wannian.server.kernel.tool;

/**
 * 观察结果消毒与截断（行为 6）。
 */
final class ToolResultSanitizer {

    static final int MAX_OBSERVATION_CHARS = 32_768;

    private ToolResultSanitizer() {}

    static String sanitize(String observationJson) {
        if (observationJson == null) {
            return "{}";
        }
        String cleaned = stripSecrets(observationJson);
        if (cleaned.length() <= MAX_OBSERVATION_CHARS) {
            return cleaned;
        }
        return cleaned.substring(0, MAX_OBSERVATION_CHARS - 32) + "...\"truncated\":true}";
    }

    private static String stripSecrets(String text) {
        // 粗粒度：去掉常见密钥形态，避免进观察正文
        return text.replaceAll("(?i)(api[_-]?key|authorization|bearer)\\s*[:=]\\s*\\S+", "$1=***");
    }
}
