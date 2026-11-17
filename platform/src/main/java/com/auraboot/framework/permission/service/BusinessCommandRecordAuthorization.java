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
import java.util.Set;

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
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DomainCommandAuthorization domains;

    private static final ThreadLocal<ReadProjection> readProjection = new ThreadLocal<>();
    private static final class ReadProjection {
        final Long tenant = MetaContext.getCurrentTenantId(), user = MetaContext.getCurrentUserId(), member = MetaContext.getCurrentMemberId();
        final String userPid = MetaContext.getCurrentUserPid();
        final java.util.Set<Object> connections = connections();
        final Map<Object,Object> configuration = new HashMap<>();
        static java.util.Set<Object> connections() {
            return new java.util.HashSet<>(TransactionSynchronizationManager.getResourceMap().values().stream()
                    .filter(org.springframework.jdbc.datasource.ConnectionHolder.class::isInstance).toList());
        }
        void verify() {
            if (tenant == null || user == null || member == null || connections.isEmpty()
                    || !Objects.equals(userPid,MetaContext.getCurrentUserPid())
                    || !Objects.equals(tenant,MetaContext.getCurrentTenantId())
                    || !Objects.equals(user,MetaContext.getCurrentUserId()) || !Objects.equals(member,MetaContext.getCurrentMemberId())
                    || !TransactionSynchronizationManager.isActualTransactionActive()
                    || !List.of(java.sql.Connection.TRANSACTION_REPEATABLE_READ,java.sql.Connection.TRANSACTION_SERIALIZABLE)
                        .contains(Objects.requireNonNullElse(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),-1))
                    || !connections.equals(connections())) throw new org.springframework.security.access.AccessDeniedException("Read projection snapshot changed");
        }
    }

    /** Host projection/document snapshot: reuse configuration, never record/condition/field decisions. */
    public <T> T withReadProjectionSnapshot(java.util.function.Supplier<T> projection) {
        var previous=readProjection.get();
        if(previous!=null) {
            previous.verify();
            T result=projection.get();previous.verify();return result;
        }
        var current=new ReadProjection();current.verify();readProjection.set(current);
        try { return snapshots.withFreshReads(() -> { T result=projection.get();current.verify();return result; }); }
        finally { readProjection.remove(); }
    }

    /** Authorization authoring cannot invalidate configuration during a host projection. */
    public static boolean isReadProjectionSnapshotActive() { return readProjection.get()!=null; }

    @SuppressWarnings("unchecked")
    private <T> T readConfiguration(Object key, java.util.function.Supplier<T> loader) {
        var current=readProjection.get();if(current==null)return loader.get();current.verify();
        if(current.configuration.containsKey(key))return (T)current.configuration.get(key);
        T result=loader.get();current.verify();current.configuration.put(key,result);return result;
    }

    private List<RolePermissionMapper.BusinessGrantRow> grantRows(Long member,String code,boolean domain) {
        return readConfiguration(List.of("grants",member,code,domain),() -> domain && domains!=null ? domains.grants(code)
                : bindings.findBusinessGrants(MetaContext.getCurrentTenantId(),MetaContext.getCurrentUserId(),member,code));
    }
    private List<PermissionPolicyService.ConditionGuard> conditionGuards(Long member,String code) {
        return readConfiguration(List.of("guards",member,code),() -> policies.getConditionGuards(member,code));
    }
    private PermissionPolicyService.ConditionGuard conditionGuard(Long member,RolePermissionMapper.BusinessGrantRow binding) {
        return readConfiguration(List.of("binding-guard",member,binding.getId(),binding.getPermissionCode()),() -> {
            if(Boolean.TRUE.equals(binding.getConditionLoaded())) {
                var definition=snapshots.resolvePermissionDefinition(MetaContext.getCurrentTenantId(),binding.getPermissionCode());
                // An older/global duplicate definition cannot lend a newer definition's guard.
                if(definition==null || binding.getPermissionId()==null || !Objects.equals(definition.getId(),binding.getPermissionId()))return null;
                return policies.loadedConditionGuard(definition,binding.getId(),binding.getConditionAstJson(),binding.getConditionsJson());
            }
            var guards=conditionGuards(member,binding.getPermissionCode());
            return guards==null?null:guards.stream().filter(guard -> binding.getId().equals(guard.grantId())).findFirst().orElse(null);
        });
    }
    private com.auraboot.framework.permission.engine.model.DataScopeCondition objectScope(Long member,Long role,String resource,String action,String model) {
        return readConfiguration(List.of("object-scope",member,role,resource,action,model),
                () -> scopes.resolveObjectBoundScopeForRole(member,role,resource,action,model));
    }

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

    /** Apply the server-declared target action to domain data policies, retaining legacy consumers. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BusinessGrantDecision resolveForAction(Long member, List<String> codes, String model,
                                                  Map<String,Object> record, String action) {
        boolean domain = codes != null && codes.stream().anyMatch(code -> DomainPermissionPolicy.owns(
                snapshots.resolvePermissionDefinition(MetaContext.getCurrentTenantId(), code), model));
        return resolveInternal(member, codes, model, record, domain ? action : null);
    }

    /** Resolve a complete generated model-read grant for an artifact reader, without an execution permit. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BusinessGrantDecision resolveModelRead(Long memberId, String model, Map<String,Object> record) {
        return resolveInternal(memberId, snapshots.resourcePermissionCodes(MetaContext.getCurrentTenantId(), model, "read"), model, record, "read");
    }

    /** Null means this declared duty is not a write owner; an owning duty's refusal must not fall back. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BusinessGrantDecision resolveDeclaredRootWriteOrNull(Long member, String code, String model,
                                                               Map<String,Object> record, String action) {
        if (code == null || code.isBlank()) throw new org.springframework.security.access.AccessDeniedException("Current command authority is unavailable");
        return snapshots.withFreshReads(() -> {
            var permission = snapshots.resolvePermissionDefinition(MetaContext.getCurrentTenantId(), code);
            if (permission == null) throw new org.springframework.security.access.AccessDeniedException("Current command permission is unavailable");
            if (DomainPermissionPolicy.permits(permission, model, action))
                return resolveInternal(member, List.of(code), model, record, action);
            if (!("operation".equalsIgnoreCase(permission.getResourceType()) || "data".equalsIgnoreCase(permission.getResourceType()))
                    || !Objects.equals(model, permission.getResourceCode())
                    || !(action.equals(permission.getAction()) || "manage".equals(permission.getAction()))) return null;
            return resolveInternal(member, List.of(code), model, record, action);
        });
    }

    /** Native model reads retain their existing permission aliases and record-share exceptions. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BusinessGrantDecision resolveOrdinaryRead(Long memberId, String model, Map<String,Object> record) {
        return resolveInternal(memberId,
                snapshots.resourcePermissionCodes(MetaContext.getCurrentTenantId(), model, "read"),
                model, record, "read", true);
    }

    /** Apply independent-object policies using only grant alternatives that admitted the root.
     * The current domain invocation already owns function authority; no child CRUD grant is added.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BusinessGrantDecision resolveIndependentRead(BusinessGrantDecision boundary, String model,
                                                       Map<String,Object> record) {
        if (boundary == null || !boundary.result().granted() || boundary.grants().isEmpty()
                || !Objects.equals(boundary.tenantId(), MetaContext.getCurrentTenantId())
                || !Objects.equals(boundary.userId(), MetaContext.getCurrentUserId())
                || !Objects.equals(boundary.memberId(), MetaContext.getCurrentMemberId()))
            return decision(denied("Admitted business grant is unavailable", List.of()),
                    MetaContext.getCurrentMemberId(), model, record, List.of());
        var resolved = resolveInternal(boundary.memberId(), boundary.grants().stream()
                .map(BusinessGrantDecision.Grant::permissionCode).distinct().toList(), model, record, "read");
        var matched = resolved.grants().stream().filter(candidate -> boundary.grants().stream().anyMatch(root ->
                Objects.equals(root.grantId(), candidate.grantId()) && Objects.equals(root.roleId(), candidate.roleId())
                        && Objects.equals(root.permissionCode(), candidate.permissionCode()))).toList();
        return decision(matched.isEmpty() ? denied("Independent record is outside the admitted grant", List.of())
                : resolved.result(), boundary.memberId(), model, record, matched);
    }

    /** Ordinary native updates use generated model grants, retaining their role-local scope and shares. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BusinessGrantDecision resolveOrdinaryUpdate(Long memberId, String model, Map<String,Object> record) {
        return resolveInternal(memberId,
                snapshots.resourcePermissionCodes(MetaContext.getCurrentTenantId(), model, "update"),
                model, record, "update", true);
    }

    /** Ordinary creation is decided on the actual inserted row, never a caller-supplied owner. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BusinessGrantDecision resolveOrdinaryCreate(Long memberId, String model, Map<String,Object> record) {
        if (!MetaContext.exists() || record == null || MetaContext.getCurrentUserId() == null
                || !Objects.equals(MetaContext.getCurrentUserId(), record.get("created_by")))
            return decision(denied("Native creation ownership is unavailable", List.of()), memberId, model, record, List.of());
        var codes = new java.util.LinkedHashSet<>(snapshots.resourcePermissionCodes(MetaContext.getCurrentTenantId(), model, "create"));
        String documentAuthority = com.auraboot.framework.meta.security.DocumentArtifactCommandScope.authorityFor(model, record, "create");
        if (documentAuthority != null) codes.add(documentAuthority);
        return resolveInternal(memberId, List.copyOf(codes), model, record, "create", true);
    }

    private BusinessGrantDecision resolveInternal(Long memberId, List<String> declaredPermissions,
                                                  String recordModel, Map<String, Object> record, String nativeAction) {
        return resolveInternal(memberId, declaredPermissions, recordModel, record, nativeAction, false);
    }

    private BusinessGrantDecision resolveInternal(Long memberId, List<String> declaredPermissions,
                                                  String recordModel, Map<String, Object> record, String nativeAction,
                                                  boolean ordinaryModelAction) {
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
        var projection=readProjection.get();
        if(projection!=null) { projection.verify();return evaluateSnapshot(memberId,declaredPermissions,recordModel,record,nativeAction,ordinaryModelAction); }
        return snapshots.withFreshReads(() -> evaluateSnapshot(memberId, declaredPermissions, recordModel, record, nativeAction, ordinaryModelAction));
    }

    private BusinessGrantDecision evaluateSnapshot(Long member, List<String> codes, String model, Map<String, Object> record,
                                                  String nativeAction, boolean ordinaryModelAction) {
        var tenant = MetaContext.getCurrentTenantId();
        List<EvaluationStep> trace = new ArrayList<>();
        List<BusinessGrantDecision.Grant> matched = new ArrayList<>();
        java.util.function.Supplier<Boolean> sharedRecord = new java.util.function.Supplier<>() {
            Boolean shared;
            public Boolean get() {
                if (nativeAction == null || "create".equals(nativeAction)) return false;
                if (!ordinaryModelAction && codes.stream().noneMatch(code ->
                        !DomainPermissionPolicy.models(snapshots.resolvePermissionDefinition(tenant,code)).isEmpty())) return false;
                if (shared == null) {
                    var share=recordShares.evaluate(member,model,nativeAction,record);
                    if(share!=null)trace.add(share);
                    shared=share!=null && share.verdict()==EvaluationVerdict.ALLOW;
                }
                return shared;
            }
        };
        for (String code : new java.util.LinkedHashSet<>(codes)) {
            var permission = snapshots.resolvePermissionDefinition(tenant, code);
            boolean modelRead = "read".equals(nativeAction);
            boolean documentEffect = com.auraboot.framework.meta.security.DocumentArtifactCommandScope.permits(permission, model, record, nativeAction);
            boolean domain = DomainPermissionPolicy.owns(permission, model) || documentEffect;
            if (!DomainPermissionPolicy.models(permission).isEmpty() && !DomainPermissionPolicy.permits(permission, model, nativeAction)
                    && !DomainPermissionPolicy.isCurrentCommandMutation(permission, model, nativeAction) && !documentEffect) {
                trace.add(step(EvaluationVerdict.DENY, "Domain level does not own this model/action", code, null, null));
                continue;
            }
            if (!domain && nativeAction != null && ((!modelRead && !ordinaryModelAction && code.startsWith("model."))
                    || modelRead && !ordinaryModelAction && !code.equals("model." + model + ".read") || permission == null
                    || !model.equals(permission.getResourceCode())
                    || !(nativeAction.equals(permission.getAction()) || !modelRead && "manage".equals(permission.getAction())))) {
                trace.add(step(EvaluationVerdict.DENY, "Native business permission does not own this root/action", code, null, null));
                continue;
            }
            if (permission == null || permission.getResourceCode() == null || permission.getAction() == null
                    || !("operation".equalsIgnoreCase(permission.getResourceType())
                         || "data".equalsIgnoreCase(permission.getResourceType())
                         || (modelRead || ordinaryModelAction) && "model".equalsIgnoreCase(permission.getResourceType()))) {
                trace.add(step(EvaluationVerdict.DENY, "Declared business permission is unavailable", code, null, null));
                continue;
            }
            var grants = grantRows(member,code,domain);
            if (grants == null || grants.stream().anyMatch(row -> !"grant".equals(row.getGrantType()))) {
                trace.add(step(EvaluationVerdict.DENY, "Business permission has an effective deny", code, null, null));
                continue;
            }
            for (var grant : grants) {
                if (grant.getId() == null || grant.getRoleId() == null || grant.getPermissionCode() == null
                        || !(domain || code.equals(grant.getPermissionCode()) || "*".equals(grant.getPermissionCode()))) continue;
                Object scopePolicy = permission.getExtension() instanceof Map<?, ?> extension
                        ? extension.get("businessScopePolicy") : null;
                if (scopePolicy != null && !"record".equals(scopePolicy)) {
                    trace.add(step(EvaluationVerdict.DENY, "Unknown business object scope policy", code,
                            grant.getRoleId(), grant.getId()));
                    continue;
                }
                boolean objectBoundScope = domain || !ordinaryModelAction && "record".equals(scopePolicy);
                String scopeAction = domain ? Objects.requireNonNullElse(nativeAction,
                        "read".equals(permission.getAction()) ? "read" : "update") : permission.getAction();
                var scope = readConfiguration(List.of("scope",member,grant.getRoleId(),permission.getResourceCode(),permission.getAction(),model,scopeAction,domain,objectBoundScope,ordinaryModelAction), () -> domain
                        ? objectScope(member, grant.getRoleId(), model, scopeAction, model)
                        : objectBoundScope
                        ? objectScope(member, grant.getRoleId(), permission.getResourceCode(),
                            permission.getAction(), model)
                        : ordinaryModelAction
                        ? ("read".equals(nativeAction)
                            ? scopes.resolveOrdinaryReadScopeForRole(member,grant.getRoleId(),model)
                            : "create".equals(nativeAction)
                                ? scopes.resolveOrdinaryCreateScopeForRole(member,grant.getRoleId(),model)
                                : scopes.resolveOrdinaryUpdateScopeForRole(member,grant.getRoleId(),model))
                        : scopes.resolveScopeForRole(member, grant.getRoleId(), permission.getResourceCode(),
                            permission.getAction(), model));
                var scopeStep = (ordinaryModelAction || objectBoundScope) && scope != null && "not_configured".equals(scope.scopeType())
                        ? step(EvaluationVerdict.NOT_APPLICABLE, "Legacy native-model scope is not configured", code, grant.getRoleId(), grant.getId())
                        : domain || ordinaryModelAction ? scopeEvaluator.evaluateCurrentGrantScope(scope, member, model, Objects.requireNonNullElse(nativeAction, permission.getAction()), record)
                        : scopeEvaluator.evaluateGrantScope(scope, member, model, permission.getAction(), record);
                if (scopeStep == null || scopeStep.verdict() == null) continue;
                trace.add(scopeStep);
                if (scopeStep.verdict() == EvaluationVerdict.DENY && !sharedRecord.get()) continue;
                var policy = policyEvaluator.evaluateGrant(member, grant.getPermissionCode(), model, record, conditionGuard(member,grant));
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
                var fields = BusinessGrantFieldScope.select(json, grant.getFieldScopeJson(), model);
                if (domain && fields.isPresent()) fields = restrictDomainData(permission, grant.getRoleId(), member, model,
                        scopeAction, record, fields.get(), sharedRecord);
                if (fields.isEmpty()) {
                    trace.add(step(EvaluationVerdict.DENY, "No field grant is configured for this business object", code,
                            grant.getRoleId(), grant.getId()));
                    continue;
                }
                trace.add(step(EvaluationVerdict.ALLOW, "One complete business grant permits this target", code,
                        grant.getRoleId(), grant.getId()));
                matched.add(new BusinessGrantDecision.Grant(grant.getId(), grant.getRoleId(), code, fields.get()));
            }
        }
        return decision(matched.isEmpty() ? denied("No complete business grant permits this target", trace)
                : PermissionResult.allow(List.copyOf(trace)), member, model, record, matched);
    }

    /** Retired codes are data-policy references only; they never supply positive function authority. */
    private java.util.Optional<BusinessGrantDecision.Fields> restrictDomainData(
            com.auraboot.framework.permission.entity.Permission permission, Long role, Long member, String model,
            String action, Map<String,Object> record, BusinessGrantDecision.Fields fields, java.util.function.Supplier<Boolean> sharedRecord) {
        List<?> declared = permission.getExtension() instanceof Map<?,?> extension
                && extension.get("dataPolicyReferences") instanceof Map<?,?> references
                && references.get(model) instanceof List<?> values ? values : List.of();
        Set<String> codes = new java.util.LinkedHashSet<>();
        for (Object raw : declared) {
            if (!(raw instanceof String code)) return java.util.Optional.empty();
            codes.add(code);
        }
        // Generated model bindings can carry existing field/row restrictions even when the plugin
        // never listed them in permissions.json. They are constraints, never fallback authority.
        codes.addAll(com.auraboot.framework.permission.engine.evaluator.PermissionCodeCandidates.forResourceAction(model, action));
        for (String code : codes) {
            var definition = snapshots.resolvePermissionDefinition(MetaContext.getCurrentTenantId(), code);
            if (definition == null && !declared.contains(code)) continue;
            if (definition == null || !model.equals(definition.getResourceCode())) return java.util.Optional.empty();
            String policyAction = definition.getAction();
            if (!(action.equals(policyAction) || "manage".equals(policyAction)
                    && (!"read".equals(action) || !"read".equals(permission.getAction())))) continue;
            var scope = objectScope(member, role, model, policyAction, model);
            if (scope == null || !"not_configured".equals(scope.scopeType())
                    && scopeEvaluator.evaluateCurrentGrantScope(scope, member, model, policyAction, record).verdict() == EvaluationVerdict.DENY
                    && !sharedRecord.get())
                return java.util.Optional.empty();
            var rows = grantRows(member,code,true);
            if (rows == null) return java.util.Optional.empty();
            for (var row : rows) {
                if (!role.equals(row.getRoleId())) continue;
                if (!"grant".equals(row.getGrantType())) return java.util.Optional.empty();
                String sourceCode = row.getPermissionCode();
                if (!(code.equals(sourceCode) || "*".equals(sourceCode))) return java.util.Optional.empty();
                var guard = conditionGuard(member,row);
                if (policyEvaluator.evaluateGrant(member, sourceCode, model, record, guard).verdict() == EvaluationVerdict.DENY)
                    return java.util.Optional.empty();
                if (row.getDataScopeAstJson() != null && !row.getDataScopeAstJson().isBlank() && !"null".equals(row.getDataScopeAstJson().strip())
                        && policyEvaluator.evaluateGrant(member, sourceCode, model, record,
                            new PermissionPolicyService.ConditionGuard(row.getId(), row.getDataScopeAstJson())).verdict() == EvaluationVerdict.DENY)
                    return java.util.Optional.empty();
                var stored = BusinessGrantFieldScope.select(json, row.getFieldScopeJson(), model);
                if (stored.isEmpty()) return java.util.Optional.empty();
                fields = intersect(fields, stored.get());
            }
        }
        return java.util.Optional.of(fields);
    }

    private static BusinessGrantDecision.Fields intersect(BusinessGrantDecision.Fields left, BusinessGrantDecision.Fields right) {
        if (left.unrestricted()) return right;
        if (right.unrestricted()) return left;
        var read = new java.util.HashSet<>(left.readable()); read.retainAll(right.readable());
        var write = new java.util.HashSet<>(left.writable()); write.retainAll(right.writable());
        return new BusinessGrantDecision.Fields(false, read, write);
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
