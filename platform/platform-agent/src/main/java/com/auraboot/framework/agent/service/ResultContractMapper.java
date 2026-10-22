package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.BusinessIntentFrame;
import com.auraboot.framework.agent.dto.ResultContract;
import com.auraboot.framework.agent.dto.SkillResult;
import org.springframework.stereotype.Service;

/**
 * Converts internal SkillResult → external ResultContract, the ONLY output
 * shape exposed to frontend / API / SSE consumers. Hides engine internals
 * (actionPids, rawSteps, cost telemetry) — everything downstream of a chat
 * turn should flow through this mapper.
 *
 * The static factory on ResultContract itself (fromSkillResult) already
 * exists; this injectable service wraps it so callers can be tested/mocked
 * and so actionability resolution (BIF → contract field) is centralised.
 */
@Service
public class ResultContractMapper {

    /**
     * Convert a SkillResult to a ResultContract, deriving actionability from
     * the current turn's BIF when present (falls back to "read_only" if no
     * BIF context is bound — safest default).
     */
    public ResultContract toContract(SkillResult skillResult) {
        String actionability = deriveActionability();
        return ResultContract.fromSkillResult(skillResult, actionability);
    }

    /**
     * Variant that takes actionability explicitly — used when the caller has
     * already resolved it (e.g. for tests, or when BIF is not in scope).
     */
    public ResultContract toContract(SkillResult skillResult, String actionability) {
        return ResultContract.fromSkillResult(skillResult, actionability);
    }

    private String deriveActionability() {
        BusinessIntentFrame bif = BifContext.getCurrentBif();
        if (bif != null && bif.getActionability() != null) {
            return bif.getActionability();
        }
        return "read_only";
    }
}
