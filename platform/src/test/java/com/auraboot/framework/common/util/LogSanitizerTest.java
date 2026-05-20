package com.auraboot.framework.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogSanitizerTest {

    @Test
    void replacesLineBreaksAndTabs() {
        assertThat(LogSanitizer.safe("alpha\r\nbeta\tgamma")).isEqualTo("alpha__beta gamma");
    }

    @Test
    void handlesNull() {
        assertThat(LogSanitizer.safe((String) null)).isNull();
    }

    @Test
    void redactsCommonSecretValues() {
        String sanitized = LogSanitizer.safe(
                "apiKey=sk-secret password=secret-db Authorization: Bearer token-123 "
                        + "\"token\":\"json-token\" credential = abc");

        assertThat(sanitized)
                .contains("apiKey=[REDACTED]")
                .contains("password=[REDACTED]")
                .contains("Authorization: Bearer [REDACTED]")
                .contains("\"token\":\"[REDACTED]\"")
                .contains("credential = [REDACTED]")
                .doesNotContain("sk-secret")
                .doesNotContain("secret-db")
                .doesNotContain("token-123")
                .doesNotContain("json-token")
                .doesNotContain("abc");
    }
}
