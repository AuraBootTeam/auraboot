package com.auraboot.framework.decision.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.decision.dto.DecisionImpactDTO;
import com.auraboot.framework.decision.dto.DecisionImpactRiskDTO;
import com.auraboot.framework.decision.service.DecisionImpactService;
import com.auraboot.framework.decision.service.impl.DecisionImpactServiceImpl;
import com.auraboot.framework.i18n.service.I18nService;
import com.auraboot.framework.i18n.util.I18nLocaleResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The impact read model lives in the service layer where no request locale exists, so the
 * empty-consumers summary is emitted as the stable {@code $i18n:} key and resolved here at
 * the response boundary (mirrors TenantSelectionController / GlobalExceptionHandler).
 * The response contract is unchanged: same endpoint, same DTO shape.
 */
class DecisionRuntimeControllerImpactLocalizationTest {

    private static final String KEY = "decision.impact.no_downstream_consumers";

    private DecisionImpactService impactService;
    private I18nService i18nService;
    private I18nLocaleResolver localeResolver;
    private HttpServletRequest request;
    private DecisionRuntimeController controller;

    @BeforeEach
    void setUp() {
        impactService = mock(DecisionImpactService.class);
        i18nService = mock(I18nService.class);
        localeResolver = mock(I18nLocaleResolver.class);
        request = mock(HttpServletRequest.class);
        // Only the impact + i18n collaborators participate in this contract; the remaining
        // constructor slots are other route dependencies unused by getDecisionImpact.
        controller = new DecisionRuntimeController(
                null, null, null, null, null, null,
                impactService,
                null, null, null, null, null, null,
                i18nService, localeResolver);
    }

    private DecisionImpactDTO impactWithSummary(String summary) {
        DecisionImpactRiskDTO risk = new DecisionImpactRiskDTO();
        risk.setBlocking(false);
        risk.setCounts(java.util.Map.of());
        risk.setSummary(summary);
        DecisionImpactDTO impact = new DecisionImpactDTO();
        impact.setDecisionCode("approval_routing");
        impact.setRisk(risk);
        return impact;
    }

    @Test
    void zhRequestResolvesNoDownstreamConsumersSummary() {
        when(impactService.getDecisionImpact("approval_routing"))
                .thenReturn(impactWithSummary(DecisionImpactServiceImpl.NO_DOWNSTREAM_CONSUMERS_KEY));
        when(localeResolver.resolveLocale(request)).thenReturn("zh-CN");
        when(i18nService.getValue("zh-CN", KEY)).thenReturn("无下游引用方");

        ApiResponse<DecisionImpactDTO> response = controller.getDecisionImpact("approval_routing", request);

        assertThat(response.getData().getRisk().getSummary()).isEqualTo("无下游引用方");
    }

    @Test
    void enRequestResolvesEnglishSummary() {
        when(impactService.getDecisionImpact("approval_routing"))
                .thenReturn(impactWithSummary(DecisionImpactServiceImpl.NO_DOWNSTREAM_CONSUMERS_KEY));
        when(localeResolver.resolveLocale(request)).thenReturn("en-US");
        when(i18nService.getValue("en-US", KEY)).thenReturn("No downstream consumers");

        ApiResponse<DecisionImpactDTO> response = controller.getDecisionImpact("approval_routing", request);

        assertThat(response.getData().getRisk().getSummary()).isEqualTo("No downstream consumers");
    }

    @Test
    void jaRequestFallsBackToBaseLocale() {
        when(impactService.getDecisionImpact("approval_routing"))
                .thenReturn(impactWithSummary(DecisionImpactServiceImpl.NO_DOWNSTREAM_CONSUMERS_KEY));
        when(localeResolver.resolveLocale(request)).thenReturn("ja-JP");
        when(i18nService.getValue("ja-JP", KEY)).thenReturn(null);
        when(i18nService.getValue("zh-CN", KEY)).thenReturn("无下游引用方");

        ApiResponse<DecisionImpactDTO> response = controller.getDecisionImpact("approval_routing", request);

        assertThat(response.getData().getRisk().getSummary()).isEqualTo("无下游引用方");
    }

    @Test
    void usedBySummaryPassesThroughWithoutTouchingI18n() {
        when(impactService.getDecisionImpact("approval_routing"))
                .thenReturn(impactWithSummary("Used by 2 automations + 1 SLA rule"));

        ApiResponse<DecisionImpactDTO> response = controller.getDecisionImpact("approval_routing", request);

        assertThat(response.getData().getRisk().getSummary()).isEqualTo("Used by 2 automations + 1 SLA rule");
        verifyNoInteractions(i18nService, localeResolver);
    }

    @Test
    void whollyUnresolvedKeyKeepsTheBareKeyInsteadOfLeakingTheDollarPrefix() {
        when(impactService.getDecisionImpact("approval_routing"))
                .thenReturn(impactWithSummary(DecisionImpactServiceImpl.NO_DOWNSTREAM_CONSUMERS_KEY));
        when(localeResolver.resolveLocale(request)).thenReturn("ja-JP");
        when(i18nService.getValue(anyString(), eq(KEY))).thenReturn(null);

        ApiResponse<DecisionImpactDTO> response = controller.getDecisionImpact("approval_routing", request);

        assertThat(response.getData().getRisk().getSummary()).isEqualTo(KEY);
    }
}
