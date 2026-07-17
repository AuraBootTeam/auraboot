package com.auraboot.framework.decision.service;

import com.auraboot.framework.decision.dto.DrtEvaluateRequest;
import com.auraboot.framework.decision.dto.DrtLogDTO;
import com.auraboot.framework.decision.dto.DrtTestRunRequest;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.decision.model.DecisionResult;
import com.auraboot.framework.decision.model.DecisionValidateResult;
import com.auraboot.framework.decision.dto.DrtValidateRequest;

import java.util.List;

/**
 * Decision evaluation and test-run service.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
public interface DecisionEvaluationService {

    /**
     * Authoritative evaluation:
     * 1. Resolve the version from the request's binding (LATEST or FIXED_VERSION).
     * 2. Build a {@link com.auraboot.framework.decision.ast.DecisionContext} from request.context.
     * 3. Call {@link com.auraboot.framework.decision.runtime.DecisionRuntime#evaluate}.
     * 4. Persist an audit log row.
     * 5. Return the {@link DecisionResult}.
     */
    DecisionResult evaluate(DrtEvaluateRequest request);

    /**
     * Batch evaluation (docs/1.md §9.2, §17.5) — SLA scheduler scans / bulk import. Each request is
     * evaluated independently; a failing request yields an ERROR result for that entry without
     * failing the batch. Returns one result per request, in order.
     */
    List<DecisionResult> batchEvaluate(List<DrtEvaluateRequest> requests);

    /**
     * In-memory test-run against draft content — no log entry is written.
     * Useful for the designer "try it" flow.
     */
    DecisionResult testRun(DrtTestRunRequest request);

    /**
     * Stateless validate call that does not require a persisted version.
     */
    DecisionValidateResult validate(DrtValidateRequest request);

    /**
     * Query evaluation logs by trace_id (tenant-scoped).
     */
    List<DrtLogDTO> findLogsByTraceId(String traceId);

    /**
     * Query one tenant-scoped evaluation log by public PID.
     */
    DrtLogDTO findLogByPid(String pid);

    /**
     * Query newest tenant-scoped evaluation logs for API-backed DSL list pages.
     */
    PageResult<DrtLogDTO> findRecentLogs(
            String keyword,
            String decisionCode,
            String status,
            String callerType,
            String callerRef,
            Boolean matched,
            String rolloutArm,
            Long minDurationMs,
            Long maxDurationMs,
            int page,
            int size);
}
