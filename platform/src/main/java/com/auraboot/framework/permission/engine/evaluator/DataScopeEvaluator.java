package com.auraboot.framework.permission.engine.evaluator;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.engine.model.DataScopeCondition;
import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import com.auraboot.framework.permission.engine.model.SharedRootReference;
import com.auraboot.framework.permission.service.DataScopeService;
import com.auraboot.framework.permission.service.RecordShareService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Data scope evaluator — checks data visibility scope based on role data scope configuration.
 *
 * <p>Delegates to {@link DataScopeService} for scope resolution and applies
 * the result to individual record access checks or SQL condition generation.
 *
 * <p>On top of the surface scope, both {@link #evaluate} and {@link #getCondition} union the
 * declared shared-aggregate surface resolved by {@link RecordShareRowSurfaceResolver}: a record
 * referencing an aggregate root that is shared with the caller stays visible even when the child
 * resource's own surface scope (self/dept/none) would deny it.
 */
@Component
@RequiredArgsConstructor
public class DataScopeEvaluator {

    private static final String NAME = "DataScope";

    private final DataScopeService dataScopeService;
    private final RecordShareService recordShareService;
    private final RecordShareRowSurfaceResolver rowSurfaceResolver;

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
        return evaluateCondition(dataScopeService.resolveScope(memberId, resource, action),
                memberId, resource, action, record, false);
    }

    public DataScopeCondition getHistoricalCondition(Long memberId, String resource, String action) {
        return dataScopeService.resolveHistoricalScope(memberId, resource, action);
    }

    public EvaluationStep evaluateHistorical(Long memberId, String resource, String action, Object record) {
        return evaluateCondition(getHistoricalCondition(memberId, resource, action),
                memberId, resource, action, record, true);
    }

    /**
     * @param strict historical/audit evaluation: the declared shared-root surface is intentionally
     *               NOT applied — it reflects current grants and must not alter past verdicts.
     */
    @SuppressWarnings("unchecked")
    private EvaluationStep evaluateCondition(DataScopeCondition condition, Long memberId,
                                             String resource, String action,
                                             Object record, boolean strict) {
        if (condition == null) {
            return new EvaluationStep(NAME, EvaluationVerdict.DENY, "Scope unavailable");
        }
        String scopeType = condition.scopeType();

        if ("not_configured".equals(scopeType)) {
            return new EvaluationStep(NAME, EvaluationVerdict.NOT_APPLICABLE,
                    "Scope: not_configured — data scope not enabled for this resource");
        }

        if ("all".equals(scopeType)) {
            return new EvaluationStep(NAME, EvaluationVerdict.NOT_APPLICABLE, "Scope: all — no restriction");
        }

        if ("none".equals(scopeType)) {
            EvaluationStep denied = new EvaluationStep(NAME, EvaluationVerdict.DENY,
                    "Scope: none — access denied");
            if (!(record instanceof Map)) {
                return denied;
            }
            DataScopeCondition withSurface = strict ? condition
                    : enrichWithSharedRootSurface(condition, memberId, resource, action);
            return allowWithinSharedRootSurfaceOr(withSurface, (Map<String, Object>) record, denied);
        }

        // For record-level checks, we need the record as a Map
        if (!(record instanceof Map)) {
            // Cannot evaluate non-map records, allow through
            return new EvaluationStep(NAME, EvaluationVerdict.NOT_APPLICABLE,
                    "Record is not a Map, skipping data scope check");
        }

        Map<String, Object> recordMap = (Map<String, Object>) record;

        condition = strict ? condition
                : enrichWithSharedRootSurface(condition, memberId, resource, action);

        if ("self".equals(scopeType)) {
            return allowWithinSharedRootSurfaceOr(condition, recordMap, evaluateSelf(condition, recordMap));
        }

        if ("dept".equals(scopeType) || "dept_and_sub".equals(scopeType)) {
            return allowWithinSharedRootSurfaceOr(condition, recordMap, evaluateDept(condition, recordMap, strict));
        }

        return new EvaluationStep(NAME, EvaluationVerdict.NOT_APPLICABLE,
                "Unknown scope type: " + scopeType);
    }

    /**
     * Union the declared shared-aggregate surface into the condition (no-op without tenant
     * context or without a declaration).
     */
    private DataScopeCondition enrichWithSharedRootSurface(
            DataScopeCondition condition, Long memberId, String resource, String action) {
        if (!MetaContext.exists()) {
            return condition;
        }
        return condition.withSharedRootReferences(rowSurfaceResolver.resolveSharedRootReferences(
                MetaContext.getCurrentTenantId(), memberId, resource, action));
    }

    /**
     * A record whose declared reference field points at a shared aggregate root belongs to the
     * caller's shared row surface — allow it even when the surface scope alone would deny it.
     */
    private EvaluationStep allowWithinSharedRootSurfaceOr(
            DataScopeCondition condition, Map<String, Object> record, EvaluationStep denied) {
        for (SharedRootReference reference : condition.sharedRootReferences()) {
            Object value = record.get(reference.referenceField());
            if (value == null) {
                continue;
            }
            if (reference.rootRecordPids().contains(String.valueOf(value))) {
                return new EvaluationStep(NAME, EvaluationVerdict.ALLOW,
                        "Scope: " + condition.scopeType()
                                + " — record references an aggregate root shared with the caller");
            }
        }
        return denied;
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
        if ("all".equals(condition.scopeType()) || !MetaContext.exists()) {
            return condition;
        }

        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null || memberId == null) {
            return condition;
        }

        return condition
                .withSharedRecords(
                        recordShareService.getSharedRecordIds(tenantId, resource, memberId, action),
                        recordShareService.getSharedRecordPids(
                                tenantId,
                                resource,
                                memberId,
                                MetaContext.getCurrentUserPid(),
                                action))
                .withSharedRootReferences(rowSurfaceResolver.resolveSharedRootReferences(
                        tenantId, memberId, resource, action));
    }

    // ========================================================================
    // Private: record-level evaluation
    // ========================================================================

    private EvaluationStep evaluateSelf(DataScopeCondition condition, Map<String, Object> record) {
        Object recordOwner = record.get(condition.ownerField());
        if (recordOwner == null) {
            return new EvaluationStep(NAME, EvaluationVerdict.DENY,
                    "Record has no " + condition.ownerField() + " field");
        }

        if (isOwnedByCurrentUser(condition.ownerValue(), recordOwner)) {
            return new EvaluationStep(NAME, EvaluationVerdict.ALLOW,
                    "Scope: self — record owned by current user");
        }

        return new EvaluationStep(NAME, EvaluationVerdict.DENY,
                "Scope: self — record not owned by current user");
    }

    /**
     * Compare by the owner value's type: a Long userId against numeric owner columns, a String
     * userPid against varchar/ULID owner columns. Type mismatches (e.g. a ULID record value
     * against a numeric owner id) can never match — deny, don't throw.
     */
    private boolean isOwnedByCurrentUser(Object ownerValue, Object recordOwner) {
        if (ownerValue == null) {
            return false;
        }
        if (ownerValue instanceof Number expected) {
            if (recordOwner instanceof Number actual) {
                return actual.longValue() == expected.longValue();
            }
            // CATCH: a non-numeric record value can never equal a numeric owner id — not-owned, not an error
            try {
                return Long.parseLong(String.valueOf(recordOwner)) == expected.longValue();
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return String.valueOf(recordOwner).equals(String.valueOf(ownerValue));
    }

    private EvaluationStep evaluateDept(DataScopeCondition condition, Map<String, Object> record, boolean strict) {
        if (condition.deptPids() == null || condition.deptPids().isEmpty()) {
            return new EvaluationStep(NAME, EvaluationVerdict.DENY,
                    "Scope: dept — no department PIDs resolved");
        }

        if (condition.deptOwnerField() != null && !condition.deptOwnerField().isBlank()) {
            Object ownerValue = record.get(condition.deptOwnerField());
            if (ownerValue == null) {
                return new EvaluationStep(NAME, EvaluationVerdict.DENY,
                        "Record has no " + condition.deptOwnerField() + " department-owner field");
            }
            boolean inDepartment = "created_by".equals(condition.deptOwnerField()) && ownerValue instanceof Number creator
                    ? dataScopeService.isCreatorInDepartments(creator.longValue(), condition.deptPids())
                    : dataScopeService.isOwnerInDepartments(String.valueOf(ownerValue), condition.deptPids());
            if (inDepartment) {
                return new EvaluationStep(NAME, EvaluationVerdict.ALLOW,
                        "Scope: " + condition.scopeType() + " — owner belongs to accessible department");
            }
            return new EvaluationStep(NAME, EvaluationVerdict.DENY,
                    "Scope: " + condition.scopeType() + " — owner outside accessible department");
        }

        Object deptValue = record.get(condition.deptField());
        if (deptValue == null) {
            if (strict) {
                return new EvaluationStep(NAME, EvaluationVerdict.DENY, "Historical department field unavailable");
            }
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
