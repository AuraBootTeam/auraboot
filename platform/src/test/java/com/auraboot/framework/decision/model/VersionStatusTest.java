package com.auraboot.framework.decision.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** DecisionVersion lifecycle state machine (docs/1.md §13.6). */
class VersionStatusTest {

    @Test
    void allowedTransitions() {
        assertThat(VersionStatus.DRAFT.canTransitionTo(VersionStatus.VALIDATED)).isTrue();
        assertThat(VersionStatus.VALIDATED.canTransitionTo(VersionStatus.PUBLISHED)).isTrue();
        assertThat(VersionStatus.VALIDATED.canTransitionTo(VersionStatus.DRAFT)).isTrue();
        assertThat(VersionStatus.PUBLISHED.canTransitionTo(VersionStatus.DEPRECATED)).isTrue();
        assertThat(VersionStatus.DEPRECATED.canTransitionTo(VersionStatus.RETIRED)).isTrue();
    }

    @Test
    void forbiddenTransitions() {
        // cannot publish straight from draft (must validate first)
        assertThat(VersionStatus.DRAFT.canTransitionTo(VersionStatus.PUBLISHED)).isFalse();
        // published is immutable — cannot go back to draft
        assertThat(VersionStatus.PUBLISHED.canTransitionTo(VersionStatus.DRAFT)).isFalse();
        assertThat(VersionStatus.RETIRED.canTransitionTo(VersionStatus.PUBLISHED)).isFalse();
    }

    @Test
    void immutabilityAndBindability() {
        assertThat(VersionStatus.PUBLISHED.isImmutable()).isTrue();
        assertThat(VersionStatus.DRAFT.isImmutable()).isFalse();
        assertThat(VersionStatus.PUBLISHED.isBindable()).isTrue();
        assertThat(VersionStatus.DEPRECATED.isBindable()).isTrue();
        assertThat(VersionStatus.DRAFT.isBindable()).isFalse();
        assertThat(VersionStatus.RETIRED.isBindable()).isFalse();
    }
}
