package com.auraboot.framework.iot.spi.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.iot.BackgroundRuleAccessor.RuleScope;
import com.auraboot.framework.plugin.extension.iot.BackgroundRuleAccessor.RuleView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IotRuleAccessorImplTest {

    private DynamicDataService dds;
    private IotRuleAccessorImpl accessor;

    @BeforeEach
    void setUp() {
        dds = mock(DynamicDataService.class);
        accessor = new IotRuleAccessorImpl(dds);
        MetaContext.clear();
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    private static PaginationResult<Map<String, Object>> page(Map<String, Object>... rows) {
        PaginationResult<Map<String, Object>> p = new PaginationResult<>();
        p.setRecords(List.of(rows));
        return p;
    }

    private static Map<String, Object> ruleRow(String code, String scope, String scopeKey,
                                               String severity, boolean enabled) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("tenant_id", 1L);
        r.put(IotRuleAccessorImpl.COL_CODE, code);
        r.put(IotRuleAccessorImpl.COL_SCOPE, scope);
        r.put(IotRuleAccessorImpl.COL_SCOPE_KEY, scopeKey);
        r.put(IotRuleAccessorImpl.COL_KIND, "SQL");
        r.put(IotRuleAccessorImpl.COL_EXPRESSION, "temp > 60");
        r.put(IotRuleAccessorImpl.COL_ACTIONS, "[]");
        r.put(IotRuleAccessorImpl.COL_SEVERITY, severity);
        r.put(IotRuleAccessorImpl.COL_COOLDOWN, 60);
        r.put(IotRuleAccessorImpl.COL_ENABLED, enabled);
        return r;
    }

    @Test
    void findActiveByScope_device_buildsExpectedConditions() {
        when(dds.list(eq("iot_rule"), any(DynamicQueryRequest.class)))
                .thenReturn(page(ruleRow("R-1", "DEVICE", "dev-1", "MAJOR", true)));

        List<RuleView> got = accessor.findActiveByScope(1L, RuleScope.DEVICE, "dev-1");
        assertThat(got).hasSize(1);
        assertThat(got.get(0).code()).isEqualTo("R-1");
        assertThat(got.get(0).scope()).isEqualTo(RuleScope.DEVICE);
        assertThat(got.get(0).scopeKey()).isEqualTo("dev-1");
        assertThat(got.get(0).severity()).isEqualTo("MAJOR");
        assertThat(got.get(0).enabled()).isTrue();

        ArgumentCaptor<DynamicQueryRequest> captor = ArgumentCaptor.forClass(DynamicQueryRequest.class);
        verify(dds).list(eq("iot_rule"), captor.capture());
        List<QueryCondition> conds = captor.getValue().getConditions();
        assertThat(conds).extracting(QueryCondition::getFieldName)
                .containsExactlyInAnyOrder(
                        IotRuleAccessorImpl.COL_SCOPE,
                        IotRuleAccessorImpl.COL_ENABLED,
                        IotRuleAccessorImpl.COL_SCOPE_KEY);
    }

    @Test
    void findActiveByScope_tenant_addsIsNullForScopeKey() {
        when(dds.list(eq("iot_rule"), any(DynamicQueryRequest.class))).thenReturn(page());
        accessor.findActiveByScope(1L, RuleScope.TENANT, null);

        ArgumentCaptor<DynamicQueryRequest> captor = ArgumentCaptor.forClass(DynamicQueryRequest.class);
        verify(dds).list(eq("iot_rule"), captor.capture());
        assertThat(captor.getValue().getConditions())
                .anyMatch(c -> IotRuleAccessorImpl.COL_SCOPE_KEY.equals(c.getFieldName())
                        && c.getOperator() == QueryCondition.Operator.IS_NULL);
    }

    @Test
    void findActiveByScope_sortsBySeverityDescThenCodeAsc() {
        when(dds.list(eq("iot_rule"), any(DynamicQueryRequest.class)))
                .thenReturn(page(
                        ruleRow("B", "DEVICE", "dev-1", "WARNING", true),
                        ruleRow("A", "DEVICE", "dev-1", "CRITICAL", true),
                        ruleRow("C", "DEVICE", "dev-1", "CRITICAL", true)));

        List<RuleView> got = accessor.findActiveByScope(1L, RuleScope.DEVICE, "dev-1");
        assertThat(got).extracting(RuleView::code).containsExactly("A", "C", "B");
    }

    @Test
    void findActiveByScope_invalidInputReturnsEmpty() {
        assertThat(accessor.findActiveByScope(0L, RuleScope.DEVICE, "x")).isEmpty();
        assertThat(accessor.findActiveByScope(1L, null, "x")).isEmpty();
        assertThat(accessor.findActiveByScope(1L, RuleScope.DEVICE, null)).isEmpty();
        assertThat(accessor.findActiveByScope(1L, RuleScope.PRODUCT, "  ")).isEmpty();
    }

    @Test
    void findActiveByScope_swallowsDdsException() {
        when(dds.list(eq("iot_rule"), any(DynamicQueryRequest.class)))
                .thenThrow(new RuntimeException("boom"));
        assertThat(accessor.findActiveByScope(1L, RuleScope.DEVICE, "x")).isEmpty();
        assertThat(MetaContext.exists()).isFalse();
    }

    @Test
    void findByCode_returnsViewIgnoringEnabledState() {
        when(dds.list(eq("iot_rule"), any(DynamicQueryRequest.class)))
                .thenReturn(page(ruleRow("R-DIS", "TENANT", null, "MINOR", false)));
        Optional<RuleView> got = accessor.findByCode(1L, "R-DIS");
        assertThat(got).isPresent();
        assertThat(got.get().enabled()).isFalse();
    }
}
