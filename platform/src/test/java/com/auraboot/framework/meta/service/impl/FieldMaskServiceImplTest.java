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
}
