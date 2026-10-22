package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.PlatformAiScoreRequest;
import com.auraboot.framework.agent.dto.PlatformAiScoreResult;

/**
 * Platform-level AI record scoring service.
 *
 * <p>Unlike {@link AiScoringService} (CRM-specific), this service works with any DSL model
 * by accepting the table structure and scoring dimensions as parameters.
 *
 * <p>Scoring flow:
 * <ol>
 *   <li>Resolve the model's table name via MetaModelService.</li>
 *   <li>Fetch records (filtered by recordPids or limit).</li>
 *   <li>Batch records and send to LLM with configurable scoring dimensions.</li>
 *   <li>Parse LLM response (JSON array of {id, score}).</li>
 *   <li>Write scores back to the model's score field via DynamicDataMapper.</li>
 * </ol>
 */
public interface PlatformAiScoringService {

    /**
     * Score records in the specified model and write results back to {@code scoreField}.
     *
     * @param request  scoring configuration
     * @param tenantId current tenant
     * @return aggregated scoring result
     * @throws Exception if LLM call or DB write fails
     */
    PlatformAiScoreResult score(PlatformAiScoreRequest request, Long tenantId) throws Exception;
}
