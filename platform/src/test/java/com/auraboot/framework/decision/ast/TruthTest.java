package com.auraboot.framework.decision.ast;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Three-valued logic truth tables (docs/1.md §14.7). */
class TruthTest {

    @Test
    void andTable() {
        assertThat(Truth.TRUE.and(Truth.TRUE)).isEqualTo(Truth.TRUE);
        assertThat(Truth.TRUE.and(Truth.UNKNOWN)).isEqualTo(Truth.UNKNOWN);
        assertThat(Truth.FALSE.and(Truth.UNKNOWN)).isEqualTo(Truth.FALSE);
        assertThat(Truth.UNKNOWN.and(Truth.FALSE)).isEqualTo(Truth.FALSE);
        assertThat(Truth.UNKNOWN.and(Truth.UNKNOWN)).isEqualTo(Truth.UNKNOWN);
    }

    @Test
    void orTable() {
        assertThat(Truth.TRUE.or(Truth.UNKNOWN)).isEqualTo(Truth.TRUE);
        assertThat(Truth.FALSE.or(Truth.UNKNOWN)).isEqualTo(Truth.UNKNOWN);
        assertThat(Truth.FALSE.or(Truth.FALSE)).isEqualTo(Truth.FALSE);
        assertThat(Truth.UNKNOWN.or(Truth.TRUE)).isEqualTo(Truth.TRUE);
    }

    @Test
    void negateTable() {
        assertThat(Truth.TRUE.negate()).isEqualTo(Truth.FALSE);
        assertThat(Truth.FALSE.negate()).isEqualTo(Truth.TRUE);
        assertThat(Truth.UNKNOWN.negate()).isEqualTo(Truth.UNKNOWN);
    }

    @Test
    void onlyTrueIsMatch() {
        assertThat(Truth.TRUE.isMatch()).isTrue();
        assertThat(Truth.FALSE.isMatch()).isFalse();
        assertThat(Truth.UNKNOWN.isMatch()).isFalse();
    }
}
