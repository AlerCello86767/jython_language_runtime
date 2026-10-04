package com.AlerCello86767.jython_language_runtime.core;

import java.util.List;
import java.util.Map;

/**
 * 「声明式参数包」的取值内核：把 Python 传来的值防御性转成 Java 值，缺失/类型不符时用默认值。
 *
 * <p>Jython 把 Python 的 dict 传成 {@link Map}、list 传成 {@link List}、数值传成 {@code Number}、
 * 布尔传成 {@code Boolean}、字符串传成 {@code String}，因此这里统一用 instanceof 判定。
 *
 * <p>主源集与 client 源集共用，因此是 public。
 */
public final class Params {
    private Params() {
    }

    public static String asString(Object value, String fallback) {
        return value instanceof String ? (String) value : fallback;
    }

    public static int asInt(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    public static float asFloat(Object value, float fallback) {
        return value instanceof Number ? ((Number) value).floatValue() : fallback;
    }

    public static double asDouble(Object value, double fallback) {
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    public static boolean asBoolean(Object value, boolean fallback) {
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    /** 非 Map 时返回 null。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    /** 非 List 时返回 null。 */
    public static List<?> asList(Object value) {
        return value instanceof List ? (List<?>) value : null;
    }

    /** 颜色取值：支持整数 RGB 与 {@code "#RRGGBB"} / {@code "#AARRGGBB"} 字符串。 */
    public static int asColor(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        String text = value instanceof String ? (String) value : null;
        if (text != null && text.startsWith("#")) {
            return (int) Long.parseLong(text.substring(1), 16);
        }
        throw new IllegalArgumentException("Invalid color: " + value);
    }

    /** 必填字符串：缺失或类型不符时抛错。 */
    public static String requireString(Object value, String what) {
        if (value instanceof String) {
            return (String) value;
        }
        throw new IllegalArgumentException("Missing or invalid '" + what + "': " + value);
    }
}
