package com.marketdata.validator.validator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigUtilsTest {

    // --- toDouble ---

    @Test
    void toDouble_withNumber() {
        assertEquals(3.14, ConfigUtils.toDouble(3.14, 0.0));
    }

    @Test
    void toDouble_withIntegerNumber() {
        assertEquals(42.0, ConfigUtils.toDouble(42, 0.0));
    }

    @Test
    void toDouble_withStringNumber() {
        assertEquals(2.5, ConfigUtils.toDouble("2.5", 0.0));
    }

    @Test
    void toDouble_withInvalidString() {
        assertEquals(9.9, ConfigUtils.toDouble("not-a-number", 9.9));
    }

    @Test
    void toDouble_withNull() {
        assertEquals(1.0, ConfigUtils.toDouble(null, 1.0));
    }

    // --- toLong ---

    @Test
    void toLong_withNumber() {
        assertEquals(100L, ConfigUtils.toLong(100, 0L));
    }

    @Test
    void toLong_withDoubleNumber() {
        assertEquals(3L, ConfigUtils.toLong(3.7, 0L));
    }

    @Test
    void toLong_withStringNumber() {
        assertEquals(500L, ConfigUtils.toLong("500", 0L));
    }

    @Test
    void toLong_withInvalidString() {
        assertEquals(42L, ConfigUtils.toLong("abc", 42L));
    }

    @Test
    void toLong_withNull() {
        assertEquals(10L, ConfigUtils.toLong(null, 10L));
    }

    // --- toInt ---

    @Test
    void toInt_withNumber() {
        assertEquals(7, ConfigUtils.toInt(7, 0));
    }

    @Test
    void toInt_withDoubleNumber() {
        assertEquals(3, ConfigUtils.toInt(3.9, 0));
    }

    @Test
    void toInt_withStringNumber() {
        assertEquals(25, ConfigUtils.toInt("25", 0));
    }

    @Test
    void toInt_withInvalidString() {
        assertEquals(5, ConfigUtils.toInt("xyz", 5));
    }

    @Test
    void toInt_withNull() {
        assertEquals(99, ConfigUtils.toInt(null, 99));
    }
}
