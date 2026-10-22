package com.auraboot.framework.agent.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentObservationServiceMappingTest {

    @Test
    void syncTurnLifecycleEventsKeepTheirMeaningForOnlineEval() {
        assertThat(AgentObservationService.mapToObservationType("turn.completed"))
                .isEqualTo("turn_completed");
        assertThat(AgentObservationService.mapToObservationType("turn.failed"))
                .isEqualTo("turn_failed");
        assertThat(AgentObservationService.mapToObservationType("turn.interrupted"))
                .isEqualTo("turn_interrupted");
        assertThat(AgentObservationService.mapToSeverity("turn.failed")).isEqualTo("error");
    }
}
