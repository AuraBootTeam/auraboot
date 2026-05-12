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
}
