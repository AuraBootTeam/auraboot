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
    private final BusinessCommandRecordAuthorization authorization = new BusinessCommandRecordAuthorization(
            snapshots, bindings, policies, scopes,
            new DataScopeEvaluator(scopes, mock(RecordShareService.class), mock(RecordShareRowSurfaceResolver.class)),
            new PolicyEvaluator(policies, vocabulary, json, mock(ObjectProvider.class)), json);
    private static final String CODE = "inventory.receipt.confirm";
    private static final String MODEL = "receipt";

    @BeforeEach void context() {
        MetaContext.setContext(100L, 1L, "actor-pid", "actor"); MetaContext.setMemberId(5L);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ);
        when(snapshots.withFreshReads(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
        when(snapshots.resolvePermissionDefinition(100L, CODE)).thenReturn(permission(CODE));
        when(vocabulary.buildBusinessContext(eq(5L), eq(MODEL), any())).thenAnswer(call -> DecisionContext.builder()
                .record(call.getArgument(2)).build());
    }

    @AfterEach void clear() {
        MetaContext.clear(); TransactionSynchronizationManager.clear();
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

    private com.auraboot.framework.permission.engine.model.PermissionResult evaluate(Map<String, Object> record) {
        return authorization.evaluate(5L, List.of(CODE), MODEL, record);
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
