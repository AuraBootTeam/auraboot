package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.mapper.DataPermissionPolicyMapper;
import com.auraboot.framework.permission.engine.evaluator.DataScopeEvaluator;
import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import com.fasterxml.jackson.databind.ObjectMapper;
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
}
