package com.auraboot.framework.agent.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agent runtime carries two risk scales, and until now they never met.
 *
 * <p>They are not duplicates: L0–L4 decides whether the platform demands an
 * approval, LOW/MEDIUM/HIGH/BLOCKED decides what a mobile client puts in front
 * of a person. What was wrong is that each was derived independently from the
 * action type, so the two could drift apart and nothing anywhere would fail —
 * the sort of disagreement that only shows up as "why did this delete not ask
 * me anything".
 */
@DisplayName("The two risk scales agree with each other")
class AiActionRiskLevelBridgeTest {

    @ParameterizedTest(name = "{0} ⇄ {1}")
    @CsvSource({
            "LOW,     L0",
            "MEDIUM,  L1",
            "HIGH,    L3",
            "BLOCKED, L4",
    })
    void mapsToThePlatformScale(AiActionRiskLevel level, String expected) {
        assertThat(level.toPlatformRiskLevel()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "L{0} reads back as a confirmation strength")
    @CsvSource({
            "L0, LOW",
            "L1, MEDIUM",
            // L2 is cross-object work: more than a single edit, still short of
            // the irreversible and external levels that earn a full-screen stop.
            "L2, MEDIUM",
            "L3, HIGH",
            "L4, BLOCKED",
    })
    void readsThePlatformScale(String platformLevel, AiActionRiskLevel expected) {
        assertThat(AiActionRiskLevel.fromPlatformRiskLevel(platformLevel)).isEqualTo(expected);
    }

    @Test
    @DisplayName("round-tripping a client level through the platform scale does not weaken it")
    void roundTripNeverWeakens() {
        // The direction that matters. A mapping that quietly downgraded on the
        // way back would turn a full-screen stop into a silent action, which is
        // exactly the failure a bridge between two scales is there to prevent.
        for (AiActionRiskLevel level : AiActionRiskLevel.values()) {
            AiActionRiskLevel back = AiActionRiskLevel.fromPlatformRiskLevel(level.toPlatformRiskLevel());
            assertThat(back.ordinal())
                    .as("%s must not come back weaker than it went in", level)
                    .isGreaterThanOrEqualTo(level.ordinal());
        }
    }

    @ParameterizedTest(name = "unrecognised input {0} is treated as high, not low")
    @ValueSource(strings = {"", "  ", "L9", "high", "unknown", "R3"})
    void unknownLevelsFailTowardsAsking(String junk) {
        // Unparseable is not the same as safe. Defaulting down would convert a
        // scale we failed to read into an action nobody was asked about.
        assertThat(AiActionRiskLevel.fromPlatformRiskLevel(junk))
                .isIn(AiActionRiskLevel.HIGH, AiActionRiskLevel.BLOCKED);
    }

    @Test
    @DisplayName("a null level asks rather than assumes")
    void nullIsNotTreatedAsHarmless() {
        assertThat(AiActionRiskLevel.fromPlatformRiskLevel(null))
                .isNotEqualTo(AiActionRiskLevel.LOW);
    }
}
