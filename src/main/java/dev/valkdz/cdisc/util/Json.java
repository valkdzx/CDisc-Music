package dev.valkdz.cdisc.util;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Json implements Iterable<Json> {

    private static final Object NULL = new Object();
    private static final Json MISSING = new Json(null);

    private final Object value;

    private Json(Object value) {
        this.value = value;
    }

    public static Json parse(String text) throws IOException {
        return new Parser(text, false).document();
    }

    public static Json parseLenient(String text) throws IOException {
        return new Parser(text, true).document();
    }

    public static Json object() {
        return new Json(new LinkedHashMap<String, Json>());
    }

    public static Json array() {
        return new Json(new ArrayList<Json>());
    }

    public static Json missing() {
        return MISSING;
    }

    public static Json of(Object value) {
        if (value == null) return new Json(NULL);
        if (value instanceof Json json) return json;
        if (value instanceof String || value instanceof Boolean) return new Json(value);
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return new Json(((Number) value).longValue());
        }
        if (value instanceof Number number) return new Json(number.doubleValue());
        if (value instanceof Map<?, ?> map) {
            Json object = object();
            map.forEach((k, v) -> object.set(String.valueOf(k), of(v)));
            return object;
        }
        if (value instanceof Iterable<?> list) {
            Json array = array();
            list.forEach(v -> array.add(of(v)));
            return array;
        }
        return new Json(value.toString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Json> map() {
        return (Map<String, Json>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Json> list() {
        return (List<Json>) value;
    }

    public boolean isMissing() {
        return value == null;
    }

    public boolean isMissingNode() {
        return value == null;
    }

    public boolean isNull() {
        return value == null || value == NULL;
    }

    public boolean isObject() {
        return value instanceof Map;
    }

    public boolean isArray() {
        return value instanceof List;
    }

    public boolean isTextual() {
        return value instanceof String;
    }

    public boolean isNumber() {
        return value instanceof Number;
    }

    public boolean isBoolean() {
        return value instanceof Boolean;
    }

    public boolean isValueNode() {
        return value != null && !isObject() && !isArray();
    }

    public boolean has(String key) {
        return isObject() && map().containsKey(key);
    }

    public int size() {
        if (isObject()) return map().size();
        if (isArray()) return list().size();
        return 0;
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    public Json get(String key) {
        if (!isObject()) return MISSING;
        Json found = map().get(key);
        return found == null ? MISSING : found;
    }

    public Json path(String key) {
        return get(key);
    }

    public Json get(int index) {
        if (!isArray() || index < 0 || index >= list().size()) return MISSING;
        return list().get(index);
    }

    public Json path(int index) {
        return get(index);
    }

    public Json index(int index) {
        return get(index);
    }

    public List<Json> values() {
        if (isArray()) return Collections.unmodifiableList(list());
        if (isObject()) return List.copyOf(map().values());
        return List.of();
    }

    @Override
    public Iterator<Json> iterator() {
        return values().iterator();
    }

    public Set<String> keys() {
        return isObject() ? Collections.unmodifiableSet(map().keySet()) : Set.of();
    }

    public Set<Map.Entry<String, Json>> entries() {
        return isObject() ? Collections.unmodifiableMap(map()).entrySet() : Set.of();
    }

    public Iterator<Map.Entry<String, Json>> fields() {
        return entries().iterator();
    }

    public String text() {
        if (isNull()) return null;
        if (value instanceof String s) return s;
        if (value instanceof Long || value instanceof Boolean) return value.toString();
        if (value instanceof Double d) return numberText(d);
        return null;
    }

    public String safeText() {
        String text = text();
        return text == null ? "" : text;
    }

    public String asText() {
        return safeText();
    }

    public String asText(String fallback) {
        String text = text();
        return text == null ? fallback : text;
    }

    public long asLong(long fallback) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof Boolean b) return b ? 1 : 0;
        if (value instanceof String s) {
            try {
                return new BigDecimal(s.trim()).longValue();
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    public long asLong() {
        return asLong(0);
    }

    public int asInt(int fallback) {
        return (int) asLong(fallback);
    }

    public int asInt() {
        return asInt(0);
    }

    public double asDouble(double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    public double asDouble() {
        return asDouble(0);
    }

    public boolean asBoolean(boolean fallback) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number number) return number.doubleValue() != 0;
        if (value instanceof String s) {
            if (s.trim().equalsIgnoreCase("true")) return true;
            if (s.trim().equalsIgnoreCase("false")) return false;
        }
        return fallback;
    }

    public boolean asBoolean() {
        return asBoolean(false);
    }

    public Json set(String key, Json child) {
        map().put(key, child == null ? new Json(NULL) : child);
        return this;
    }

    public Json put(String key, String text) {
        return set(key, text == null ? new Json(NULL) : new Json(text));
    }

    public Json put(String key, long number) {
        return set(key, new Json(number));
    }

    public Json put(String key, double number) {
        return set(key, new Json(number));
    }

    public Json put(String key, boolean flag) {
        return set(key, new Json(flag));
    }

    public Json setAll(Json other) {
        if (other.isObject()) other.map().forEach(this::set);
        return this;
    }

    public Json putObject(String key) {
        Json child = object();
        set(key, child);
        return child;
    }

    public Json putArray(String key) {
        Json child = array();
        set(key, child);
        return child;
    }

    public Json remove(String key) {
        if (isObject()) map().remove(key);
        return this;
    }

    public Json add(Json child) {
        list().add(child == null ? new Json(NULL) : child);
        return this;
    }

    public Json add(String text) {
        return add(text == null ? null : new Json(text));
    }

    public Json add(long number) {
        return add(new Json(number));
    }

    public Json deepCopy() {
        if (isObject()) {
            Json copy = object();
            map().forEach((k, v) -> copy.set(k, v.deepCopy()));
            return copy;
        }
        if (isArray()) {
            Json copy = array();
            list().forEach(v -> copy.add(v.deepCopy()));
            return copy;
        }
        return this;
    }

    public Object toJava() {
        if (isNull()) return null;
        if (isObject()) {
            Map<String, Object> out = new LinkedHashMap<>();
            map().forEach((k, v) -> out.put(k, v.toJava()));
            return out;
        }
        if (isArray()) {
            List<Object> out = new ArrayList<>();
            list().forEach(v -> out.add(v.toJava()));
            return out;
        }
        return value;
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder();
        write(out, -1, 0);
        return out.toString();
    }

    public String toPrettyString() {
        StringBuilder out = new StringBuilder();
        write(out, 2, 0);
        return out.toString();
    }

    private void write(StringBuilder out, int indent, int depth) {
        if (isNull()) {
            out.append("null");
        } else if (value instanceof String s) {
            quote(out, s);
        } else if (value instanceof Double d) {
            out.append(d.isNaN() || d.isInfinite() ? "null" : numberText(d));
        } else if (value instanceof Long || value instanceof Boolean) {
            out.append(value);
        } else if (isObject()) {
            if (map().isEmpty()) {
                out.append("{}");
                return;
            }
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Json> entry : map().entrySet()) {
                if (!first) out.append(',');
                first = false;
                newline(out, indent, depth + 1);
                quote(out, entry.getKey());
                out.append(indent < 0 ? ":" : ": ");
                entry.getValue().write(out, indent, depth + 1);
            }
            newline(out, indent, depth);
            out.append('}');
        } else {
            if (list().isEmpty()) {
                out.append("[]");
                return;
            }
            out.append('[');
            boolean first = true;
            for (Json element : list()) {
                if (!first) out.append(',');
                first = false;
                newline(out, indent, depth + 1);
                element.write(out, indent, depth + 1);
            }
            newline(out, indent, depth);
            out.append(']');
        }
    }

    private static void newline(StringBuilder out, int indent, int depth) {
        if (indent < 0) return;
        out.append('\n');
        out.append(" ".repeat(indent * depth));
    }

    private static String numberText(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d) + ".0";
        return Double.toString(d);
    }

    private static void quote(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Json json && toString().equals(json.toString());
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }

    private static final class Parser {
        private final String text;
        private final boolean lenient;
        private int at;

        Parser(String text, boolean lenient) {
            this.text = text == null ? "" : text;
            this.lenient = lenient;
        }

        Json document() throws IOException {
            if (!text.isEmpty() && text.charAt(0) == '﻿') at = 1;
            Json root = value();
            skip();
            if (at < text.length()) throw error("unexpected text after the value");
            return root;
        }

        private Json value() throws IOException {
            skip();
            if (at >= text.length()) throw error("unexpected end");
            char c = text.charAt(at);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> new Json(string('"'));
                case '\'' -> {
                    if (!lenient) throw error("single quotes");
                    yield new Json(string('\''));
                }
                default -> literal();
            };
        }

        private Json object() throws IOException {
            at++;
            Json object = Json.object();
            skip();
            if (peek('}')) {
                at++;
                return object;
            }
            while (true) {
                skip();
                if (lenient && peek('}')) {
                    at++;
                    return object;
                }
                String key;
                if (peek('"')) {
                    key = string('"');
                } else if (lenient && peek('\'')) {
                    key = string('\'');
                } else {
                    throw error("expected a key");
                }
                skip();
                expect(':');
                object.set(key, value());
                skip();
                if (peek(',')) {
                    at++;
                    continue;
                }
                expect('}');
                return object;
            }
        }

        private Json array() throws IOException {
            at++;
            Json array = Json.array();
            skip();
            if (peek(']')) {
                at++;
                return array;
            }
            while (true) {
                skip();
                if (lenient && peek(']')) {
                    at++;
                    return array;
                }
                array.add(value());
                skip();
                if (peek(',')) {
                    at++;
                    continue;
                }
                expect(']');
                return array;
            }
        }

        private String string(char quote) throws IOException {
            at++;
            StringBuilder out = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == quote) return out.toString();
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (at >= text.length()) break;
                char e = text.charAt(at++);
                switch (e) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        if (at + 4 > text.length()) throw error("short unicode escape");
                        try {
                            out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw error("bad unicode escape");
                        }
                        at += 4;
                    }
                    default -> out.append(e);
                }
            }
            throw error("unterminated string");
        }

        private Json literal() throws IOException {
            int start = at;
            while (at < text.length() && "{}[],: \t\r\n/#".indexOf(text.charAt(at)) < 0) at++;
            String word = text.substring(start, at);
            switch (word) {
                case "true":
                    return new Json(Boolean.TRUE);
                case "false":
                    return new Json(Boolean.FALSE);
                case "null":
                    return new Json(NULL);
                default:
                    break;
            }
            try {
                if (word.indexOf('.') < 0 && word.indexOf('e') < 0 && word.indexOf('E') < 0) {
                    return new Json(Long.parseLong(word));
                }
                return new Json(Double.parseDouble(word));
            } catch (NumberFormatException e) {
                try {
                    return new Json(new BigDecimal(word).doubleValue());
                } catch (NumberFormatException again) {
                    throw error("unexpected '" + word + "'");
                }
            }
        }

        private void skip() throws IOException {
            while (at < text.length()) {
                char c = text.charAt(at);
                if (Character.isWhitespace(c)) {
                    at++;
                } else if (lenient && c == '#') {
                    lineComment();
                } else if (lenient && c == '/' && at + 1 < text.length() && text.charAt(at + 1) == '/') {
                    lineComment();
                } else if (lenient && c == '/' && at + 1 < text.length() && text.charAt(at + 1) == '*') {
                    int end = text.indexOf("*/", at + 2);
                    if (end < 0) throw error("unterminated comment");
                    at = end + 2;
                } else {
                    return;
                }
            }
        }

        private void lineComment() {
            while (at < text.length() && text.charAt(at) != '\n') at++;
        }

        private boolean peek(char c) {
            return at < text.length() && text.charAt(at) == c;
        }

        private void expect(char c) throws IOException {
            if (!peek(c)) throw error("expected '" + c + "'");
            at++;
        }

        private IOException error(String what) {
            int line = 1;
            int column = 1;
            for (int i = 0; i < Math.min(at, text.length()); i++) {
                if (text.charAt(i) == '\n') {
                    line++;
                    column = 1;
                } else {
                    column++;
                }
            }
            return new IOException("Invalid JSON: " + what + " at line " + line + ", column " + column);
        }
    }
}
