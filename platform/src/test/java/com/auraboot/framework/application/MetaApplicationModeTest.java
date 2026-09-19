package com.auraboot.framework.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MetaApplicationModeTest {

    @AfterEach
    void clearMode() {
        System.clearProperty("aura.application.mode");
    }

    @Test
    void defaultsToCompatibilityApplication() {
        assertThat(MetaApplication.applicationSource(new String[0])).isEqualTo(MetaApplication.class);
    }

    @Test
    void selectsCoreOnlySourceFromSystemProperty() {
        System.setProperty("aura.application.mode", "core-only");
        assertThat(MetaApplication.applicationSource(new String[0])).isEqualTo(CoreOnlyApplication.class);
    }

    @Test
    void selectsCoreOnlySourceFromCommandLine() {
        assertThat(MetaApplication.applicationSource(new String[] {"--aura.application.mode=core-only"}))
            .isEqualTo(CoreOnlyApplication.class);
    }
}
