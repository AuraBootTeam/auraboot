package com.auraboot.framework.permission.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.decision.ast.*;
import com.auraboot.framework.permission.engine.evaluator.*;
import com.auraboot.framework.permission.engine.model.DataScopeCondition;
import com.auraboot.framework.permission.engine.vocab.PermissionFieldVocabulary;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.service.impl.PermissionSnapshotCache;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real scope and condition evaluators, with only the database/membership boundaries mocked. */
class BusinessCommandRecordAuthorizationTest {
    private final PermissionSnapshotCache snapshots = mock(PermissionSnapshotCache.class);
    private final RolePermissionMapper bindings = mock(RolePermissionMapper.class);
    private final PermissionPolicyService policies = mock(PermissionPolicyService.class);
    private final DataScopeService scopes = mock(DataScopeService.class);
    private final PermissionFieldVocabulary vocabulary = mock(PermissionFieldVocabulary.class);
    private final ObjectMapper json = new ObjectMapper();
    private final RecordShareEvaluator recordShares = mock(RecordShareEvaluator.class);
    private final BusinessCommandRecordAuthorization authorization = new BusinessCommandRecordAuthorization(
            snapshots, bindings, policies, scopes,
            new DataScopeEvaluator(scopes, mock(RecordShareService.class), mock(RecordShareRowSurfaceResolver.class)),
            new PolicyEvaluator(policies, vocabulary, json, mock(ObjectProvider.class)), json, recordShares);
    private static final String CODE = "inventory.receipt.confirm";
    private static final String MODEL = "receipt";

    @BeforeEach void context() {
        MetaContext.setContext(100L, 1L, "actor-pid", "actor"); MetaContext.setMemberId(5L);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ);
        when(snapshots.resourcePermissionCodes(any(), any(), any())).thenAnswer(call ->
                com.auraboot.framework.permission.engine.evaluator.PermissionCodeCandidates.forResourceAction(call.getArgument(1), call.getArgument(2)));
        when(snapshots.withFreshReads(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
        when(snapshots.resolvePermissionDefinition(100L, CODE)).thenReturn(permission(CODE));
        when(vocabulary.buildBusinessContext(eq(5L), eq(MODEL), any())).thenAnswer(call -> DecisionContext.builder()
                .record(call.getArgument(2)).build());
    }

    @AfterEach void clear() {
        MetaContext.clear(); TransactionSynchronizationManager.clear();
    }

    @Test void retiredGeneratedModelBindingsRemainFieldConstraintsWithoutSupplyingOperationAuthority() {
        var definition = permission(CODE); definition.setResourceCode("domain"); definition.setAction("write");
        definition.setExtension(Map.of("domainModels", Map.of(MODEL, List.of("read", "update"))));
        when(snapshots.resolvePermissionDefinition(100L, CODE)).thenReturn(definition);
        String oldCode = "model." + MODEL + ".update";
        var oldDefinition = permission(oldCode); oldDefinition.setResourceType("model");
        oldDefinition.setResourceCode(MODEL); oldDefinition.setAction("update");
        when(snapshots.resolvePermissionDefinition(100L, oldCode)).thenReturn(oldDefinition);
        var old = grant(12,101,oldCode);old.setFieldScopeJson("{\"version\":2,\"models\":{\"receipt\":{\"readFields\":[\"city\"],\"writeFields\":[]}}}");
        when(bindings.findBusinessGrants(100L,1L,5L,oldCode)).thenReturn(List.of(old));
        grants(grant(11,101,CODE));guards(CODE,new PermissionPolicyService.ConditionGuard(11L,null));
        guards(oldCode,new PermissionPolicyService.ConditionGuard(12L,null));
        when(scopes.resolveObjectBoundScopeForRole(5L,101L,MODEL,"update",MODEL)).thenReturn(DataScopeCondition.all());
        var result=authorization.resolveNative(5L,List.of(CODE),MODEL,record(1,"Shanghai"),"update");
        assertThat(result.result().granted()).isTrue();assertThat(result.canRead("city")).isTrue();assertThat(result.canWrite("city")).isFalse();
        old.setPermissionCode("*");
        guards(oldCode, new PermissionPolicyService.ConditionGuard(13L, null));
        guards("*", new PermissionPolicyService.ConditionGuard(12L, null));
        var wildcard = authorization.resolveNative(5L,List.of(CODE),MODEL,record(1,"Shanghai"),"update");
        assertThat(wildcard.result().granted()).isTrue();
        assertThat(wildcard.canWrite("city")).isFalse();
        grants();
        assertThat(authorization.resolveNative(5L,List.of(CODE),MODEL,record(1,"Shanghai"),"update").result().granted()).isFalse();
    }

    @Test void domainUsesOneFunctionBindingAndOriginalObjectScopeForInternalRows() {
        var definition = permission(CODE);
        definition.setResourceCode("domain"); definition.setAction("write");
        definition.setExtension(Map.of("domainModels", Map.of(MODEL, List.of("read", "create", "update"))));
        when(snapshots.resolvePermissionDefinition(100L, CODE)).thenReturn(definition);
        var domains = new DomainCommandAuthorization(snapshots, bindings);
        org.springframework.test.util.ReflectionTestUtils.setField(authorization, "domains", domains);
        grants(grant(11, 101, CODE)); guards(CODE, new PermissionPolicyService.ConditionGuard(11L, null));
        when(scopes.resolveObjectBoundScopeForRole(5L, 101L, MODEL, "update", MODEL))
                .thenReturn(new DataScopeCondition("self", "created_by", 1L, null, List.of(), List.of()));
        domains.withinInvocation(() -> {
            assertThat(domains.authorize(CODE)).isTrue();
            assertThat(authorization.resolveNative(5L, List.of(CODE), MODEL, record(1, "Shanghai"), "update").result().granted()).isTrue();
            assertThat(authorization.resolveNative(5L, List.of(CODE), MODEL, record(999, "Shanghai"), "update").result().granted()).isFalse();
            assertThat(authorization.resolveNative(5L, List.of(CODE), MODEL, record(1, "Shanghai"), "delete").result().granted()).isFalse();
            return null;
        });
        verify(bindings, times(1)).findBusinessGrants(100L, 1L, 5L, CODE);
        domains.withinInvocation(() -> { assertThat(domains.authorize(CODE)).isTrue(); return null; });
        verify(bindings, times(2)).findBusinessGrants(100L, 1L, 5L, CODE);
    }

    @Test void internalDerivedWritesReuseTheDutyButStillEnforceObjectAndFieldConstraints() {
        var definition = permission(CODE); definition.setResourceCode("domain"); definition.setAction("write");
        definition.setExtension(Map.of("domainModels", Map.of(MODEL, List.of("read"))));
        when(snapshots.resolvePermissionDefinition(100L, CODE)).thenReturn(definition);
        var row = grant(11, 101, CODE);
        row.setFieldScopeJson("{\"version\":2,\"models\":{\"receipt\":{\"readFields\":[\"city\"],\"writeFields\":[]}}}");
        grants(row); guards(CODE, new PermissionPolicyService.ConditionGuard(11L, null));
        when(scopes.resolveObjectBoundScopeForRole(5L, 101L, MODEL, "update", MODEL))
                .thenReturn(new DataScopeCondition("self", "created_by", 1L, null, List.of(), List.of()));
        assertThat(authorization.resolveNative(5L, List.of(CODE), MODEL, record(1,"Shanghai"), "update").result().granted()).isFalse();
        MetaContext.runWithCommandPermitPlan("TARGET", null, "root", "root-pid", () ->
            MetaContext.runWithAuthorizedCommandCode("example:convert", () -> MetaContext.runWithCommandAuthority(CODE, () -> {
                var decision = authorization.resolveNative(5L, List.of(CODE), MODEL, record(1,"Shanghai"), "update");
                assertThat(decision.result().granted()).isTrue();
                assertThat(decision.canWrite("city")).isFalse();
                assertThat(authorization.resolveNative(5L, List.of(CODE), MODEL, record(999,"Shanghai"), "update").result().granted()).isFalse();
                when(scopes.resolveObjectBoundScopeForRole(5L,101L,MODEL,"update",MODEL)).thenReturn(DataScopeCondition.none());
                assertThat(authorization.resolveNative(5L, List.of(CODE), MODEL, record(1,"Shanghai"), "update").result().granted()).isFalse();
            })));
    }

    @Test void domainLevelDoesNotRemoveOriginalFieldAndExplicitScopeRestrictions() {
        var definition = permission(CODE); definition.setResourceCode("domain"); definition.setAction("write");
        definition.setExtension(Map.of("domainModels", Map.of(MODEL, List.of("read", "update"))));
        when(snapshots.resolvePermissionDefinition(100L, CODE)).thenReturn(definition);
        var row = grant(11, 101, CODE);
        row.setFieldScopeJson("{\"version\":2,\"models\":{\"receipt\":{\"readFields\":[\"city\"],\"writeFields\":[]}}}");
        grants(row); guards(CODE, new PermissionPolicyService.ConditionGuard(11L, null));
        when(scopes.resolveObjectBoundScopeForRole(5L, 101L, MODEL, "update", MODEL)).thenReturn(DataScopeCondition.all());
        var result = authorization.resolveNative(5L, List.of(CODE), MODEL, record(1, "Shanghai"), "update");
        assertThat(result.result().granted()).isTrue(); assertThat(result.canRead("city")).isTrue(); assertThat(result.canWrite("city")).isFalse();
        when(scopes.resolveObjectBoundScopeForRole(5L, 101L, MODEL, "update", MODEL)).thenReturn(DataScopeCondition.none());
        assertThat(authorization.resolveNative(5L, List.of(CODE), MODEL, record(1, "Shanghai"), "update").result().granted()).isFalse();
    }

    @Test void cannotBorrowAllScopeFromAConditionalRoleAndAnUnconditionalGuardFromAnother() throws Exception {
        grants(grant(11, 101, CODE), grant(22, 202, CODE));
        scope(101, new DataScopeCondition("self", "created_by", 1L, null, List.of(), List.of()));
        scope(202, DataScopeCondition.all());
        guards(CODE, new PermissionPolicyService.ConditionGuard(11L, null), guard(22, "Shanghai"));
        assertThat(evaluate(record(999, "Beijing")).granted()).isFalse();
        assertThat(evaluate(record(1, "Beijing")).granted()).isTrue();
        assertThat(evaluate(record(999, "Shanghai")).granted()).isTrue();
    }

    @Test void rowAstAndOrdinaryGuardBelongToTheSameCompleteGrant() throws Exception {
        var row = grant(11, 101, CODE); row.setDataScopeAstJson(warehouseAst("Shanghai"));
        grants(row); scope(101, DataScopeCondition.all()); guards(CODE, new PermissionPolicyService.ConditionGuard(11L, null));
        assertThat(evaluate(record(999, "Beijing")).granted()).isFalse();
        assertThat(evaluate(record(999, "Shanghai")).granted()).isTrue();
        row.setDataScopeAstJson("{broken}");
        assertThat(evaluate(record(999, "Shanghai")).granted()).isFalse();
    }

    @Test void unconfiguredObjectCannotBorrowEvenFixedActionAuthorityFromTheSameDuty() {
        var row = grant(11,101,CODE);
        row.setFieldScopeJson("{\"version\":2,\"models\":{\"other\":{\"unrestricted\":true}}}");
        grants(row); scope(101, DataScopeCondition.all());
        guards(CODE, new PermissionPolicyService.ConditionGuard(11L, null));
        var result = authorization.resolve(5L, List.of(CODE), MODEL, record(1,"Shanghai"));
        assertThat(result.result().granted()).isFalse();assertThat(result.grants()).isEmpty();
        row.setFieldScopeJson("{\"version\":2,\"models\":{\"receipt\":{\"readFields\":[],\"writeFields\":[]}}}");
        assertThat(authorization.resolve(5L, List.of(CODE), MODEL, record(1,"Shanghai")).result().granted()).isTrue();
    }

    @Test void missingGuardCannotBecomeAnUnconditionalGrant() {
        grants(grant(11, 101, CODE)); scope(101, DataScopeCondition.all()); guards(CODE);
        assertThat(evaluate(record(1, "Shanghai")).granted()).isFalse();
    }

    @Test void missingAndUnknownScopeCannotBecomeAll() {
        grants(grant(11, 101, CODE)); guards(CODE, new PermissionPolicyService.ConditionGuard(11L, null));
        assertThat(evaluate(record(1, "Shanghai")).granted()).isFalse();
        scope(101, DataScopeCondition.notConfigured());
        assertThat(evaluate(record(1, "Shanghai")).granted()).isFalse();
    }

    @Test void anEffectiveDenyBlocksOtherRolesOnTheSamePermission() {
        var deny = grant(22, 202, CODE); deny.setGrantType("deny");
        grants(grant(11, 101, CODE), deny);
        assertThat(evaluate(record(1, "Shanghai")).granted()).isFalse(); verifyNoInteractions(scopes);
    }

    @Test void nativeCommandCannotBorrowPermissionFromAnotherResourceOrAction() {
        assertThat(authorization.resolveNative(5L, List.of(CODE), MODEL, record(1, "Shanghai"), "update")
                .result().granted()).isFalse();
        verifyNoInteractions(bindings, scopes, policies);
    }

    @Test void nativeCommandUsesOneMatchingGrantAndRequiresAnExplicitScope() {
        var definition = permission(CODE); definition.setResourceCode(MODEL); definition.setAction("manage");
        when(snapshots.resolvePermissionDefinition(100L, CODE)).thenReturn(definition);
        grants(grant(11, 101, CODE), grant(22, 202, CODE));
        when(scopes.resolveScopeForRole(5L, 101L, MODEL, "manage", MODEL)).thenReturn(
                new DataScopeCondition("self", "created_by", 1L, null, List.of(), List.of()));
        when(scopes.resolveScopeForRole(5L, 202L, MODEL, "manage", MODEL)).thenReturn(DataScopeCondition.all());
        guards(CODE, new PermissionPolicyService.ConditionGuard(11L, null));
        var result = authorization.resolveNative(5L, List.of(CODE), MODEL, record(1, "Shanghai"), "update");
        assertThat(result.result().granted()).isTrue();
        assertThat(result.grants()).extracting(com.auraboot.framework.permission.engine.model.BusinessGrantDecision.Grant::grantId)
                .containsExactly(11L);
        when(scopes.resolveScopeForRole(5L, 101L, MODEL, "manage", MODEL)).thenReturn(DataScopeCondition.notConfigured());
        assertThat(authorization.resolveNative(5L, List.of(CODE), MODEL, record(1, "Shanghai"), "update")
                .result().granted()).isFalse();
    }

    @Test void nativeCreationUsesItsOwnRootAndCompleteScopeConditionAndFields() throws Exception {
        var definition = permission(CODE); definition.setResourceCode(MODEL); definition.setAction("create");
        when(snapshots.resolvePermissionDefinition(100L, CODE)).thenReturn(definition);
        var own = grant(11, 101, CODE); own.setFieldScopeJson(fieldScope("label"));
        var conditional = grant(22, 202, CODE); conditional.setFieldScopeJson(fieldScope("cost"));
        grants(own, conditional);
        when(scopes.resolveScopeForRole(5L, 101L, MODEL, "create", MODEL)).thenReturn(new DataScopeCondition("none", null, null, null, List.of(), List.of()));
        when(scopes.resolveScopeForRole(5L, 202L, MODEL, "create", MODEL)).thenReturn(DataScopeCondition.all());
        guards(CODE, new PermissionPolicyService.ConditionGuard(11L, null), guard(22, "Shanghai"));
        assertThat(authorization.resolveNative(5L, List.of(CODE), MODEL, record(1, "Beijing"), "create")
                .result().granted()).isFalse();
        var candidate = authorization.resolveNative(5L, List.of(CODE), MODEL, record(1, "Shanghai"), "create");
        assertThat(candidate.result().granted()).isTrue();
        assertThat(candidate.grants()).extracting(com.auraboot.framework.permission.engine.model.BusinessGrantDecision.Grant::grantId)
                .containsExactly(22L);
        assertThat(candidate.canWrite("cost")).isTrue(); assertThat(candidate.canWrite("label")).isFalse();
        conditional.setDataScopeAstJson(warehouseAst("Beijing"));
        assertThat(authorization.resolveNative(5L, List.of(CODE), MODEL, record(1, "Shanghai"), "create")
                .result().granted()).isFalse();
    }

    @Test void nativeCreationDoesNotInferAllFromManageOrMissingScope() {
        var definition = permission(CODE); definition.setResourceCode(MODEL); definition.setAction("manage");
        when(snapshots.resolvePermissionDefinition(100L, CODE)).thenReturn(definition);
        grants(grant(11, 101, CODE)); guards(CODE, new PermissionPolicyService.ConditionGuard(11L, null));
        when(scopes.resolveScopeForRole(5L, 101L, MODEL, "manage", MODEL)).thenReturn(DataScopeCondition.notConfigured());
        assertThat(authorization.resolveNative(5L, List.of(CODE), MODEL, record(1, "Shanghai"), "create")
                .result().granted()).isFalse();
        when(scopes.resolveScopeForRole(5L, 101L, MODEL, "manage", MODEL)).thenReturn(
                new DataScopeCondition("self", "created_by", 1L, null, List.of(), List.of()));
        assertThat(authorization.resolveNative(5L, List.of(CODE), MODEL, record(1, "Shanghai"), "create")
                .result().granted()).isTrue();
        definition.setAction("update");
        assertThat(authorization.resolveNative(5L, List.of(CODE), MODEL, record(1, "Shanghai"), "create")
                .result().granted()).isFalse();
    }

    @Test void nativeCreationRejectsForeignOrUnresolvedOwnershipBeforeReadingGrants() {
        var definition = permission(CODE); definition.setResourceCode(MODEL); definition.setAction("create");
        when(snapshots.resolvePermissionDefinition(100L, CODE)).thenReturn(definition);
        grants(grant(11, 101, CODE)); guards(CODE, new PermissionPolicyService.ConditionGuard(11L, null));
        when(scopes.resolveScopeForRole(5L, 101L, MODEL, "create", MODEL)).thenReturn(DataScopeCondition.all());
        assertThat(authorization.resolveNative(5L, List.of(CODE), MODEL, record(999, "Shanghai"), "create")
                .result().granted()).isFalse();
        var candidate = new java.util.HashMap<>(record(1, "Shanghai")); candidate.remove("created_by");
        assertThat(authorization.resolveNative(5L, List.of(CODE), MODEL, candidate, "create").result().granted()).isFalse();
        candidate.put("created_by", 1L); candidate.put("tenant_id", 200L);
        assertThat(authorization.resolveNative(5L, List.of(CODE), MODEL, candidate, "create").result().granted()).isFalse();
        MetaContext.clear();
        assertThat(authorization.resolveNative(5L, List.of(CODE), MODEL, record(1, "Shanghai"), "create")
                .result().granted()).isFalse();
        verifyNoInteractions(bindings, scopes, policies);
    }

    @Test void explicitWildcardStillUsesItsOwnRoleScopeAndGuard() throws Exception {
        grants(grant(11, 101, "*")); scope(101, DataScopeCondition.all()); guards("*", guard(11, "Shanghai"));
        assertThat(evaluate(record(999, "Beijing")).granted()).isFalse();
        assertThat(evaluate(record(999, "Shanghai")).granted()).isTrue();
        verify(policies, never()).getConditionGuards(5L, CODE);
    }

    @Test void alternativeDeclaredPermissionsAreEvaluatedAsCompleteGrants() {
        when(bindings.findBusinessGrants(100L, 1L, 5L, CODE)).thenReturn(List.of());
        String alternative = "inventory.receipt.supervise";
        when(snapshots.resolvePermissionDefinition(100L, alternative)).thenReturn(permission(alternative));
        when(bindings.findBusinessGrants(100L, 1L, 5L, alternative)).thenReturn(List.of(grant(22, 202, alternative)));
        scope(202, DataScopeCondition.all()); guards(alternative, new PermissionPolicyService.ConditionGuard(22L, null));
        assertThat(authorization.evaluate(5L, List.of(CODE, alternative), MODEL, record(999, "Beijing")).granted()).isTrue();
    }

    @Test void genericEntryPermissionOrMenuDefinitionCannotAuthorizeBusinessData() {
        assertThat(authorization.evaluate(5L, List.of("meta.command.execute"), MODEL, record(1, "Shanghai")).granted()).isFalse();
        var page = permission(CODE); page.setResourceType("page");
        when(snapshots.resolvePermissionDefinition(100L, CODE)).thenReturn(page);
        assertThat(evaluate(record(1, "Shanghai")).granted()).isFalse(); verifyNoInteractions(bindings);
    }

    @Test void crossTenantOtherSubjectAndWeakSnapshotDenyBeforeReadingGrants() {
        assertThat(authorization.evaluate(6L, List.of(CODE), MODEL, record(1, "Shanghai")).granted()).isFalse();
        var foreign = new java.util.HashMap<>(record(1, "Shanghai")); foreign.put("tenant_id", 200L);
        assertThat(evaluate(foreign).granted()).isFalse();
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);
        assertThat(evaluate(record(1, "Shanghai")).granted()).isFalse(); verifyNoInteractions(bindings);
    }

    @Test void fieldsComeOnlyFromCompleteMatchingGrantsAndMergeForTheSameRecord() throws Exception {
        var own = grant(11,101,CODE); own.setFieldScopeJson(fieldScope("label"));
        var shanghai = grant(22,202,CODE); shanghai.setFieldScopeJson(fieldScope("cost"));
        grants(own,shanghai);
        scope(101,new DataScopeCondition("self","created_by",1L,null,List.of(),List.of()));
        scope(202,DataScopeCondition.all());
        guards(CODE,new PermissionPolicyService.ConditionGuard(11L,null),guard(22,"Shanghai"));
        var ownBeijing=authorization.resolve(5L,List.of(CODE),MODEL,record(1,"Beijing"));
        assertThat(ownBeijing.result().granted()).isTrue();
        assertThat(ownBeijing.canWrite("label")).isTrue(); assertThat(ownBeijing.canWrite("cost")).isFalse();
        var foreignShanghai=authorization.resolve(5L,List.of(CODE),MODEL,record(999,"Shanghai"));
        assertThat(foreignShanghai.canWrite("cost")).isTrue(); assertThat(foreignShanghai.canWrite("label")).isFalse();
        var ownShanghai=authorization.resolve(5L,List.of(CODE),MODEL,record(1,"Shanghai"));
        assertThat(ownShanghai.grants()).hasSize(2);
        assertThat(ownShanghai.canWrite("cost")).isTrue(); assertThat(ownShanghai.canWrite("label")).isTrue();
        assertThat(ownShanghai.canRead("cost")).isTrue(); assertThat(ownShanghai.canRead("other")).isFalse();
        own.setFieldScopeJson(json.writeValueAsString(Map.of("modelCode",MODEL,"readFields",List.of(),"writeFields",List.of())));
        var emptyPlusAllowed=authorization.resolve(5L,List.of(CODE),MODEL,record(1,"Shanghai"));
        assertThat(emptyPlusAllowed.canWrite("cost")).isTrue();assertThat(emptyPlusAllowed.canWrite("label")).isFalse();
    }

    @Test void explicitEmptyFieldsDoNotBecomeLegacyUnrestrictedOrGlobalDeny() throws Exception {
        var row=grant(11,101,CODE);row.setFieldScopeJson(json.writeValueAsString(Map.of(
                "modelCode",MODEL,"readFields",List.of(),"writeFields",List.of())));
        grants(row);scope(101,DataScopeCondition.all());guards(CODE,new PermissionPolicyService.ConditionGuard(11L,null));
        var result=authorization.resolve(5L,List.of(CODE),MODEL,record(1,"Beijing"));
        assertThat(result.result().granted()).isTrue();assertThat(result.canRead("label")).isFalse();assertThat(result.canWrite("label")).isFalse();
        row.setFieldScopeJson(null);
        assertThat(authorization.resolve(5L,List.of(CODE),MODEL,record(1,"Beijing")).canWrite("label")).isTrue();
    }

    @Test void missingContextReturnsARefusalWithoutLoadingAuthorizationData() {
        MetaContext.clear();
        var result=authorization.resolve(5L,List.of(CODE),MODEL,record(1,"Shanghai"));
        assertThat(result.result().granted()).isFalse();assertThat(result.canWrite("label")).isFalse();
        assertThat(result.tenantId()).isNull();assertThat(result.userId()).isNull();
        assertThat(authorization.evaluate(5L,List.of(CODE),MODEL,record(1,"Shanghai")).granted()).isFalse();
        verifyNoInteractions(bindings,scopes,policies);
    }

    private String fieldScope(String field) throws Exception {
        return json.writeValueAsString(Map.of("modelCode",MODEL,"readFields",List.of(field),"writeFields",List.of(field)));
    }

    @Test void generatedModelReadUsesTheSameCompleteScopeConditionAndFieldGrant() throws Exception {
        String code = "model.receipt.read";
        var definition = permission(code); definition.setResourceType("model"); definition.setResourceCode(MODEL); definition.setAction("read");
        when(snapshots.resolvePermissionDefinition(100L, code)).thenReturn(definition);
        var row = grant(11,101,code); row.setFieldScopeJson(json.writeValueAsString(Map.of("modelCode",MODEL,"readFields",List.of("label"),"writeFields",List.of())));
        when(bindings.findBusinessGrants(100L,1L,5L,code)).thenReturn(List.of(row));
        when(scopes.resolveScopeForRole(5L,101L,MODEL,"read",MODEL)).thenReturn(new DataScopeCondition("self","created_by",1L,null,List.of(),List.of()));
        guards(code, guard(11,"Shanghai"));
        var allowed = authorization.resolveModelRead(5L,MODEL,record(1,"Shanghai"));
        assertThat(allowed.result().granted()).isTrue(); assertThat(allowed.canRead("label")).isTrue();
        assertThat(allowed.canRead("cost")).isFalse(); assertThat(allowed.canWrite("label")).isFalse();
        assertThat(authorization.resolveModelRead(5L,MODEL,record(999,"Shanghai")).result().granted()).isFalse();
        assertThat(authorization.resolveModelRead(5L,MODEL,record(1,"Beijing")).result().granted()).isFalse();
        assertThat(authorization.resolveNative(5L,List.of(code),MODEL,record(1,"Shanghai"),"update").result().granted()).isFalse();
    }

    @Test void ordinaryReadSharesOverrideOnlyScopeAndNeverConditionsOrFields() throws Exception {
        String code = MODEL + ".read";
        var definition = permission(code); definition.setResourceType("model"); definition.setResourceCode(MODEL); definition.setAction("read");
        when(snapshots.resolvePermissionDefinition(100L,code)).thenReturn(definition);
        var row = grant(11,101,code); row.setFieldScopeJson(fieldScope("label"));
        when(bindings.findBusinessGrants(100L,1L,5L,code)).thenReturn(List.of(row));
        when(scopes.resolveOrdinaryReadScopeForRole(5L,101L,MODEL)).thenReturn(DataScopeCondition.none());
        guards(code,guard(11,"Shanghai"));
        when(recordShares.evaluate(eq(5L),eq(MODEL),eq("read"),any())).thenReturn(
                new com.auraboot.framework.permission.engine.model.EvaluationStep("RecordShare",
                        com.auraboot.framework.permission.engine.model.EvaluationVerdict.ALLOW,"shared"));
        var allowed = authorization.resolveOrdinaryRead(5L,MODEL,record(999,"Shanghai"));
        assertThat(allowed.canRead("label")).isTrue(); assertThat(allowed.canRead("cost")).isFalse();
        assertThat(authorization.resolveOrdinaryRead(5L,MODEL,record(999,"Beijing")).result().granted()).isFalse();
        when(recordShares.evaluate(eq(5L),eq(MODEL),eq("read"),any())).thenReturn(
                new com.auraboot.framework.permission.engine.model.EvaluationStep("RecordShare",
                        com.auraboot.framework.permission.engine.model.EvaluationVerdict.NOT_APPLICABLE,"not shared"));
        assertThat(authorization.resolveOrdinaryRead(5L,MODEL,record(999,"Shanghai")).result().granted()).isFalse();
    }

    @Test void domainReadSharesDoNotReapplyObjectScopeButRetainFieldsAndConditions() throws Exception {
        var definition = permission(CODE); definition.setResourceCode("domain"); definition.setAction("read");
        definition.setExtension(Map.of("domainModels", Map.of(MODEL, List.of("read"))));
        when(snapshots.resourcePermissionCodes(100L, MODEL, "read")).thenReturn(List.of(CODE));
        when(snapshots.resolvePermissionDefinition(100L, CODE)).thenReturn(definition);
        String modelCode = "model." + MODEL + ".read";
        var modelDefinition = permission(modelCode); modelDefinition.setResourceType("model");
        modelDefinition.setResourceCode(MODEL); modelDefinition.setAction("read");
        when(snapshots.resolvePermissionDefinition(100L, modelCode)).thenReturn(modelDefinition);
        var function = grant(11, 101, CODE); grants(function);
        var fields = grant(12, 101, modelCode); fields.setFieldScopeJson(fieldScope("label"));
        when(bindings.findBusinessGrants(100L,1L,5L,modelCode)).thenReturn(List.of(fields));
        when(scopes.resolveObjectBoundScopeForRole(5L,101L,MODEL,"read",MODEL))
                .thenReturn(new DataScopeCondition("self","created_by",1L,null,List.of(),List.of()));
        guards(CODE, new PermissionPolicyService.ConditionGuard(11L,null));
        guards(modelCode, guard(12,"Shanghai"));
        when(recordShares.evaluate(eq(5L),eq(MODEL),eq("read"),any())).thenReturn(
                new com.auraboot.framework.permission.engine.model.EvaluationStep("RecordShare",
                        com.auraboot.framework.permission.engine.model.EvaluationVerdict.ALLOW,"shared"));
        var allowed = authorization.resolveOrdinaryRead(5L,MODEL,record(999,"Shanghai"));
        assertThat(allowed.result().granted()).isTrue();
        assertThat(allowed.canRead("label")).isTrue(); assertThat(allowed.canRead("cost")).isFalse();
        assertThat(authorization.resolveOrdinaryRead(5L,MODEL,record(999,"Beijing")).result().granted()).isFalse();
        when(recordShares.evaluate(eq(5L),eq(MODEL),eq("read"),any())).thenReturn(
                new com.auraboot.framework.permission.engine.model.EvaluationStep("RecordShare",
                        com.auraboot.framework.permission.engine.model.EvaluationVerdict.NOT_APPLICABLE,"revoked"));
        assertThat(authorization.resolveOrdinaryRead(5L,MODEL,record(999,"Shanghai")).result().granted()).isFalse();
    }

    @Test void ordinaryReadPreservesLegacyUnconfiguredScopeWithoutRelaxingBusinessCommands() throws Exception {
        String code = "model." + MODEL + ".read";
        var definition = permission(code); definition.setResourceType("model"); definition.setResourceCode(MODEL); definition.setAction("read");
        when(snapshots.resolvePermissionDefinition(100L,code)).thenReturn(definition);
        var row = grant(11,101,code); row.setFieldScopeJson(fieldScope("label"));
        when(bindings.findBusinessGrants(100L,1L,5L,code)).thenReturn(List.of(row));
        when(scopes.resolveOrdinaryReadScopeForRole(5L,101L,MODEL)).thenReturn(
                new DataScopeCondition("not_configured",null,null,null,List.of(),List.of()));
        guards(code,new PermissionPolicyService.ConditionGuard(11L,null));
        when(recordShares.evaluate(eq(5L),eq(MODEL),eq("read"),any())).thenReturn(
                new com.auraboot.framework.permission.engine.model.EvaluationStep("RecordShare",
                        com.auraboot.framework.permission.engine.model.EvaluationVerdict.NOT_APPLICABLE,"not shared"));
        assertThat(authorization.resolveOrdinaryRead(5L,MODEL,record(999,"Shanghai")).canRead("label")).isTrue();
        assertThat(authorization.resolveOrdinaryRead(5L,MODEL,record(999,"Shanghai")).canRead("cost")).isFalse();
        when(scopes.resolveScopeForRole(5L,101L,MODEL,"read",MODEL)).thenReturn(DataScopeCondition.none());
        assertThat(authorization.resolveModelRead(5L,MODEL,record(999,"Shanghai")).result().granted()).isFalse();
    }

    private com.auraboot.framework.permission.engine.model.PermissionResult evaluate(Map<String, Object> record) {
        return authorization.evaluate(5L, List.of(CODE), MODEL, record);
    }
    @Test void optedInObjectScopeRetainsSelfOwnershipAndDoesNotBorrowAnotherGrant() {
        var permission = permission(CODE);permission.setExtension(Map.of("businessScopePolicy","record"));
        when(snapshots.resolvePermissionDefinition(100L,CODE)).thenReturn(permission);
        grants(grant(11,101,CODE),grant(22,202,CODE));
        guards(CODE,new PermissionPolicyService.ConditionGuard(11L,null),new PermissionPolicyService.ConditionGuard(22L,null));
        when(scopes.resolveObjectBoundScopeForRole(5L,101L,"inventory.receipt","confirm",MODEL))
                .thenReturn(new DataScopeCondition("self","created_by",1L,null,List.of(),List.of()));
        when(scopes.resolveObjectBoundScopeForRole(5L,202L,"inventory.receipt","confirm",MODEL)).thenReturn(DataScopeCondition.none());
        assertThat(evaluate(record(1,"Shanghai")).granted()).isTrue();
        assertThat(evaluate(record(999,"Shanghai")).granted()).isFalse();
        verify(scopes,never()).resolveScopeForRole(any(),any(),any(),any(),any());
    }

    @Test void objectScopeLegacyAbsenceIsNotAnAllRewriteAndUnknownPolicyFailsClosed() {
        var permission = permission(CODE);permission.setExtension(Map.of("businessScopePolicy","record"));
        when(snapshots.resolvePermissionDefinition(100L,CODE)).thenReturn(permission);
        grants(grant(11,101,CODE));guards(CODE,new PermissionPolicyService.ConditionGuard(11L,null));
        when(scopes.resolveObjectBoundScopeForRole(5L,101L,"inventory.receipt","confirm",MODEL)).thenReturn(DataScopeCondition.notConfigured());
        assertThat(evaluate(record(1,"Shanghai")).granted()).isTrue();
        permission.setExtension(Map.of("businessScopePolicy","unknown"));
        assertThat(evaluate(record(1,"Shanghai")).granted()).isFalse();
    }

    @Test void documentRegistrationReusesTheDutyWithoutRemovingChildScopeOrFieldRestrictions() {
        var definition=permission(CODE);definition.setResourceCode("domain");definition.setAction("write");
        definition.setExtension(Map.of("domainModels",Map.of(MODEL,List.of("read","update"))));
        when(snapshots.resolvePermissionDefinition(100L,CODE)).thenReturn(definition);
        var binding=grant(11,101,CODE);grants(binding);guards(CODE,new PermissionPolicyService.ConditionGuard(11L,null));
        String child="ab_document_artifact";
        when(scopes.resolveObjectBoundScopeForRole(5L,101L,child,"create",child))
                .thenReturn(new DataScopeCondition("self","created_by",1L,null,List.of(),List.of()));
        var artifact=Map.<String,Object>of("pid","artifact-pid","tenant_id",100L,"created_by",1L,
                "ab_da_source_model",MODEL,"ab_da_source_record_pid","receipt-pid","ab_da_definition_code","example.standard",
                "ab_da_definition_version","1.0.0","ab_da_format","xlsx","ab_da_filename","example.xlsx");
        assertThat(authorization.resolveOrdinaryCreate(5L,child,artifact).result().granted()).isFalse();
        withDocumentEffect(()->{
            var allowed=authorization.resolveOrdinaryCreate(5L,child,artifact);
            assertThat(allowed.result().granted()).isTrue();assertThat(allowed.canWrite("ab_da_filename")).isTrue();
            binding.setFieldScopeJson("{\"version\":2,\"models\":{\"ab_document_artifact\":{\"readFields\":[\"ab_da_filename\"],\"writeFields\":[]}}}");
            var limited=authorization.resolveOrdinaryCreate(5L,child,artifact);
            assertThat(limited.result().granted()).isTrue();assertThat(limited.canWrite("ab_da_filename")).isFalse();
            binding.setFieldScopeJson(null);
            when(scopes.resolveObjectBoundScopeForRole(5L,101L,child,"create",child)).thenReturn(DataScopeCondition.none());
            assertThat(authorization.resolveOrdinaryCreate(5L,child,artifact).result().granted()).isFalse();
            return null;
        });
        assertThat(authorization.resolveOrdinaryCreate(5L,child,artifact).result().granted()).isFalse();
    }

    @Test void hiddenTechnicalCreationRetainsItsOwnObjectAndFieldPolicies() {
        String child = "crm_customer_request_common";
        var definition = permission(CODE); definition.setResourceCode("domain"); definition.setAction("write");
        definition.setExtension(Map.of("domainModels", Map.of(MODEL, List.of("read", "create")),
                "commandEffects", Map.of(child, List.of("read", "create"))));
        when(snapshots.resolvePermissionDefinition(100L, CODE)).thenReturn(definition);
        when(snapshots.resourcePermissionCodes(100L, child, "create")).thenReturn(List.of(CODE));
        var binding = grant(11, 101, CODE); grants(binding);
        guards(CODE, new PermissionPolicyService.ConditionGuard(11L, null));
        when(scopes.resolveObjectBoundScopeForRole(5L, 101L, child, "create", child))
                .thenReturn(new DataScopeCondition("self", "created_by", 1L, null, List.of(), List.of()));
        var row = Map.<String,Object>of("pid", "new-request", "tenant_id", 100L, "created_by", 1L, "title", "Quote request");
        assertThat(authorization.resolveOrdinaryCreate(5L, child, row).result().granted()).isFalse();
        MetaContext.runWithCommandPermitPlan("SELF", null, MODEL, null, () ->
                MetaContext.runWithAuthorizedCommandCode("example:create", () -> MetaContext.runWithCommandAuthority(CODE, () -> {
                    assertThat(authorization.resolveOrdinaryCreate(5L, child, row).result().granted()).isTrue();
                    binding.setFieldScopeJson("{\"version\":2,\"models\":{\"crm_customer_request_common\":{\"readFields\":[\"title\"],\"writeFields\":[]}}}");
                    assertThat(authorization.resolveOrdinaryCreate(5L, child, row).canWrite("title")).isFalse();
                    binding.setFieldScopeJson(null);
                    when(scopes.resolveObjectBoundScopeForRole(5L, 101L, child, "create", child)).thenReturn(DataScopeCondition.none());
                    assertThat(authorization.resolveOrdinaryCreate(5L, child, row).result().granted()).isFalse();
                })));
        assertThat(authorization.resolveOrdinaryCreate(5L, child, row).result().granted()).isFalse();
    }

    private <T>T withDocumentEffect(Supplier<T> body) {
        var context=mock(com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext.class);
        var command=new com.auraboot.framework.meta.entity.CommandDefinition();command.setModelCode(MODEL);
        var request=new com.auraboot.framework.meta.dto.CommandExecuteRequest();request.setTargetRecordPid("receipt-pid");
        when(context.getCommand()).thenReturn(command);when(context.getRequest()).thenReturn(request);
        when(context.getCommandCode()).thenReturn(CODE);when(context.getTenantId()).thenReturn(100L);when(context.getUserId()).thenReturn(1L);
        when(context.getTargetRecordReadable()).thenReturn(true);
        when(context.getAuthorizationVerdict()).thenReturn(com.auraboot.framework.meta.service.impl.pipeline.CommandAuthorizationVerdict.authorized(CODE));
        when(context.getExecConfig()).thenReturn(Map.of("handlerParams",Map.of("documentDefinition",Map.of(
                "code","example.standard","version","1.0.0","dataset",MODEL+":snapshot_v1",
                "versionPolicy","immutable-artifact","outputs",List.of("xlsx","pdf")))));
        return MetaContext.runWithCommandPermitPlan("TARGET",null,MODEL,"receipt-pid",()->MetaContext.runWithCommandAuthority(CODE,()->
                MetaContext.runWithCommandAggregate("receipt-pid",()->MetaContext.runWithAuthorizedCommandCode(CODE,()->{
                    try{return com.auraboot.framework.meta.security.DocumentArtifactCommandScope.within(context,body::get);}
                    catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new AssertionError(failure);}
                }))));
    }

    private void grants(RolePermissionMapper.BusinessGrantRow... rows) {
        when(bindings.findBusinessGrants(100L, 1L, 5L, CODE)).thenReturn(List.of(rows));
    }
    private void guards(String code, PermissionPolicyService.ConditionGuard... guards) {
        when(policies.getConditionGuards(5L, code)).thenReturn(List.of(guards));
    }
    private void scope(long role, DataScopeCondition value) {
        when(scopes.resolveScopeForRole(5L, role, "inventory.receipt", "confirm", MODEL)).thenReturn(value);
    }
    private PermissionPolicyService.ConditionGuard guard(long id, String warehouse) throws Exception {
        return new PermissionPolicyService.ConditionGuard(id, warehouseAst(warehouse));
    }
    private String warehouseAst(String warehouse) throws Exception {
        return json.writeValueAsString(ConditionNode.CompareNode.of(
                new Operand.PathOperand(Scope.RECORD, "data.warehouse", DataType.STRING), Operator.EQ,
                new Operand.LiteralOperand(warehouse, DataType.STRING)));
    }
    private static RolePermissionMapper.BusinessGrantRow grant(long id, long role, String code) {
        var grant = new RolePermissionMapper.BusinessGrantRow(); grant.setId(id); grant.setRoleId(role);
        grant.setPermissionCode(code); grant.setGrantType("grant"); return grant;
    }
    private static Permission permission(String code) {
        var permission = new Permission(); permission.setCode(code); permission.setResourceType("operation");
        permission.setResourceCode("inventory.receipt"); permission.setAction("confirm"); return permission;
    }
    private static Map<String, Object> record(long owner, String warehouse) {
        return Map.of("pid", "receipt-pid", "tenant_id", 100L, "created_by", owner, "warehouse", warehouse);
    }
}
