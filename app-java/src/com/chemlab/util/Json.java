package com.chemlab.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 读写工具。
 *
 * <p>项目只引入 SQLite JDBC 一个外部依赖，因此不引入 JSON 库，这里自带一个
 * 足够用的递归下降解析器：对象映射为 {@link LinkedHashMap}（保持键序，便于
 * 界面展示），数组映射为 {@link ArrayList}，数值统一为 {@link Double}，
 * 整数值解析为 {@link Long}。
 *
 * <p>解析失败抛 {@link JsonException}，调用方据此降级处理。
 */
public final class Json {

    public static class JsonException extends RuntimeException {
        public JsonException(String message) {
            super(message);
        }
    }

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    public static Object parse(String text) {
        if (text == null) {
            throw new JsonException("输入为 null");
        }
        Json p = new Json(text);
        p.skipWs();
        Object v = p.parseValue();
        p.skipWs();
        if (p.pos < p.src.length()) {
            throw new JsonException("第 " + p.pos + " 个字符后存在多余内容");
        }
        return v;
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    private Object parseValue() {
        if (pos >= src.length()) {
            throw new JsonException("输入意外结束");
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{':
                return parseObject();
            case '[':
                return parseArray();
            case '"':
                return parseString();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return parseNumber();
        }
    }

    private Map<String, Object> parseObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // {
        skipWs();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWs();
            if (peek() != '"') {
                throw new JsonException("对象键必须是字符串，位置 " + pos);
            }
            String key = parseString();
            skipWs();
            if (peek() != ':') {
                throw new JsonException("键 " + key + " 后缺少冒号");
            }
            pos++;
            skipWs();
            map.put(key, parseValue());
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return map;
            }
            throw new JsonException("对象中期望 , 或 }，位置 " + pos);
        }
    }

    private List<Object> parseArray() {
        List<Object> list = new ArrayList<>();
        pos++; // [
        skipWs();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWs();
            list.add(parseValue());
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return list;
            }
            throw new JsonException("数组中期望 , 或 ]，位置 " + pos);
        }
    }

    private String parseString() {
        pos++; // 开引号
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw new JsonException("字符串未闭合");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= src.length()) {
                throw new JsonException("转义序列未完成");
            }
            char e = src.charAt(pos++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (pos + 4 > src.length()) {
                        throw new JsonException("\\u 转义不完整");
                    }
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default:
                    throw new JsonException("无法识别的转义 \\" + e);
            }
        }
    }

    private Object parseNumber() {
        int start = pos;
        if (peek() == '-' || peek() == '+') {
            pos++;
        }
        boolean isDouble = false;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c >= '0' && c <= '9') {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                isDouble = true;
                pos++;
            } else {
                break;
            }
        }
        String tok = src.substring(start, pos);
        if (tok.isEmpty() || tok.equals("-") || tok.equals("+")) {
            throw new JsonException("位置 " + start + " 处不是合法值");
        }
        try {
            if (isDouble) {
                return Double.valueOf(tok);
            }
            return Long.valueOf(tok);
        } catch (NumberFormatException ex) {
            throw new JsonException("无法解析数值：" + tok);
        }
    }

    private char peek() {
        if (pos >= src.length()) {
            throw new JsonException("输入意外结束");
        }
        return src.charAt(pos);
    }

    private void expect(String literal) {
        if (!src.startsWith(literal, pos)) {
            throw new JsonException("位置 " + pos + " 期望 " + literal);
        }
        pos += literal.length();
    }

    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    // ------------------------------------------------------------------
    // 序列化
    // ------------------------------------------------------------------

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            writeString(sb, (String) v);
        } else if (v instanceof Boolean) {
            sb.append(v.toString());
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else {
                sb.append(trimNumber(d));
            }
        } else if (v instanceof Number) {
            sb.append(v.toString());
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object o : (Iterable<?>) v) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, o);
            }
            sb.append(']');
        } else if (v instanceof double[]) {
            sb.append('[');
            double[] a = (double[]) v;
            for (int i = 0; i < a.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeValue(sb, a[i]);
            }
            sb.append(']');
        } else {
            writeString(sb, v.toString());
        }
    }

    private static String trimNumber(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------
    // 取值辅助
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object o) {
        if (o instanceof Map) {
            return (Map<String, Object>) o;
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object o) {
        if (o instanceof List) {
            return (List<Object>) o;
        }
        return new ArrayList<>();
    }

    public static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    public static double num(Object o, double def) {
        if (o instanceof Number) {
            return ((Number) o).doubleValue();
        }
        if (o instanceof String) {
            try {
                return Double.parseDouble(((String) o).trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public static int intOf(Object o, int def) {
        return (int) Math.round(num(o, def));
    }

    public static boolean bool(Object o, boolean def) {
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        if (o instanceof String) {
            return Boolean.parseBoolean((String) o);
        }
        if (o instanceof Number) {
            return ((Number) o).doubleValue() != 0;
        }
        return def;
    }
}