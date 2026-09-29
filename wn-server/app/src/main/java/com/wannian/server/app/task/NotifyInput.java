package com.wannian.server.app.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;

/**
 * USER_SCHEDULED_NOTIFY 的 {@code input_json} / {@code result_json} 投影（2.5.12）。
 *
 * <p>不另建表；调度只认 scheduleSpec，忽略 {@code triggerAt}。
 */
public final class NotifyInput {

    public static final String DELIVERY_NOTICE_THEN_CHAT = "NOTICE_THEN_CHAT";

    private static final ObjectMapper FALLBACK_MAPPER = new ObjectMapper();

    private final String message;
    private final String title;
    private final String delivery;

    private NotifyInput(String message, String title, String delivery) {
        this.message = Objects.requireNonNull(message, "message");
        this.title = title;
        this.delivery = delivery == null || delivery.isBlank()
                ? DELIVERY_NOTICE_THEN_CHAT
                : delivery.trim();
    }

    public String message() {
        return message;
    }

    /** 可空。 */
    public String title() {
        return title;
    }

    public String delivery() {
        return delivery;
    }

    /** 列表/中心短标题：title 优先，否则正文截断。 */
    public String titleOrClip(int max) {
        if (title != null && !title.isBlank()) {
            return clip(title.trim(), max);
        }
        return clip(message, max);
    }

    /**
     * 解析 input_json：须为对象且含非空 {@code message} 或 {@code reminder}（优先 message）。
     *
     * @throws IllegalArgumentException 缺正文或非对象
     */
    public static NotifyInput parseRequired(ObjectMapper mapper, String inputJson) {
        NotifyInput parsed = tryParse(mapper, inputJson);
        if (parsed == null) {
            throw new IllegalArgumentException(
                    "USER_SCHEDULED_NOTIFY 须含 message 或 reminder 正文");
        }
        return parsed;
    }

    /** 宽松解析；缺正文或非法 JSON 返回 null。{@code mapper} 可空（用内置）。 */
    public static NotifyInput tryParse(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        ObjectMapper m = mapper != null ? mapper : FALLBACK_MAPPER;
        try {
            JsonNode node = m.readTree(json);
            if (node == null || !node.isObject()) {
                return null;
            }
            String message = firstNonBlank(text(node, "message"), text(node, "reminder"));
            if (message == null) {
                return null;
            }
            return new NotifyInput(message, text(node, "title"), text(node, "delivery"));
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Policy/prepare：是否含可读提醒正文。 */
    public static boolean hasNotifyBody(String inputJson) {
        return tryParse(FALLBACK_MAPPER, inputJson) != null;
    }

    /**
     * 终态交付：优先 result_json 的 fired.message，否则回落 input_json。
     */
    public static NotifyInput fromResultOrInput(
            ObjectMapper mapper, String resultJson, String inputJson) {
        NotifyInput fromResult = tryParse(mapper, resultJson);
        if (fromResult != null) {
            return fromResult;
        }
        return tryParse(mapper, inputJson);
    }

    private static String text(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            return null;
        }
        String v = node.get(field).asText();
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }

    private static String clip(String text, int max) {
        String t = text.trim();
        if (max < 1 || t.length() <= max) {
            return t;
        }
        return t.substring(0, max) + "…";
    }
}
