package com.auraboot.framework.agent;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

/** Reject evaluator artifacts: echoed questions, historic values, substrings and blank answers. */
class MemorySkillEffectivenessOracleTest {
    @Test
    void currentStructuredFactsAreRequired() {
        assertThat(MemorySkillBusinessEffectivenessLiveIT.currentFactsPresent("status=OPEN; days_late=12")).isTrue();
        assertThat(MemorySkillBusinessEffectivenessLiveIT.currentFactsPresent("**status=OPEN; days_late=12**")).isTrue();
        for (String bad : new String[]{"", "OPEN 12", "status=CLOSED; days_late=12", "status=OPEN; days_late=112",
                "status=<raw enum>; days_late=<actual integer>", "Historical quote: status=OPEN; days_late=12"}) {
            assertThat(MemorySkillBusinessEffectivenessLiveIT.currentFactsPresent(bad)).as(bad).isFalse();
        }
    }
}
