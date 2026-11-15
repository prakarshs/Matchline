package dev.matchline.http;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small bounded HTTP wire codec, deliberately limited to flat JSON objects whose
 * values are strings, signed 64-bit integers, booleans, or null. Nested data is
 * only emitted by the server, never accepted in order submissions. Rejects duplicate
 * keys, non-integral numeric values, malformed escapes and trailing tokens.
 * No third-party JSON dependency is used in the command path.
 */
final class Json {
    private Json() { }

    static Map<String, Object> parseObject(String text) {
        Parser parser = new Parser(text);
        Map<String, Object> parsed = parser.object();
        parser.ws();
        if (parser.at < text.length()) throw new IllegalArgumentException("trailing JSON content");
        return parsed;
    }

    static String encode(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value) {
        if (value == null) sb.append("null");
        else if (value instanceof String string) quote(sb, string);
        else if (value instanceof Number || value instanceof Boolean) sb.append(value);
        else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (var entry : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, (String) entry.getKey());
                sb.append(':');
                write(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object element : list) {
                if (!first) sb.append(',');
                first = false;
                write(sb, element);
            }
            sb.append(']');
        } else throw new IllegalArgumentException("unserializable JSON value");
    }

    private static void quote(StringBuilder sb, String text) {
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 32) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    private static final class Parser {
        private final String text;
        private int at;
        Parser(String text) { this.text = text; }
        void ws() {
            while (at < text.length() && (text.charAt(at) == ' ' || text.charAt(at) == '\n'
                    || text.charAt(at) == '\t' || text.charAt(at) == '\r')) at++;
        }
        boolean next(char c) {
            ws();
            if (at < text.length() && text.charAt(at) == c) { at++; return true; }
            return false;
        }
        void expect(char c) {
            if (!next(c)) throw new IllegalArgumentException("expected '" + c + "' at JSON offset " + at);
        }
        Map<String, Object> object() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            if (next('}')) return map;
            do {
                ws();
                String key = string();
                expect(':');
                Object value = scalar();
                if (map.containsKey(key)) throw new IllegalArgumentException("duplicate JSON field: " + key);
                map.put(key, value);
                if (next('}')) return map;
                expect(',');
            } while (true);
        }
        Object scalar() {
            ws();
            if (at >= text.length()) throw new IllegalArgumentException("missing JSON value");
            char c = text.charAt(at);
            if (c == '"') return string();
            if (c == '-' || c >= '0' && c <= '9') return number();
            if (text.startsWith("true", at)) { at += 4; return true; }
            if (text.startsWith("false", at)) { at += 5; return false; }
            if (text.startsWith("null", at)) { at += 4; return null; }
            throw new IllegalArgumentException("expected JSON scalar at offset " + at);
        }
        Long number() {
            int start = at;
            if (text.charAt(at) == '-') at++;
            if (at == text.length()) throw new IllegalArgumentException("incomplete JSON integer");
            if (text.charAt(at) == '0') at++;
            else {
                if (text.charAt(at) < '1' || text.charAt(at) > '9')
                    throw new IllegalArgumentException("invalid JSON integer");
                while (at < text.length() && Character.isDigit(text.charAt(at))) at++;
            }
            try { return Long.parseLong(text.substring(start, at)); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("JSON integer out of range"); }
        }
        String string() {
            ws();
            if (at >= text.length() || text.charAt(at++) != '"')
                throw new IllegalArgumentException("expected JSON string");
            StringBuilder sb = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') return sb.toString();
                if (c < 32) throw new IllegalArgumentException("unescaped JSON control character");
                if (c != '\\') { sb.append(c); continue; }
                if (at >= text.length()) throw new IllegalArgumentException("incomplete JSON escape");
                char e = text.charAt(at++);
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (at + 4 > text.length()) throw new IllegalArgumentException("short Unicode escape");
                        try { sb.append((char) Integer.parseInt(text.substring(at, at + 4), 16)); }
                        catch (NumberFormatException invalid) { throw new IllegalArgumentException("bad Unicode escape"); }
                        at += 4;
                    }
                    default -> throw new IllegalArgumentException("invalid JSON escape");
                }
            }
            throw new IllegalArgumentException("unterminated JSON string");
        }
    }
}
