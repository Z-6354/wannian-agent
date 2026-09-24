package com.wannian.server.kernel.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Structured observation sanitizing and size bounding. */
final class ToolResultSanitizer {

    static final int MAX_OBSERVATION_CHARS = 32_768;
    private static final int INITIAL_STRING_LIMIT = 2_048;
    private static final Pattern SECRET_TEXT =
            Pattern.compile(
                    "(?i)(api[_-]?key|authorization|bearer)\\s*[:=]\\s*(?:bearer\\s+)?([^\\s,;]+)");

    private ToolResultSanitizer() {}

    static String sanitize(String observationJson) {
        if (observationJson == null) {
            return "{}";
        }
        Object value;
        try {
            value = new JsonParser(observationJson).parse();
        } catch (IllegalArgumentException ex) {
            // Adapter observations are required to be JSON. Keep the runtime envelope valid if a
            // faulty adapter violates that contract; never splice arbitrary source text into JSON.
            return "{\"invalidObservation\":true}";
        }

        value = sanitizeValue(value);
        String cleaned = writeJson(value);
        if (cleaned.length() <= MAX_OBSERVATION_CHARS) {
            return cleaned;
        }

        value = markTruncated(value, observationJson.length());
        int stringLimit = INITIAL_STRING_LIMIT;
        while (true) {
            value = clipStrings(value, stringLimit);
            cleaned = writeJson(value);
            if (cleaned.length() <= MAX_OBSERVATION_CHARS) {
                return cleaned;
            }
            if (stringLimit == 0) {
                return "{\"truncated\":true,\"originalChars\":"
                        + observationJson.length()
                        + "}";
            }
            stringLimit /= 2;
        }
    }

    private static Object sanitizeValue(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            rawMap.forEach(
                    (key, child) -> {
                        String name = (String) key;
                        result.put(
                                name,
                                isSecretName(name) ? "***" : sanitizeValue(child));
                    });
            return result;
        }
        if (value instanceof List<?> rawList) {
            ArrayList<Object> result = new ArrayList<>(rawList.size());
            rawList.forEach(child -> result.add(sanitizeValue(child)));
            return result;
        }
        if (value instanceof String text) {
            return SECRET_TEXT.matcher(text).replaceAll("$1=***");
        }
        return value;
    }

    private static boolean isSecretName(String name) {
        String normalized = name.replace('-', '_').toLowerCase(Locale.ROOT);
        return normalized.equals("authorization")
                || normalized.equals("bearer")
                || normalized.equals("api_key")
                || normalized.equals("apikey");
    }

    private static Object markTruncated(Object value, int originalChars) {
        if (value instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) rawMap;
            map.put("truncated", true);
            map.put("originalChars", originalChars);
            return map;
        }
        LinkedHashMap<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("value", value);
        wrapper.put("truncated", true);
        wrapper.put("originalChars", originalChars);
        return wrapper;
    }

    private static Object clipStrings(Object value, int maximum) {
        if (value instanceof String text) {
            return text.length() <= maximum ? text : text.substring(0, maximum);
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, child) -> result.put((String) key, clipStrings(child, maximum)));
            return result;
        }
        if (value instanceof List<?> list) {
            ArrayList<Object> result = new ArrayList<>(list.size());
            list.forEach(child -> result.add(clipStrings(child, maximum)));
            return result;
        }
        return value;
    }

    private static String writeJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return "\"" + ToolJson.escape(text) + "\"";
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append(writeJson(entry.getKey().toString())).append(':')
                        .append(writeJson(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) out.append(',');
                out.append(writeJson(list.get(i)));
            }
            return out.append(']').toString();
        }
        throw new IllegalArgumentException("Unsupported JSON value");
    }

    private static final class JsonParser {
        private final String source;
        private int position;

        private JsonParser(String source) {
            this.source = source;
        }

        Object parse() {
            Object value = readValue();
            whitespace();
            if (position != source.length()) fail();
            return value;
        }

        private Object readValue() {
            whitespace();
            if (position >= source.length()) return fail();
            return switch (source.charAt(position)) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            position++;
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            whitespace();
            if (take('}')) return result;
            while (true) {
                whitespace();
                if (position >= source.length() || source.charAt(position) != '"') fail();
                String key = readString();
                whitespace();
                require(':');
                result.put(key, readValue());
                whitespace();
                if (take('}')) return result;
                require(',');
            }
        }

        private List<Object> readArray() {
            position++;
            ArrayList<Object> result = new ArrayList<>();
            whitespace();
            if (take(']')) return result;
            while (true) {
                result.add(readValue());
                whitespace();
                if (take(']')) return result;
                require(',');
            }
        }

        private String readString() {
            require('"');
            StringBuilder result = new StringBuilder();
            while (position < source.length()) {
                char ch = source.charAt(position++);
                if (ch == '"') return result.toString();
                if (ch < 0x20) fail();
                if (ch != '\\') {
                    result.append(ch);
                    continue;
                }
                if (position >= source.length()) fail();
                char escape = source.charAt(position++);
                switch (escape) {
                    case '"', '\\', '/' -> result.append(escape);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> result.append(readUnicode());
                    default -> fail();
                }
            }
            return fail();
        }

        private char readUnicode() {
            if (position + 4 > source.length()) return fail();
            try {
                char result = (char) Integer.parseInt(source.substring(position, position + 4), 16);
                position += 4;
                return result;
            } catch (NumberFormatException ex) {
                return fail();
            }
        }

        private Object readNumber() {
            int start = position;
            if (take('-') && position >= source.length()) fail();
            if (take('0')) {
                if (position < source.length() && Character.isDigit(source.charAt(position))) fail();
            } else {
                digits();
            }
            if (take('.')) digits();
            if (take('e') || take('E')) {
                if (!take('+')) take('-');
                digits();
            }
            if (position == start) return fail();
            String number = source.substring(start, position);
            try {
                return new java.math.BigDecimal(number);
            } catch (NumberFormatException ex) {
                return fail();
            }
        }

        private void digits() {
            int start = position;
            while (position < source.length() && Character.isDigit(source.charAt(position))) position++;
            if (start == position) fail();
        }

        private Object readLiteral(String literal, Object value) {
            if (!source.startsWith(literal, position)) return fail();
            position += literal.length();
            return value;
        }

        private boolean take(char expected) {
            if (position < source.length() && source.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void require(char expected) {
            if (!take(expected)) fail();
        }

        private void whitespace() {
            while (position < source.length() && Character.isWhitespace(source.charAt(position))) position++;
        }

        private <T> T fail() {
            throw new IllegalArgumentException("Invalid JSON at offset " + position);
        }
    }
}
