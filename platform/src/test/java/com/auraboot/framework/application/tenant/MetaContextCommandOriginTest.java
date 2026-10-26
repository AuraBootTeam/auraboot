package com.auraboot.framework.application.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetaContextCommandOriginTest {
    @AfterEach void clear() { MetaContext.clear(); }

    @Test void onlyTheOuterAuthorizedCommandFrameIsRootAndFailuresRestoreIt() {
        MetaContext.setContext(1L,2L,"actor","actor");
        assertThat(MetaContext.isRootAuthorizedCommand()).isFalse();
        MetaContext.runWithCommandPermitScope("ALL", () -> MetaContext.runWithAuthorizedCommandCode("root", () -> {
            assertThat(MetaContext.isRootAuthorizedCommand()).isTrue();
            assertThatThrownBy(() -> MetaContext.runWithAuthorizedCommandCode("nested", () -> {
                assertThat(MetaContext.isRootAuthorizedCommand()).isFalse();
                throw new IllegalStateException("nested failed");
            })).hasMessage("nested failed");
            assertThat(MetaContext.isRootAuthorizedCommand()).isTrue();
            assertThat(MetaContext.getAuthorizedCommandCode()).isEqualTo("root");
        }));
        assertThat(MetaContext.isRootAuthorizedCommand()).isFalse();
    }

    @Test void identitySnapshotDoesNotPropagateRootAuthorityEvenOnCallerRuns() {
        MetaContext.setContext(1L,2L,"actor","actor");
        MetaContext.runWithCommandPermitScope("ALL", () -> MetaContext.runWithAuthorizedCommandCode("root", () -> {
            MetaContext.runWithSnapshot(MetaContext.snapshot(), () -> {
                assertThat(MetaContext.getCurrentUserId()).isEqualTo(2L);
                assertThat(MetaContext.isRootAuthorizedCommand()).isFalse();
                assertThat(MetaContext.getAuthorizedCommandCode()).isNull();
            });
            assertThat(MetaContext.isRootAuthorizedCommand()).isTrue();
        }));
        assertThat(MetaContext.isRootAuthorizedCommand()).isFalse();
    }

    @Test void clearRemovesRootProvenance() {
        MetaContext.runWithCommandPermitScope("ALL", () -> MetaContext.runWithAuthorizedCommandCode("root", () -> {
            assertThat(MetaContext.isRootAuthorizedCommand()).isTrue();
            MetaContext.clear();
            assertThat(MetaContext.isRootAuthorizedCommand()).isFalse();
        }));
        assertThat(MetaContext.isRootAuthorizedCommand()).isFalse();
    }
}
