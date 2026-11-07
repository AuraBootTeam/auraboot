package com.auraboot.framework.permission.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.engine.evaluator.DataScopeEvaluator;
import com.auraboot.framework.permission.engine.evaluator.PolicyEvaluator;
import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import com.auraboot.framework.permission.engine.model.PermissionResult;
import com.auraboot.framework.permission.engine.model.BusinessGrantDecision;
import com.auraboot.framework.permission.service.impl.PermissionSnapshotCache;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Complete business grants are alternatives: function AND scope AND guards from ONE binding.
 * The record must be host-loaded from the target transaction, never supplied by the caller.
 * This returns a boundary decision; it never grants model CRUD or a process-wide data bypass.
 */
@Service
@RequiredArgsConstructor
public class BusinessCommandRecordAuthorization {
    private final PermissionSnapshotCache snapshots;
    private final RolePermissionMapper bindings;
    private final PermissionPolicyService policies;
    private final DataScopeService scopes;
    private final DataScopeEvaluator scopeEvaluator;
    private final PolicyEvaluator policyEvaluator;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final com.auraboot.framework.permission.engine.evaluator.RecordShareEvaluator recordShares;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PermissionResult evaluate(Long memberId, List<String> declaredPermissions,
                                     String recordModel, Map<String, Object> record) {
        return resolve(memberId, declaredPermissions, recordModel, record).result();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BusinessGrantDecision resolve(Long memberId, List<String> declaredPermissions,
                                         String recordModel, Map<String, Object> record) {
        return resolveInternal(memberId, declaredPermissions, recordModel, record, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BusinessGrantDecision resolveNative(Long memberId, List<String> declaredPermissions,
                                               String recordModel, Map<String, Object> record, String action) {
        if (action == null || !List.of("create", "update", "delete").contains(action))
            return decision(denied("Unsupported native business action", List.of()), memberId, recordModel, record, List.of());
        // For create, the host supplies the normalized prospective row, including server-owned
        // tenant and creator. This decision alone is not an insert permit or a command contract.
        if ("create".equals(action) && (!MetaContext.exists() || record == null
                || MetaContext.getCurrentUserId() == null
                || !Objects.equals(MetaContext.getCurrentUserId(), record.get("created_by")))) {
            return decision(denied("Native creation ownership is unavailable", List.of()),
                    memberId, recordModel, record, List.of());
        }
        return resolveInternal(memberId, declaredPermissions, recordModel, record, action);
    }

    /** Resolve a complete generated model-read grant for an artifact reader, without an execution permit. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BusinessGrantDecision resolveModelRead(Long memberId, String model, Map<String,Object> record) {
        return resolveInternal(memberId, List.of("model." + model + ".read"), model, record, "read");
    }

    /** Native model reads retain their existing permission aliases and record-share exceptions. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BusinessGrantDecision resolveOrdinaryRead(Long memberId, String model, Map<String,Object> record) {
        return resolveInternal(memberId,
                com.auraboot.framework.permission.engine.evaluator.PermissionCodeCandidates.forResourceAction(model, "read"),
                model, record, "read", true);
    }

    private BusinessGrantDecision resolveInternal(Long memberId, List<String> declaredPermissions,
                                                  String recordModel, Map<String, Object> record, String nativeAction) {
        return resolveInternal(memberId, declaredPermissions, recordModel, record, nativeAction, false);
    }

    private BusinessGrantDecision resolveInternal(Long memberId, List<String> declaredPermissions,
                                                  String recordModel, Map<String, Object> record, String nativeAction,
                                                  boolean ordinaryRead) {
        if (com.auraboot.framework.application.security.MachineDataAccessContext.isActive()
                || com.auraboot.framework.openplatform.service.InstalledIntegrationExecution.active()
                || !MetaContext.exists() || memberId == null
                || !Objects.equals(memberId, MetaContext.getCurrentMemberId())
                || MetaContext.getCurrentTenantId() == null || MetaContext.getCurrentUserId() == null
                || MetaContext.getCurrentUserPid() == null || MetaContext.getCurrentUserPid().isBlank()
                || recordModel == null || recordModel.isBlank() || record == null
                || !Objects.equals(String.valueOf(MetaContext.getCurrentTenantId()), String.valueOf(record.get("tenant_id")))
                || !(record.get("pid") instanceof String pid) || pid.isBlank()
                || Boolean.TRUE.equals(record.get("deleted_flag"))
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.getCurrentTransactionIsolationLevel() == null
                || !List.of(Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_SERIALIZABLE)
                    .contains(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
            return decision(denied("Business target authorization context is unavailable", List.of()), memberId, recordModel, record, List.of());
        }
        if (declaredPermissions == null || declaredPermissions.isEmpty() || declaredPermissions.size() > 128
                || declaredPermissions.stream().anyMatch(code -> code == null || code.isBlank()
                    || !code.equals(code.strip()) || code.length() > 255 || "*".equals(code)
                    || "meta.command.execute".equals(code))) {
            return decision(denied("A declared business permission is required", List.of()), memberId, recordModel, record, List.of());
        }
        // The complete decision uses the same database snapshot, including membership and policy
        // rows. Shared caches must not splice a stale grant into a freshly changed scope.
        return snapshots.withFreshReads(() -> evaluateSnapshot(memberId, declaredPermissions, recordModel, record, nativeAction, ordinaryRead));
    }

    private BusinessGrantDecision evaluateSnapshot(Long member, List<String> codes, String model, Map<String, Object> record,
                                                  String nativeAction, boolean ordinaryRead) {
        var tenant = MetaContext.getCurrentTenantId();
        List<EvaluationStep> trace = new ArrayList<>();
        List<BusinessGrantDecision.Grant> matched = new ArrayList<>();
        Map<String, Map<Long, PermissionPolicyService.ConditionGuard>> guardsByCode = new HashMap<>();
        boolean sharedRead = false;
        if (ordinaryRead) {
            var share = recordShares.evaluate(member, model, "read", record);
            if (share == null || share.verdict() == null)
                return decision(denied("Record share authorization is unavailable", trace), member, model, record, List.of());
            trace.add(share);
            sharedRead = share.verdict() == EvaluationVerdict.ALLOW;
        }
        for (String code : new java.util.LinkedHashSet<>(codes)) {
            var permission = snapshots.resolvePermissionDefinition(tenant, code);
            boolean modelRead = "read".equals(nativeAction);
            if (nativeAction != null && ((!modelRead && code.startsWith("model."))
                    || modelRead && !ordinaryRead && !code.equals("model." + model + ".read") || permission == null
                    || !model.equals(permission.getResourceCode())
                    || !(nativeAction.equals(permission.getAction()) || !modelRead && "manage".equals(permission.getAction())))) {
                trace.add(step(EvaluationVerdict.DENY, "Native business permission does not own this root/action", code, null, null));
                continue;
            }
            if (permission == null || permission.getResourceCode() == null || permission.getAction() == null
                    || !("operation".equalsIgnoreCase(permission.getResourceType())
                         || "data".equalsIgnoreCase(permission.getResourceType())
                         || modelRead && "model".equalsIgnoreCase(permission.getResourceType()))) {
                trace.add(step(EvaluationVerdict.DENY, "Declared business permission is unavailable", code, null, null));
                continue;
            }
            var grants = bindings.findBusinessGrants(tenant, MetaContext.getCurrentUserId(), member, code);
            if (grants == null || grants.stream().anyMatch(row -> !"grant".equals(row.getGrantType()))) {
                trace.add(step(EvaluationVerdict.DENY, "Business permission has an effective deny", code, null, null));
                continue;
            }
            for (var grant : grants) {
                if (grant.getId() == null || grant.getRoleId() == null || grant.getPermissionCode() == null
                        || !(code.equals(grant.getPermissionCode()) || "*".equals(grant.getPermissionCode()))) continue;
                var scope = scopes.resolveScopeForRole(member, grant.getRoleId(), permission.getResourceCode(),
                        permission.getAction(), model);
                var scopeStep = ordinaryRead && scope != null && "not_configured".equals(scope.scopeType())
                        ? step(EvaluationVerdict.NOT_APPLICABLE, "Legacy native-read scope is not configured", code, grant.getRoleId(), grant.getId())
                        : scopeEvaluator.evaluateGrantScope(scope, member, model, permission.getAction(), record);
                if (scopeStep == null || scopeStep.verdict() == null) continue;
                trace.add(scopeStep);
                if (scopeStep.verdict() == EvaluationVerdict.DENY && !sharedRead) continue;
                var guards = guardsByCode.computeIfAbsent(grant.getPermissionCode(), actual -> {
                    Map<Long, PermissionPolicyService.ConditionGuard> result = new LinkedHashMap<>();
                    for (var guard : policies.getConditionGuards(member, actual)) result.put(guard.grantId(), guard);
                    return result;
                });
                var policy = policyEvaluator.evaluateGrant(member, grant.getPermissionCode(), model, record, guards.get(grant.getId()));
                if (policy == null || policy.verdict() == null) continue;
                trace.add(policy);
                if (policy.verdict() == EvaluationVerdict.DENY) continue;
                String rowAst = grant.getDataScopeAstJson();
                if (rowAst != null && !rowAst.isBlank() && !"null".equals(rowAst.strip())) {
                    var rowGuard = policyEvaluator.evaluateGrant(member, code, model, record,
                            new PermissionPolicyService.ConditionGuard(grant.getId(), rowAst));
                    if (rowGuard == null || rowGuard.verdict() == null) continue;
                    trace.add(rowGuard);
                    if (rowGuard.verdict() == EvaluationVerdict.DENY) continue;
                }
                trace.add(step(EvaluationVerdict.ALLOW, "One complete business grant permits this target", code,
                        grant.getRoleId(), grant.getId()));
                matched.add(new BusinessGrantDecision.Grant(grant.getId(), grant.getRoleId(), code,
                        BusinessGrantFieldScope.parse(json, grant.getFieldScopeJson(), model)));
            }
        }
        return decision(matched.isEmpty() ? denied("No complete business grant permits this target", trace)
                : PermissionResult.allow(List.copyOf(trace)), member, model, record, matched);
    }

    private static BusinessGrantDecision decision(PermissionResult result, Long member, String model,
                                                  Map<String, Object> record, List<BusinessGrantDecision.Grant> matched) {
        boolean initialized = MetaContext.exists();
        return new BusinessGrantDecision(result, initialized ? MetaContext.getCurrentTenantId() : null,
                initialized ? MetaContext.getCurrentUserId() : null, member, model,
                record != null && record.get("pid") instanceof String pid ? pid : null, matched);
    }

    private static EvaluationStep step(EvaluationVerdict verdict, String reason, String code, Long role, Long grant) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("permissionCode", code);
        if (role != null) details.put("roleId", role);
        if (grant != null) details.put("grantId", grant);
        return new EvaluationStep("BusinessGrant", verdict, reason, details);
    }

    private static PermissionResult denied(String reason, List<EvaluationStep> trace) {
        return PermissionResult.deny(reason, trace);
    }
}
