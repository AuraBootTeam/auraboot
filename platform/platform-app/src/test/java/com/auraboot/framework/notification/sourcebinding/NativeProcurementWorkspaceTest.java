package com.auraboot.framework.notification.sourcebinding;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NativeProcurementWorkspaceTest {
    @Test void missingWorkspaceHasNoMachineSpecificFallback() {
        assertThatThrownBy(() -> NativeProcurementWorkspace.root(Map.of()))
                .hasMessage("AURA_WORKSPACE_ROOT_REQUIRED");
        assertThatThrownBy(() -> NativeProcurementWorkspace.root(Map.of("AURA_WORKSPACE_ROOT", " ")))
                .hasMessage("AURA_WORKSPACE_ROOT_REQUIRED");
    }
    @Test void relativeWorkspaceCannotRebaseNativeEvidenceOntoTheCurrentDirectory() {
        assertThatThrownBy(() -> NativeProcurementWorkspace.root(Map.of("AURA_WORKSPACE_ROOT", "fixture/workspace")))
                .hasMessage("AURA_WORKSPACE_ROOT_MUST_BE_ABSOLUTE");
    }
    @Test void explicitAbsoluteWorkspaceIsNormalizedWithoutReadingPrivateFiles() {
        assertThat(NativeProcurementWorkspace.root(Map.of("AURA_WORKSPACE_ROOT", "/fixture/child/../workspace")))
                .isEqualTo(Path.of("/fixture/workspace"));
    }
    @Test void explicitRuntimeCannotSelectAnUnownedEnvironmentOrEscapeItsDirectory() {
        for (String runtime : java.util.List.of("production", "../other", "procurement-main-validation/child", " ")) {
            assertThatThrownBy(() -> NativeProcurementWorkspace.root(Map.of(
                    "AURA_WORKSPACE_ROOT", "/fixture/workspace", "PROCUREMENT_NATIVE_RUNTIME", runtime)))
                    .hasMessage("PROCUREMENT_NATIVE_RUNTIME_NOT_OWNED");
        }
    }
    @Test void runtimeChoiceIsExplicitAndLegacyRemainsTheDefault() {
        assertThat(NativeProcurementWorkspace.runtime(Map.of())).isEqualTo("procurement-web-stack-20261010");
        assertThat(NativeProcurementWorkspace.runtime(Map.of("PROCUREMENT_NATIVE_RUNTIME", "procurement-main-validation")))
                .isEqualTo("procurement-main-validation");
    }
}
