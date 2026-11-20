package com.qoder.payment.sdk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** G-4b Java SDK 极简 JSON（零第三方依赖）：解析对象/数组/字符串/数字/布尔/null，序列化 Map/List/标量。 */
public final class Json {

    private Json() {
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                writeValue(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            sb.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else {
            writeString(sb, value.toString());
        }
    }

    private static void writeString(StringBuilder sb, String text) {
        sb.append('"');
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    public static Object parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Parser parser = new Parser(text);
        parser.skipSpace();
        Object value = parser.readValue();
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        void skipSpace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        Object readValue() {
            skipSpace();
            if (pos >= text.length()) {
                return null;
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> readNumber();
            };
        }

        private Object readLiteral(String literal, Object value) {
            pos += literal.length();
            return value;
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipSpace();
            if (pos < text.length() && text.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (pos < text.length()) {
                skipSpace();
                String key = readString();
                skipSpace();
                pos++; // :
                Object value = readValue();
                map.put(key, value);
                skipSpace();
                if (pos < text.length() && text.charAt(pos) == ',') {
                    pos++;
                    continue;
                }
                pos++; // }
                break;
            }
            return map;
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipSpace();
            if (pos < text.length() && text.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (pos < text.length()) {
                list.add(readValue());
                skipSpace();
                if (pos < text.length() && text.charAt(pos) == ',') {
                    pos++;
                    continue;
                }
                pos++; // ]
                break;
            }
            return list;
        }

        private String readString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    break;
                }
                if (c == '\\' && pos < text.length()) {
                    char esc = text.charAt(pos++);
                    switch (esc) {
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Object readNumber() {
            int start = pos;
            while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
            String raw = text.substring(start, pos);
            if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
                return Double.parseDouble(raw);
            }
            return Long.parseLong(raw);
        }
    }
}