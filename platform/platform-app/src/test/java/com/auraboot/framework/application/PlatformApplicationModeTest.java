package com.auraboot.framework.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PlatformApplicationModeTest {

    @AfterEach
    void clearMode() {
        System.clearProperty("aura.application.mode");
    }

    @Test
    void defaultsToCompatibilityApplication() {
        assertThat(PlatformApplication.applicationSource(new String[0])).isEqualTo(MetaApplication.class);
    }

    @Test
    void selectsCoreOnlySourceFromSystemProperty() {
        System.setProperty("aura.application.mode", "core-only");
        assertThat(PlatformApplication.applicationSource(new String[0])).isEqualTo(CoreOnlyApplication.class);
    }

    @Test
    void selectsCoreOnlySourceFromCommandLine() {
        assertThat(PlatformApplication.applicationSource(new String[] {"--aura.application.mode=core-only"}))
            .isEqualTo(CoreOnlyApplication.class);
    }
}
