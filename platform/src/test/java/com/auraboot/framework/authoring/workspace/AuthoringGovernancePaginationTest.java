package com.auraboot.framework.authoring.workspace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoringGovernancePaginationTest {

    @Test
    void computesBoundedReleaseHistoryOffsets() {
        assertThat(AuthoringGovernanceService.releaseHistoryOffset(1, 20)).isZero();
        assertThat(AuthoringGovernanceService.releaseHistoryOffset(100_000, 100))
                .isEqualTo(9_999_900);
    }

    @Test
    void rejectsPaginationOutsideTheServiceBoundary() {
        assertThatThrownBy(() -> AuthoringGovernanceService.releaseHistoryOffset(0, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuthoringGovernanceService.releaseHistoryOffset(1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuthoringGovernanceService.releaseHistoryOffset(
                Integer.MAX_VALUE, Integer.MAX_VALUE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
