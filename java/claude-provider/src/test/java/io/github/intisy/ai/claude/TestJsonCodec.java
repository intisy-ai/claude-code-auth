package io.github.intisy.ai.claude;

import io.github.intisy.ai.shared.spi.JsonCodec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test-only {@link JsonCodec}: a small hand-rolled recursive-descent parser/printer, structurally
 * identical to core-proxy's own {@code routing}-module test double
 * ({@code io.github.intisy.ai.shared.store.TestJsonCodec}) and its {@code :teavm} production
 * counterpart ({@code SimpleJsonCodec}). Kept LOCAL to this module's test sources (rather than
 * depending on {@code :teavm}, whose own build.gradle pins Java 17) so {@code :claude-provider}'s
 * test classpath never has to cross a JVM-version boundary -- {@code :claude-provider} itself
 * stays pinned at Java 8 (see ../build.gradle's {@code subprojects{}} block).
 */
final class TestJsonCodec implements JsonCodec {

    @Override
    public Object parse(String jsonText) {
        if (jsonText == null || jsonText.isEmpty()) return null;
        Parser p = new Parser(jsonText);
        p.skipWhitespace();
        if (p.atEnd()) return null;
        Object value = p.parseValue();
        p.skipWhitespace();
        return value;
    }

    @Override
    public String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb);
        return sb.toString();
    }

    private static void write(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString((String) value, sb);
        } else if (value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Long || value instanceof Integer) {
            sb.append(value);
        } else if (value instanceof Number) {
            sb.append(value);
        } else if (value instanceof Map) {
            writeMap((Map<?, ?>) value, sb);
        } else if (value instanceof List) {
            writeList((List<?>) value, sb);
        } else {
            throw new IllegalArgumentException("unsupported JSON value type: " + value.getClass());
        }
    }

    private static void writeMap(Map<?, ?> map, StringBuilder sb) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            writeString(String.valueOf(e.getKey()), sb);
            sb.append(':');
            write(e.getValue(), sb);
        }
        sb.append('}');
    }

    private static void writeList(List<?> list, StringBuilder sb) {
        sb.append('[');
        boolean first = true;
        for (Object o : list) {
            if (!first) sb.append(',');
            first = false;
            write(o, sb);
        }
        sb.append(']');
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        sb.append('"');
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
            this.i = 0;
        }

        Object parseValue() {
            skipWhitespace();
            char c = s.charAt(i);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't') { expect("true"); return Boolean.TRUE; }
            if (c == 'f') { expect("false"); return Boolean.FALSE; }
            if (c == 'n') { expect("null"); return null; }
            return parseNumber();
        }

        Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            i++;
            skipWhitespace();
            if (peek() == '}') { i++; return map; }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expectChar(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                char c = s.charAt(i);
                if (c == ',') { i++; }
                else if (c == '}') { i++; break; }
                else throw new IllegalArgumentException("malformed object at " + i);
            }
            return map;
        }

        List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            i++;
            skipWhitespace();
            if (peek() == ']') { i++; return list; }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                char c = s.charAt(i);
                if (c == ',') { i++; }
                else if (c == ']') { i++; break; }
                else throw new IllegalArgumentException("malformed array at " + i);
            }
            return list;
        }

        String parseString() {
            expectChar('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c == '\\') {
                    char esc = s.charAt(i++);
                    switch (esc) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            String hex = s.substring(i, i + 4);
                            i += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                            break;
                        default: sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Object parseNumber() {
            int start = i;
            boolean isDouble = false;
            if (peek() == '-') i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            if (i < s.length() && s.charAt(i) == '.') {
                isDouble = true;
                i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                isDouble = true;
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            String token = s.substring(start, i);
            return isDouble ? (Object) Double.parseDouble(token) : (Object) Long.parseLong(token);
        }

        char peek() { return s.charAt(i); }

        void expectChar(char c) {
            if (s.charAt(i) != c) throw new IllegalArgumentException("expected '" + c + "' at " + i);
            i++;
        }

        void expect(String literal) {
            if (!s.regionMatches(i, literal, 0, literal.length())) {
                throw new IllegalArgumentException("expected '" + literal + "' at " + i);
            }
            i += literal.length();
        }

        void skipWhitespace() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        boolean atEnd() { return i >= s.length(); }
    }
}
