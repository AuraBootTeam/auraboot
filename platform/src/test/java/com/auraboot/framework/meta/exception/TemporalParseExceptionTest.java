package com.auraboot.framework.meta.exception;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TemporalParseExceptionTest {

    @Test
    void shouldCarryFieldAndValue() {
        var ex = new TemporalParseException("birthDate", "2026/01/01", "ISO-8601 date (yyyy-MM-dd)");
        assertThat(ex.getField()).isEqualTo("birthDate");
        assertThat(ex.getRawValue()).isEqualTo("2026/01/01");
        assertThat(ex.getExpected()).isEqualTo("ISO-8601 date (yyyy-MM-dd)");
        assertThat(ex.getMessage()).contains("birthDate");
    }
}
