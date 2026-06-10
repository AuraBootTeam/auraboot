package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.decision.dto.DecisionImpactRefDTO;
import com.auraboot.framework.decision.dto.DecisionIntegrationImpactDTO;
import com.auraboot.framework.decision.service.DecisionImpactAckService;
import com.auraboot.framework.decision.service.DecisionUsageIndexService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DecisionImpactServiceImplTest {

    private final DecisionUsageIndexService usageIndexService = mock(DecisionUsageIndexService.class);
    private final DecisionImpactAckService impactAckService = mock(DecisionImpactAckService.class);
    private final DecisionImpactServiceImpl service =
            new DecisionImpactServiceImpl(usageIndexService, impactAckService);

    @Test
    void getIntegrationImpactReturnsConnectorConsumersAndManagementUrl() {
        DecisionImpactRefDTO ref = new DecisionImpactRefDTO();
        ref.setSourceType("AUTOMATION");
        ref.setSourceCode("auto-1");
        ref.setSourceName("Escalation Flow");
        ref.setTargetType("CONNECTOR");
        ref.setTargetCode("api-1");
        ref.setTargetPath("enrich");
        ref.setMetadata(Map.of("actionType", "call_api"));
        when(usageIndexService.findTargetRefs("CONNECTOR", "api-1")).thenReturn(List.of(ref));

        DecisionIntegrationImpactDTO impact = service.getIntegrationImpact("CONNECTOR", "api-1");

        assertThat(impact.getTargetType()).isEqualTo("CONNECTOR");
        assertThat(impact.getTargetCode()).isEqualTo("api-1");
        assertThat(impact.getManageUrl()).isEqualTo("/p/api_connector");
        assertThat(impact.getReferences()).containsExactly(ref);
        assertThat(impact.getRisk().getBlocking()).isTrue();
        assertThat(impact.getRisk().getCounts()).containsEntry("AUTOMATION", 1);
        assertThat(impact.getRisk().getSummary()).isEqualTo("Used by 1 automation");
    }
}
