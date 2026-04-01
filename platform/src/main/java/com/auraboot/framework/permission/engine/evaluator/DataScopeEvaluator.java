package com.auraboot.framework.permission.engine.evaluator;

import com.auraboot.framework.permission.engine.model.DataScopeCondition;
import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import com.auraboot.framework.permission.service.DataScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Data scope evaluator — checks data visibility scope based on role data scope configuration.
 *
 * <p>Delegates to {@link DataScopeService} for scope resolution and applies
 * the result to individual record access checks or SQL condition generation.
 */
@Component
@RequiredArgsConstructor
public class DataScopeEvaluator {

    private static final String NAME = "DataScope";

    private final DataScopeService dataScopeService;

    /**
     * Evaluate whether the member can access a specific record based on data scope.
     *
     * @param memberId member (tenant member) ID
     * @param resource resource identifier (model code)
     * @param action   action identifier (e.g. "read")
     * @param record   the target record (Map for dynamic table data)
     * @return evaluation step with verdict
     */
    @SuppressWarnings("unchecked")
    public EvaluationStep evaluate(Long memberId, String resource, String action, Object record) {
        DataScopeCondition condition = dataScopeService.resolveScope(memberId, resource, action);

        String scopeType = condition.scopeType();

        if ("not_configured".equals(scopeType)) {
            return new EvaluationStep(NAME, EvaluationVerdict.NOT_APPLICABLE,
                    "Scope: not_configured — data scope not enabled for this resource");
        }

        if ("all".equals(scopeType)) {
            return new EvaluationStep(NAME, EvaluationVerdict.NOT_APPLICABLE, "Scope: all — no restriction");
        }

        if ("none".equals(scopeType)) {
            return new EvaluationStep(NAME, EvaluationVerdict.DENY, "Scope: none — access denied");
        }

        // For record-level checks, we need the record as a Map
        if (!(record instanceof Map)) {
            // Cannot evaluate non-map records, allow through
            return new EvaluationStep(NAME, EvaluationVerdict.NOT_APPLICABLE,
                    "Record is not a Map, skipping data scope check");
        }

        Map<String, Object> recordMap = (Map<String, Object>) record;

        if ("self".equals(scopeType)) {
            return evaluateSelf(condition, recordMap);
        }

        if ("dept".equals(scopeType) || "dept_and_sub".equals(scopeType)) {
            return evaluateDept(condition, recordMap);
        }

        return new EvaluationStep(NAME, EvaluationVerdict.NOT_APPLICABLE,
                "Unknown scope type: " + scopeType);
    }

    /**
     * Get the SQL-level data scope condition for list queries.
     *
     * <p>When scope is "not_configured", no data scope is enabled for this resource and
     * we return {@link DataScopeCondition#all()} so the SQL layer applies no row filter.
     * This is semantically distinct from an explicit "all" grant: the evaluator step
     * returns NOT_APPLICABLE (not ALLOW), preserving deny-by-default in the pipeline.
     *
     * @param memberId member (tenant member) ID
     * @param resource resource identifier (model code)
     * @param action   action identifier
     * @return data scope condition for SQL generation
     */
    public DataScopeCondition getCondition(Long memberId, String resource, String action) {
        DataScopeCondition condition = dataScopeService.resolveScope(memberId, resource, action);
        if ("not_configured".equals(condition.scopeType())) {
            // Data scope is not configured for this resource — no SQL filtering needed.
            // The RBAC layer is still responsible for overall access control.
            return DataScopeCondition.all();
        }
        return condition;
    }

    // ========================================================================
    // Private: record-level evaluation
    // ========================================================================

    private EvaluationStep evaluateSelf(DataScopeCondition condition, Map<String, Object> record) {
        Object createdBy = record.get(condition.ownerField());
        if (createdBy == null) {
            return new EvaluationStep(NAME, EvaluationVerdict.DENY,
                    "Record has no " + condition.ownerField() + " field");
        }

        long createdById;
        if (createdBy instanceof Number) {
            createdById = ((Number) createdBy).longValue();
        } else {
            createdById = Long.parseLong(String.valueOf(createdBy));
        }

        if (createdById == condition.ownerValue()) {
            return new EvaluationStep(NAME, EvaluationVerdict.ALLOW,
                    "Scope: self — record owned by current user");
        }

        return new EvaluationStep(NAME, EvaluationVerdict.DENY,
                "Scope: self — record not owned by current user");
    }

    private EvaluationStep evaluateDept(DataScopeCondition condition, Map<String, Object> record) {
        if (condition.deptPids() == null || condition.deptPids().isEmpty()) {
            return new EvaluationStep(NAME, EvaluationVerdict.DENY,
                    "Scope: dept — no department PIDs resolved");
        }

        Object deptValue = record.get(condition.deptField());
        if (deptValue == null) {
            // If record has no dept field, fall back to owner check
            return evaluateSelf(condition, record);
        }

        String deptPid = String.valueOf(deptValue);
        if (condition.deptPids().contains(deptPid)) {
            return new EvaluationStep(NAME, EvaluationVerdict.ALLOW,
                    "Scope: " + condition.scopeType() + " — record in accessible department");
        }

        return new EvaluationStep(NAME, EvaluationVerdict.DENY,
                "Scope: " + condition.scopeType() + " — record not in accessible department");
    }
}
