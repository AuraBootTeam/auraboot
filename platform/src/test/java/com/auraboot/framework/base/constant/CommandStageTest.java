package com.auraboot.framework.base.constant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for CommandStage constants and utility methods.
 */
class CommandStageTest {

    @Test
    void shouldHave20TransactionalStages() {
        assertThat(CommandStage.TOTAL_TRANSACTIONAL_STAGES).isEqualTo(20);
    }

    @Test
    void shouldHaveConsecutiveStageNumbers() {
        // Transactional stages: 1 through 20
        assertThat(CommandStage.LOAD).isEqualTo(1);
        assertThat(CommandStage.POST_INVARIANT).isEqualTo(20);

        // After-commit stages: 21 through 24
        assertThat(CommandStage.DOMAIN_EVENT).isEqualTo(21);
        assertThat(CommandStage.GOVERNANCE_SNAPSHOT).isEqualTo(24);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24})
    void shouldReturnKnownNameForAllStages(int stage) {
        String name = CommandStage.nameOf(stage);
        assertThat(name).doesNotStartWith("unknown");
        assertThat(name).isNotBlank();
    }

    @Test
    void shouldReturnUnknownForInvalidStage() {
        assertThat(CommandStage.nameOf(0)).isEqualTo("UNKNOWN(0)");
        assertThat(CommandStage.nameOf(99)).isEqualTo("UNKNOWN(99)");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24})
    void shouldReturnDescriptionForAllStages(int stage) {
        String description = CommandStage.descriptionOf(stage);
        assertThat(description).isNotBlank();
    }

    @Test
    void shouldReturnEmptyDescriptionForInvalidStage() {
        assertThat(CommandStage.descriptionOf(0)).isEmpty();
        assertThat(CommandStage.descriptionOf(99)).isEmpty();
    }

    @Test
    void shouldHaveCorrectStageNames() {
        assertThat(CommandStage.nameOf(CommandStage.LOAD)).isEqualTo("load");
        assertThat(CommandStage.nameOf(CommandStage.SCHEMA_VALIDATE)).isEqualTo("schema_validate");
        assertThat(CommandStage.nameOf(CommandStage.HANDLER)).isEqualTo("handler");
        assertThat(CommandStage.nameOf(CommandStage.EFFECT)).isEqualTo("effect");
        assertThat(CommandStage.nameOf(CommandStage.WEBHOOK)).isEqualTo("webhook");
    }
}
