package com.auraboot.framework.plugin.extension;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationModuleExtensionContractTest {

    @Test
    void keepsProductConfigurationOpaqueToTheHost() {
        ApplicationModuleExtension extension = new ApplicationModuleExtension() {
            @Override public String moduleId() { return "fixture.workflow"; }
            @Override public List<Class<?>> configurationTypes() { return List.of(FixtureConfiguration.class); }
        };

        assertThat(extension.moduleId()).isEqualTo("fixture.workflow");
        assertThat(extension.configurationTypes()).containsExactly(FixtureConfiguration.class);
    }

    private static final class FixtureConfiguration {}
}
