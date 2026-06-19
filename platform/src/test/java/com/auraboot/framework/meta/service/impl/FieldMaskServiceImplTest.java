package com.auraboot.framework.meta.service.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FieldMaskServiceImplTest {

    private final FieldMaskServiceImpl service = new FieldMaskServiceImpl(null, null);

    @Test
    void customMaskTreatsPatternAsLiteralText() {
        String masked = service.maskValue("a+b aab", "custom", "a+", "*");

        assertThat(masked).isEqualTo("**b aab");
    }

    @Test
    void hashMaskProducesDeterministicHexDigest() {
        String masked = service.maskValue("customer@example.com", "hash", null, "*");
        String maskedAgain = service.maskValue("customer@example.com", "hash", null, "*");

        assertThat(masked).hasSize(16);
        assertThat(masked).matches("[0-9a-f]{16}");
        assertThat(masked).isEqualTo(maskedAgain);
        assertThat(masked).isNotEqualTo("customer@example.com");
    }

    @Test
    void phoneMaskKeepsHeadAndTail() {
        assertThat(service.maskValue("13812345678", "phone", null, "*")).isEqualTo("138****5678");
        assertThat(service.maskValue("13812345678", "phone", null, "#")).isEqualTo("138####5678");
        assertThat(service.maskValue("123", "phone", null, "*")).isEqualTo("123"); // too short
    }

    @Test
    void emailMaskKeepsTwoCharsAndDomain() {
        assertThat(service.maskValue("customer@example.com", "email", null, "*")).isEqualTo("cu***@example.com");
        assertThat(service.maskValue("no-at-sign", "email", null, "*")).isEqualTo("no-at-sign");
    }

    @Test
    void idCardMaskKeepsHeadTail() {
        assertThat(service.maskValue("110101199003071234", "id_card", null, "*")).isEqualTo("1101**********1234");
        assertThat(service.maskValue("1234567", "id_card", null, "*")).isEqualTo("1234567"); // < 8
    }

    @Test
    void bankCardMaskKeepsLastFour() {
        assertThat(service.maskValue("6222021234567890", "bank_card", null, "*")).isEqualTo("************7890");
        assertThat(service.maskValue("abc", "bank_card", null, "*")).isEqualTo("abc"); // < 4
    }

    @Test
    void nameMaskKeepsFirstChar() {
        assertThat(service.maskValue("John", "name", null, "*")).isEqualTo("J***");
        assertThat(service.maskValue("X", "name", null, "*")).isEqualTo("X"); // < 2
    }

    @Test
    void fullMaskRepeatsReplacementCharCappedAtTen() {
        assertThat(service.maskValue("short", "full", null, "*")).isEqualTo("*****");
        assertThat(service.maskValue("a-very-long-secret-value", "full", null, "*")).isEqualTo("**********");
    }

    @Test
    void partialMaskUsesDefaultsAndExplicitPattern() {
        assertThat(service.maskValue("1234567890", "partial", null, "*")).isEqualTo("123***7890");
        assertThat(service.maskValue("1234567890", "partial", "2,2", "*")).isEqualTo("12******90");
    }

    @Test
    void unknownTypeAndBlankValuePassThrough() {
        assertThat(service.maskValue("keepme", "no_such_type", null, "*")).isEqualTo("keepme");
        assertThat(service.maskValue(null, "phone", null, "*")).isNull();
        assertThat(service.maskValue("", "phone", null, "*")).isEqualTo("");
    }
}
