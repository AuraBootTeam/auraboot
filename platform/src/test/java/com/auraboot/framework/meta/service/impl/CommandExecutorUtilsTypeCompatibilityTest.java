package com.auraboot.framework.meta.service.impl;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CommandExecutorUtils#isTypeCompatible(Object, String)}.
 * Covers all switch branches: datetime, integer, decimal, boolean, and default (text/enum/json).
 */
class CommandExecutorUtilsTypeCompatibilityTest {

    // ── datetime / date / timestamp ──────────────────────────────────────

    @Test
    void datetime_acceptsJavaUtilDate() {
        assertThat(CommandExecutorUtils.isTypeCompatible(new Date(), "datetime")).isTrue();
    }

    @Test
    void datetime_acceptsInstant() {
        assertThat(CommandExecutorUtils.isTypeCompatible(Instant.now(), "datetime")).isTrue();
    }

    @Test
    void datetime_acceptsLocalDateTime() {
        assertThat(CommandExecutorUtils.isTypeCompatible(LocalDateTime.now(), "datetime")).isTrue();
    }

    @Test
    void datetime_acceptsLocalDate() {
        assertThat(CommandExecutorUtils.isTypeCompatible(LocalDate.now(), "date")).isTrue();
    }

    @Test
    void datetime_acceptsSqlTimestamp() {
        assertThat(CommandExecutorUtils.isTypeCompatible(
                new Timestamp(System.currentTimeMillis()), "timestamp")).isTrue();
    }

    @Test
    void datetime_acceptsSqlDate() {
        assertThat(CommandExecutorUtils.isTypeCompatible(
                new java.sql.Date(System.currentTimeMillis()), "date")).isTrue();
    }

    @Test
    void datetime_rejectsString() {
        assertThat(CommandExecutorUtils.isTypeCompatible("2026-04-11T03:41:34Z", "datetime")).isFalse();
        assertThat(CommandExecutorUtils.isTypeCompatible("2026-04-11", "date")).isFalse();
    }

    @Test
    void datetime_rejectsLong() {
        assertThat(CommandExecutorUtils.isTypeCompatible(1712800000000L, "datetime")).isFalse();
    }

    // ── integer / int ────────────────────────────────────────────────────

    @Test
    void integer_acceptsIntAndLong() {
        assertThat(CommandExecutorUtils.isTypeCompatible(42, "integer")).isTrue();
        assertThat(CommandExecutorUtils.isTypeCompatible(42L, "int")).isTrue();
    }

    @Test
    void integer_acceptsBigDecimal() {
        // BigDecimal is a Number, so it passes the integer check
        assertThat(CommandExecutorUtils.isTypeCompatible(new BigDecimal("100"), "integer")).isTrue();
    }

    @Test
    void integer_rejectsString() {
        assertThat(CommandExecutorUtils.isTypeCompatible("42", "integer")).isFalse();
    }

    // ── decimal / float / double / money ─────────────────────────────────

    @Test
    void decimal_acceptsDoubleAndBigDecimal() {
        assertThat(CommandExecutorUtils.isTypeCompatible(3.14, "decimal")).isTrue();
        assertThat(CommandExecutorUtils.isTypeCompatible(new BigDecimal("99.99"), "money")).isTrue();
    }

    @Test
    void decimal_acceptsLong() {
        assertThat(CommandExecutorUtils.isTypeCompatible(100L, "float")).isTrue();
    }

    @Test
    void decimal_rejectsString() {
        assertThat(CommandExecutorUtils.isTypeCompatible("3.14", "double")).isFalse();
    }

    // ── boolean ──────────────────────────────────────────────────────────

    @Test
    void boolean_acceptsBoolean() {
        assertThat(CommandExecutorUtils.isTypeCompatible(true, "boolean")).isTrue();
        assertThat(CommandExecutorUtils.isTypeCompatible(false, "boolean")).isTrue();
    }

    @Test
    void boolean_rejectsString() {
        assertThat(CommandExecutorUtils.isTypeCompatible("true", "boolean")).isFalse();
    }

    @Test
    void boolean_rejectsInteger() {
        assertThat(CommandExecutorUtils.isTypeCompatible(1, "boolean")).isFalse();
    }

    // ── default (text, enum, json, reference, etc.) ──────────────────────

    @Test
    void text_acceptsAnything() {
        assertThat(CommandExecutorUtils.isTypeCompatible("hello", "text")).isTrue();
        assertThat(CommandExecutorUtils.isTypeCompatible(42, "text")).isTrue();
        assertThat(CommandExecutorUtils.isTypeCompatible(true, "enum")).isTrue();
        assertThat(CommandExecutorUtils.isTypeCompatible(new Object(), "json")).isTrue();
        assertThat(CommandExecutorUtils.isTypeCompatible(new Date(), "reference")).isTrue();
    }

    // ── case insensitivity ───────────────────────────────────────────────

    @Test
    void dataType_isCaseInsensitive() {
        assertThat(CommandExecutorUtils.isTypeCompatible(Instant.now(), "DateTime")).isTrue();
        assertThat(CommandExecutorUtils.isTypeCompatible(42, "INTEGER")).isTrue();
        assertThat(CommandExecutorUtils.isTypeCompatible(3.14, "DECIMAL")).isTrue();
        assertThat(CommandExecutorUtils.isTypeCompatible(true, "Boolean")).isTrue();
    }
}
