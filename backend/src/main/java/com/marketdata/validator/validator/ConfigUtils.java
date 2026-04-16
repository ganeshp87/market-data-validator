package com.marketdata.validator.validator;

/**
 * Shared utility methods for safe configuration value casting.
 * Prevents ClassCastException when config values are not the expected Number type.
 */
final class ConfigUtils {

    private ConfigUtils() { }

    static double toDouble(Object value, double fallback) {
        if (value instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(value.toString()); } catch (Exception e) { return fallback; }
    }

    static long toLong(Object value, long fallback) {
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(value.toString()); } catch (Exception e) { return fallback; }
    }

    static int toInt(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(value.toString()); } catch (Exception e) { return fallback; }
    }
}
