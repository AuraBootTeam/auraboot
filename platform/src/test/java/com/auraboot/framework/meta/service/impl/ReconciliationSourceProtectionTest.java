package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.ReconciliationRunRequest;
import com.auraboot.framework.meta.entity.ReconciliationProfile;
import com.auraboot.framework.meta.mapper.ReconciliationItemMapper;
import com.auraboot.framework.meta.mapper.ReconciliationProfileMapper;
import com.auraboot.framework.meta.mapper.ReconciliationRunMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.QueryBuilderReadProtection;
import com.auraboot.framework.permission.engine.PermissionEvaluator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Hermetic consumer-seam checks; real source scopes remain an integration obligation. */
class ReconciliationSourceProtectionTest {
    private final MetaModelService models = mock(MetaModelService.class);
    private final QueryBuilderReadProtection protection = mock(QueryBuilderReadProtection.class);
    private final PermissionEvaluator permissions = mock(PermissionEvaluator.class);
    private final ReconciliationProfileMapper profiles = mock(ReconciliationProfileMapper.class);
    private final ReconciliationRunMapper runs = mock(ReconciliationRunMapper.class);
    private final ReconciliationItemMapper items = mock(ReconciliationItemMapper.class);
    private final ReconciliationService service = new ReconciliationService(
            profiles, runs, items, protection, permissions, models,
            mock(PlatformTransactionManager.class));
    private final QueryBuilderReadProtection.Plan plan = new QueryBuilderReadProtection.Plan(Map.of(),
            new NamedQueryFieldProtection.Plan(null, List.of(), Map.of()));

    @BeforeEach void setup() {
        MetaContext.setContext(7L, 99L, "actor", "tester");
        MetaContext.setMemberId(77L);
        var fields = List.of(
                FieldDefinition.builder().code("amount").columnName("amount_col").build(),
                FieldDefinition.builder().code("date").columnName("date_col").build(),
                FieldDefinition.builder().code("reference").columnName("ref_col").build());
        when(models.getModelDefinition("invoices")).thenReturn(Optional.of(
                ModelDefinition.builder().code("invoices").fields(fields).softDelete(true).build()));
        when(models.getTableName("invoices")).thenReturn("mt_invoices");
        when(permissions.canAction(77L, "invoices", "read")).thenReturn(true);
        when(protection.prepareComparison(eq("invoices"), anyCollection(), anyMap(), eq("mt_invoices")))
                .thenReturn(plan);
    }
    @AfterEach void clear() { MetaContext.clear(); }
    private List<ReconciliationService.RecordEntry> load(String amount) {
        return ReflectionTestUtils.invokeMethod(service, "loadRecords", 7L, "invoices", amount,
                "date", "reference", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
    }
    @Test void sourceReadDenialNeverReachesProtectedExecution() {
        when(permissions.canAction(77L, "invoices", "read")).thenReturn(false);
        assertThatThrownBy(() -> load("amount")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(protection);
    }
    @Test void hiddenOrMaskedComparisonDenialNeverExecutesSql() {
        when(protection.prepareComparison(eq("invoices"), anyCollection(), anyMap(), eq("mt_invoices")))
                .thenThrow(new AccessDeniedException("Protected comparison"));
        assertThatThrownBy(() -> load("amount")).isInstanceOf(AccessDeniedException.class);
        verify(protection, never()).execute(any(), anyString(), anyMap());
    }
    @Test void registeredPhysicalAliasAndDatesUseOnlyProtectedBoundQuery() {
        when(protection.execute(eq(plan), anyString(), anyMap())).thenReturn(List.of(Map.of(
                "id", 123L, "amount_col", new BigDecimal("12.34"),
                "date_col", LocalDate.of(2026, 1, 7), "ref_col", "INV-A")));
        var result = load("amount_col");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).recordId).isEqualTo(123L);
        assertThat(result.get(0).amount).isEqualByComparingTo("12.34");
        assertThat(result.get(0).date).isEqualTo(LocalDate.of(2026, 1, 7));
        assertThat(result.get(0).ref).isEqualTo("INV-A");
        var sql = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> parameters = ArgumentCaptor.forClass(Map.class);
        verify(protection).execute(eq(plan), sql.capture(), parameters.capture());
        assertThat(sql.getValue()).contains("SELECT id, amount_col, date_col, ref_col FROM mt_invoices",
                "tenant_id = #{params.tenantId}", "deleted_flag = FALSE",
                "date_col >= #{params.periodStart}", "date_col <= #{params.periodEnd}", "LIMIT 50000")
                .doesNotContain("2026-01", "queryList");
        assertThat(parameters.getValue()).containsEntry("tenantId", 7L)
                .containsEntry("periodStart", LocalDate.of(2026, 1, 1))
                .containsEntry("periodEnd", LocalDate.of(2026, 1, 31));
        verify(protection).prepareComparison(eq("invoices"), eq(List.of("id", "amount_col", "date", "reference")),
                argThat(columns -> "amount_col".equals(columns.get("amount_col"))
                        && "amount_col".equals(columns.get("amount"))), eq("mt_invoices"));
    }
    @Test void publicRunPreservesSourcePermissionDenialWithoutCreatingItems() {
        var profile = new ReconciliationProfile();
        profile.setId(10L);
        profile.setTenantId(7L);
        profile.setProfileCode("test_profile");
        profile.setEnabled(true);
        profile.setSourceAModel("invoices");
        profile.setSourceAAmountField("amount");
        when(profiles.selectById(10L)).thenReturn(profile);
        when(permissions.canAction(77L, "invoices", "read")).thenReturn(false);
        var request = new ReconciliationRunRequest();
        request.setProfileId(10L);
        assertThatThrownBy(() -> service.startReconciliation(request))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("reconciliation source");
        verifyNoInteractions(items, protection);
        verify(runs, never()).updateById(any(com.auraboot.framework.meta.entity.ReconciliationRun.class));
    }

}
