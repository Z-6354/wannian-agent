package com.wannian.server.kernel.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 手写扁平 JSON 对象读写（避免 kernel 依赖 Jackson）。仅支持一层 string / number / boolean / null。
 */
public final class ToolJson {

    private ToolJson() {}

    public static Map<String, String> parseFlatObject(String json) throws ToolJsonException {
        if (json == null) {
            throw new ToolJsonException("JSON 不得为 null");
        }
        Cursor c = new Cursor(json.trim());
        c.skipWs();
        c.expect('{');
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        c.skipWs();
        if (c.peek('}')) {
            c.advance();
            return Map.of();
        }
        while (true) {
            c.skipWs();
            String key = c.readString();
            c.skipWs();
            c.expect(':');
            c.skipWs();
            out.put(key, c.readScalar());
            c.skipWs();
            if (c.peek('}')) {
                c.advance();
                return Collections.unmodifiableMap(out);
            }
            c.expect(',');
        }
    }

    public static Optional<String> optionalString(Map<String, String> map, String key) {
        if (!map.containsKey(key)) {
            return Optional.empty();
        }
        String v = map.get(key);
        return v == null || v.isBlank() ? Optional.empty() : Optional.of(v);
    }

    public static String requireString(Map<String, String> map, String key) throws ToolJsonException {
        String v = map.get(key);
        if (v == null || v.isBlank()) {
            throw new ToolJsonException("缺少必填字段: " + key);
        }
        return v;
    }

    public static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
    }

    public static String object(Map<String, String> fields) {
        StringBuilder out = new StringBuilder(64);
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append('"')
                    .append(escape(e.getKey()))
                    .append("\":\"")
                    .append(escape(e.getValue() == null ? "" : e.getValue()))
                    .append('"');
        }
        out.append('}');
        return out.toString();
    }

    public static String objectWithRaw(Map<String, String> stringFields, Map<String, String> rawJsonValues) {
        StringBuilder out = new StringBuilder(64);
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, String> e : stringFields.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append('"')
                    .append(escape(e.getKey()))
                    .append("\":\"")
                    .append(escape(e.getValue() == null ? "" : e.getValue()))
                    .append('"');
        }
        for (Map.Entry<String, String> e : rawJsonValues.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append('"').append(escape(e.getKey())).append("\":").append(e.getValue());
        }
        out.append('}');
        return out.toString();
    }

    private static final class Cursor {
        private final String s;
        private int i;

        Cursor(String s) {
            this.s = s;
        }

        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        boolean peek(char expected) {
            return i < s.length() && s.charAt(i) == expected;
        }

        void advance() {
            i++;
        }

        void expect(char expected) throws ToolJsonException {
            if (i >= s.length() || s.charAt(i) != expected) {
                throw new ToolJsonException("期望 '" + expected + "'");
            }
            i++;
        }

        String readString() throws ToolJsonException {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (i < s.length()) {
                char ch = s.charAt(i++);
                if (ch == '\\') {
                    if (i >= s.length()) {
                        throw new ToolJsonException("非法转义");
                    }
                    char e = s.charAt(i++);
                    out.append(switch (e) {
                        case '"', '\\', '/' -> e;
                        case 'b' -> '\b';
                        case 'f' -> '\f';
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case 'u' -> {
                            if (i + 3 >= s.length()) {
                                throw new ToolJsonException("非法 \\u 转义");
                            }
                            int code = Integer.parseInt(s.substring(i, i + 4), 16);
                            i += 4;
                            yield (char) code;
                        }
                        default -> throw new ToolJsonException("非法转义");
                    });
                    continue;
                }
                if (ch == '"') {
                    return out.toString();
                }
                out.append(ch);
            }
            throw new ToolJsonException("字符串未闭合");
        }

        String readScalar() throws ToolJsonException {
            if (i >= s.length()) {
                throw new ToolJsonException("期望值");
            }
            char ch = s.charAt(i);
            if (ch == '"') {
                return readString();
            }
            if (s.startsWith("true", i)) {
                i += 4;
                return "true";
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return "false";
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            if (ch == '-' || Character.isDigit(ch)) {
                int start = i++;
                while (i < s.length()) {
                    char d = s.charAt(i);
                    if (Character.isDigit(d) || d == '.' || d == 'e' || d == 'E' || d == '+' || d == '-') {
                        i++;
                    } else {
                        break;
                    }
                }
                return s.substring(start, i);
            }
            if (ch == '{' || ch == '[') {
                throw new ToolJsonException("不支持嵌套对象或数组");
            }
            throw new ToolJsonException("无法解析值");
        }
    }

    public static final class ToolJsonException extends Exception {
        public ToolJsonException(String message) {
            super(message);
        }
    }
}
