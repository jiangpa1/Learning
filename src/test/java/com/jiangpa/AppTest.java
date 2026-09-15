package com.jiangpa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 骨架单元测试，用来确认测试环境（JUnit 5）可以正常工作。
 */
class AppTest {

    @Test
    void shouldRunBasicTest() {
        String value = "ok";
        assertNotNull(value);
    }
}
