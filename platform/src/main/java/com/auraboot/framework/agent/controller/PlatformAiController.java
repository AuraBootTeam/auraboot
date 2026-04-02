package com.auraboot.framework.agent.controller;

import com.auraboot.framework.agent.dto.PlatformAiScoreRequest;
import com.auraboot.framework.agent.dto.PlatformAiScoreResult;
import com.auraboot.framework.agent.service.PlatformAiScoringService;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class PlatformAiController {

    private final PlatformAiScoringService platformAiScoringService;

    /**
     * AI-score records of any DSL model.
     * Uses configured LLM to score records 0-100 and write back to the specified field.
     */
    @PostMapping("/api/ai/score-records")
    public ApiResponse<PlatformAiScoreResult> scoreRecords(@RequestBody PlatformAiScoreRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        try {
            PlatformAiScoreResult result = platformAiScoringService.score(request, tenantId);
            return ApiResponse.success(result);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            log.error("AI scoring failed for model {}: {}", request.getModelCode(), e.getMessage(), e);
            return ApiResponse.error("AI scoring failed: " + e.getMessage());
        }
    }
}
