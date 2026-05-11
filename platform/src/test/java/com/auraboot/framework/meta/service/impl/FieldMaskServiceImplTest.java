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
}
