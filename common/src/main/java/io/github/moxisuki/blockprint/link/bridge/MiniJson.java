package io.github.moxisuki.blockprint.link.bridge;

import io.github.moxisuki.blockprint.link.schematic.SchematicScanner;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-rolled, minimal JSON builder/parser for the WebSocket bridge.
 * Avoids pulling in Gson/Jsonb and keeps the mod jar tight.
 */
final class MiniJson {

    private MiniJson() {}

    // ---- builder --------------------------------------------------------

    static String obj(String... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (i > 0) sb.append(',');
            appendString(sb, kv[i]);
            sb.append(':');
            appendValue(sb, kv[i + 1]);
        }
        return sb.append('}').toString();
    }

    static void appendValue(StringBuilder sb, String value) {
        if (value == null || "null".equals(value)) {
            sb.append("null");
        } else if ("true".equals(value) || "false".equals(value)) {
            sb.append(value);
        } else if (isNumeric(value)) {
            sb.append(value);
        } else {
            appendString(sb, value);
        }
    }

    static void appendString(StringBuilder sb, String s) {
        if (s == null) { sb.append("null"); return; }
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    private static boolean isNumeric(String s) {
        if (s.isEmpty()) return false;
        int i = 0;
        if (s.charAt(0) == '-' || s.charAt(0) == '+') i = 1;
        if (i == s.length()) return false;
        boolean seenDot = false, seenE = false, seenDigit = false;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') seenDigit = true;
            else if (c == '.' && !seenDot && !seenE) seenDot = true;
            else if ((c == 'e' || c == 'E') && !seenE && seenDigit) seenE = true;
            else return false;
        }
        return seenDigit;
    }

    // ---- entry → JSON DTO serialiser -----------------------------------

    static String entryToJson(SchematicScanner.Entry e) {
        if (!e.isOk()) return "{\"fileName\":" + quote(e.fileName()) + ",\"error\":" + quote(e.error()) + "}";
        return obj(
            "fileName", e.fileName(),
            "format", e.format(),
            "name", e.data().getName(),
            "width", String.valueOf(e.data().getPrimaryRegion() != null ? e.data().getPrimaryRegion().getWidth() : 0),
            "height", String.valueOf(e.data().getPrimaryRegion() != null ? e.data().getPrimaryRegion().getHeight() : 0),
            "depth", String.valueOf(e.data().getPrimaryRegion() != null ? e.data().getPrimaryRegion().getDepth() : 0),
            "blocks", String.valueOf(e.data().blockCount(false)),
            "author", e.data().getAuthor(),
            "description", e.data().getDescription(),
            "minecraftDataVersion", e.data().getMinecraftDataVersion() == null ? "null" : e.data().getMinecraftDataVersion().toString(),
            "version", e.data().getVersion() == null ? "null" : e.data().getVersion().toString(),
            "regions", String.valueOf(e.data().getRegions().size()),
            "source", e.source() != null ? e.source() : "schematics"
        );
    }

    static String entriesToJsonArray(List<SchematicScanner.Entry> entries) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (SchematicScanner.Entry e : entries) {
            if (!first) sb.append(',');
            first = false;
            sb.append(entryToJson(e));
        }
        return sb.append(']').toString();
    }

    // ---- parser ---------------------------------------------------------

    static java.util.Map<String, String> parseObject(String json) {
        Parser p = new Parser(json);
        p.skipWs();
        return p.readObject();
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder();
        appendString(sb, s);
        return sb.toString();
    }

    // ---- internal recursive-descent parser ------------------------------

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) { this.s = s; }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        java.util.Map<String, String> readObject() {
            java.util.LinkedHashMap<String, String> m = new java.util.LinkedHashMap<>();
            if (s.charAt(pos) != '{') throw new IllegalArgumentException("Expected '{' at " + pos);
            pos++;
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') { pos++; return m; }
            while (pos < s.length()) {
                skipWs();
                String key = readKey();
                skipWs();
                if (s.charAt(pos) != ':') throw new IllegalArgumentException("Expected ':' at " + pos);
                pos++;
                skipWs();
                String value = readValue();
                m.put(key, value);
                skipWs();
                if (pos < s.length() && s.charAt(pos) == ',') { pos++; continue; }
                if (pos < s.length() && s.charAt(pos) == '}') { pos++; return m; }
                throw new IllegalArgumentException("Expected ',' or '}' at " + pos);
            }
            throw new IllegalArgumentException("Unterminated object");
        }

        private String readKey() {
            skipWs();
            if (s.charAt(pos) == '"') return readString();
            int start = pos;
            while (pos < s.length() && "/-_$".indexOf(s.charAt(pos)) >= 0
                || (pos == start ? Character.isLetter(s.charAt(pos)) : Character.isLetterOrDigit(s.charAt(pos)))) {
                pos++;
            }
            return s.substring(start, pos);
        }

        private String readValue() {
            char c = s.charAt(pos);
            if (c == '"') return readString();
            if (c == '{' || c == '[') {
                int start = pos;
                int depth = 0;
                while (pos < s.length()) {
                    char ch = s.charAt(pos);
                    if (ch == '{' || ch == '[') depth++;
                    else if (ch == '}' || ch == ']') {
                        depth--;
                        if (depth == 0) { pos++; return s.substring(start, pos); }
                    }
                    pos++;
                }
                return s.substring(start);
            }
            int start = pos;
            while (pos < s.length() && ",}]".indexOf(s.charAt(pos)) < 0) {
                pos++;
            }
            return s.substring(start, pos).trim();
        }

        private String readString() {
            if (s.charAt(pos) != '"') throw new IllegalArgumentException("Expected '\"' at " + pos);
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\' && pos < s.length()) {
                    char esc = s.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (pos + 4 > s.length()) throw new IllegalArgumentException("Bad unicode escape");
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }
    }
}
