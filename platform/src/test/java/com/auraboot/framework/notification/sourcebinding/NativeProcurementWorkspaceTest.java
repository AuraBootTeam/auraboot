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
}
