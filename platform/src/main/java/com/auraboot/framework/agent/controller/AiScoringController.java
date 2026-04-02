package com.auraboot.framework.agent.controller;

import com.auraboot.framework.agent.service.AiScoringService;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST endpoint for AI-powered lead scoring.
 */
@Slf4j
@RestController
@RequestMapping("/api/crm/ai")
@RequiredArgsConstructor
public class AiScoringController {

    private final AiScoringService aiScoringService;

    /**
     * Score CRM leads using LLM.
     * @param forceRescore if true, rescore all leads (not just unscored)
     */
    @PostMapping("/score-leads")
    public ApiResponse<Map<String, Object>> scoreLeads(
            @RequestParam(defaultValue = "false") boolean forceRescore) {
        try {
            Long tenantId = MetaContext.getCurrentTenantId();
            int scored = aiScoringService.scoreLeads(tenantId, forceRescore);
            return ApiResponse.success(Map.of(
                    "scored", scored,
                    "message", String.format("Successfully scored %d leads", scored)
            ));
        } catch (IllegalStateException e) {
            log.warn("AI scoring configuration error: {}", e.getMessage());
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            log.error("AI scoring failed", e);
            return ApiResponse.error("AI scoring failed: " + e.getMessage());
        }
    }
}
