package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.mapper.DataPermissionPolicyMapper;
import com.auraboot.framework.permission.engine.evaluator.DataScopeEvaluator;
import com.auraboot.framework.permission.engine.model.DataScopeCondition;
import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataPermissionEngineImplDataScopeTest {

    private static final Long TENANT_ID = 10L;
    private static final Long USER_ID = 20L;
    private static final Long MEMBER_ID = 30L;
    private static final String MODEL_CODE = "phase_one_model";

    @Mock
    private DataPermissionPolicyMapper policyMapper;

    @Mock
    private DataScopeEvaluator dataScopeEvaluator;

    private DataPermissionEngineImpl engine;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, USER_ID, "user-pid", "tester");
        MetaContext.setMemberId(MEMBER_ID);
        engine = new DataPermissionEngineImpl(policyMapper, dataScopeEvaluator, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("canAccessRecord denies when role data scope denies, even without legacy row policies")
    void canAccessRecord_deniesWhenDataScopeDenies() {
        when(policyMapper.findEffectivePolicies(TENANT_ID, MODEL_CODE, MEMBER_ID)).thenReturn(List.of());
        when(dataScopeEvaluator.evaluate(MEMBER_ID, MODEL_CODE, "read", Map.of("created_by", 99L)))
                .thenReturn(new EvaluationStep("DataScope", EvaluationVerdict.DENY, "not owner"));

        boolean allowed = engine.canAccessRecord(
                TENANT_ID,
                MODEL_CODE,
                USER_ID,
                Map.of("created_by", 99L));

        assertThat(allowed).isFalse();
    }

    @Test
    @DisplayName("canAccessRecord uses explicit action for role data scope")
    void canAccessRecord_usesExplicitAction() {
        Map<String, Object> record = Map.of("pid", "rec-1", "created_by", USER_ID);
        when(policyMapper.findEffectivePolicies(TENANT_ID, MODEL_CODE, MEMBER_ID)).thenReturn(List.of());
        when(dataScopeEvaluator.evaluate(MEMBER_ID, MODEL_CODE, "delete", record))
                .thenReturn(new EvaluationStep("DataScope", EvaluationVerdict.ALLOW, "delete owner"));

        boolean allowed = engine.canAccessRecord(TENANT_ID, MODEL_CODE, "delete", USER_ID, record);

        assertThat(allowed).isTrue();
    }

    @Test
    @DisplayName("filterRecords applies role data scope as a post-query guard")
    void filterRecords_appliesDataScopeGuard() {
        Map<String, Object> own = Map.of("pid", "own", "created_by", USER_ID);
        Map<String, Object> other = Map.of("pid", "other", "created_by", 99L);
        when(policyMapper.findEffectivePolicies(TENANT_ID, MODEL_CODE, MEMBER_ID)).thenReturn(List.of());
        when(dataScopeEvaluator.evaluate(MEMBER_ID, MODEL_CODE, "read", own))
                .thenReturn(new EvaluationStep("DataScope", EvaluationVerdict.ALLOW, "owner"));
        when(dataScopeEvaluator.evaluate(MEMBER_ID, MODEL_CODE, "read", other))
                .thenReturn(new EvaluationStep("DataScope", EvaluationVerdict.DENY, "not owner"));

        List<Map<String, Object>> filtered = engine.filterRecords(
                TENANT_ID,
                MODEL_CODE,
                USER_ID,
                List.of(own, other));

        assertThat(filtered).containsExactly(own);
    }

    // ---- data-scope SQL identifier validation (deep-review DR-20260701 W1-C-1) ----
    // ownerField / deptField are model-config identifiers concatenated into SQL; the sibling
    // row-policy builder validates them, this SQL-gen path previously did not. Values (Long owner id,
    // quote-escaped dept pids) are already safe; only the field identifiers are the injection surface.

    @Test
    @DisplayName("dataScopeConditionToSql fails secure (1 = 0) on an injected SELF owner field")
    void dataScopeSql_rejectsInjectedOwnerField() {
        DataScopeCondition malicious = new DataScopeCondition(
                "self", "created_by = 1 OR 1=1 --", USER_ID, null, List.of(), List.of());
        String sql = (String) ReflectionTestUtils.invokeMethod(engine, "dataScopeConditionToSql", malicious);
        assertThat(sql).isEqualTo("1 = 0");
    }

    @Test
    @DisplayName("dataScopeConditionToSql fails secure (1 = 0) on an injected DEPT field")
    void dataScopeSql_rejectsInjectedDeptField() {
        DataScopeCondition malicious = new DataScopeCondition(
                "dept", null, null, "dept_id); DROP TABLE x --", List.of("p1"), List.of());
        String sql = (String) ReflectionTestUtils.invokeMethod(engine, "dataScopeConditionToSql", malicious);
        assertThat(sql).isEqualTo("1 = 0");
    }

    @Test
    @DisplayName("dataScopeConditionToSql builds normal SQL for valid identifiers")
    void dataScopeSql_buildsNormalSqlForValidFields() {
        DataScopeCondition self = new DataScopeCondition(
                "self", "created_by", USER_ID, null, List.of(), List.of());
        assertThat((String) ReflectionTestUtils.invokeMethod(engine, "dataScopeConditionToSql", self))
                .isEqualTo("created_by = " + USER_ID);

        DataScopeCondition dept = new DataScopeCondition(
                "dept", null, null, "org_emp_dept_id", List.of("p1", "p2"), List.of());
        assertThat((String) ReflectionTestUtils.invokeMethod(engine, "dataScopeConditionToSql", dept))
                .isEqualTo("org_emp_dept_id IN ('p1','p2')");
    }

    // ---- SELF owner value typing (2026-06-28 Quote/BOM varchar/ULID owner incident) ----
    // A string owner value (userPid against a varchar/ULID owner column) must be quoted;
    // unquoted it is either broken SQL or an injection surface.

    @Test
    @DisplayName("dataScopeConditionToSql quotes string owner values (ULID/pid owner columns)")
    void dataScopeSql_quotesStringOwnerValue() {
        DataScopeCondition self = new DataScopeCondition(
                "self", "crm_acc_owner", "01KWEYE0JGW6364G1VMP5MZK7X", null, List.of(), List.of());
        assertThat((String) ReflectionTestUtils.invokeMethod(engine, "dataScopeConditionToSql", self))
                .isEqualTo("crm_acc_owner = '01KWEYE0JGW6364G1VMP5MZK7X'");
    }

    @Test
    @DisplayName("dataScopeConditionToSql escapes quotes inside string owner values")
    void dataScopeSql_escapesQuotesInStringOwnerValue() {
        DataScopeCondition self = new DataScopeCondition(
                "self", "crm_acc_owner", "a'b", null, List.of(), List.of());
        assertThat((String) ReflectionTestUtils.invokeMethod(engine, "dataScopeConditionToSql", self))
                .isEqualTo("crm_acc_owner = 'a''b'");
    }

    @Test
    @DisplayName("dataScopeConditionToSql keeps numeric owner values unquoted")
    void dataScopeSql_numericOwnerValueUnquoted() {
        DataScopeCondition self = new DataScopeCondition(
                "self", "created_by", USER_ID, null, List.of(), List.of());
        assertThat((String) ReflectionTestUtils.invokeMethod(engine, "dataScopeConditionToSql", self))
                .isEqualTo("created_by = " + USER_ID);
    }

    @Test
    @DisplayName("dataScopeConditionToSql fails secure (1 = 0) on a null owner value")
    void dataScopeSql_nullOwnerValueFailsSecure() {
        DataScopeCondition self = new DataScopeCondition(
                "self", "created_by", null, null, List.of(), List.of());
        assertThat((String) ReflectionTestUtils.invokeMethod(engine, "dataScopeConditionToSql", self))
                .isEqualTo("1 = 0");
    }
}
