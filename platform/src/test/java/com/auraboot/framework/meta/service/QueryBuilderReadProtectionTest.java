package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.QueryBuilderDTO;
import com.auraboot.framework.meta.entity.NamedQuery;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.impl.NamedQueryFieldProtection;
import com.auraboot.framework.permission.engine.model.FieldPermissionSet;
import com.auraboot.framework.permission.service.FieldPermissionService;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QueryBuilderReadProtectionTest {
    private final FieldPermissionService fields = mock(FieldPermissionService.class);
    private final NamedQueryFieldProtection protection = mock(NamedQueryFieldProtection.class);
    private final DynamicDataMapper mapper = mock(DynamicDataMapper.class);
    private final QueryBuilderReadProtection service = new QueryBuilderReadProtection(fields, protection, mapper);
    private final Map<String, String> columns = Map.of("name", "customer_name", "secret", "private_phone");
    private final NamedQueryFieldProtection.Plan clearPlan = new NamedQueryFieldProtection.Plan(null, List.of(), Map.of());

    @BeforeEach void context() { MetaContext.setContext(7L, 99L, "actor", "tester"); }
    @AfterEach void clear() { MetaContext.clear(); }
    private QueryBuilderDTO query() { var dto = new QueryBuilderDTO(); dto.setModelCode("customers"); return dto; }
    private List<Consumer<QueryBuilderDTO>> secretUses() {
        return List.of(
            dto -> dto.setFields(List.of("secret")),
            dto -> { var filter = new QueryBuilderDTO.FilterCondition(); filter.setFieldName("secret"); dto.setFilters(List.of(filter)); },
            dto -> dto.setSortField("secret"),
            dto -> dto.setGroupBy(List.of("secret")),
            dto -> { var metric = new QueryBuilderDTO.AggregationConfig(); metric.setFieldCode("secret"); metric.setFunction("COUNT"); dto.setAggregations(List.of(metric)); }
        );
    }
    @Test void hiddenFieldsCannotBeProjectedFilteredSortedGroupedOrAggregated() {
        when(fields.getFieldPermissions(99L, "customers"))
            .thenReturn(new FieldPermissionSet(Set.of("name"), Set.of(), Set.of("secret")));
        for (var use : secretUses()) {
            var dto = query(); use.accept(dto);
            assertThatThrownBy(() -> service.prepare(dto, columns, "mt_customers"))
                .isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(protection, mapper);
    }
    @Test void anotherFieldCodeCannotExposeThePhysicalColumnOfAHiddenField() {
        when(fields.getFieldPermissions(99L, "customers"))
            .thenReturn(new FieldPermissionSet(Set.of("name", "alias"), Set.of(), Set.of("secret")));
        var aliases = Map.of("name", "customer_name", "secret", "private_phone", "alias", "private_phone");
        assertThat(service.visibleColumns("customers", aliases)).containsExactlyEntriesOf(Map.of("name", "customer_name"));
        var dto = query(); dto.setFields(List.of("alias"));
        assertThatThrownBy(() -> service.prepare(dto, aliases, "mt_customers"))
            .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(protection, mapper);
    }
    @Test void defaultProjectionOmitsHiddenColumnsBeforeSharedPlanning() {
        when(fields.getFieldPermissions(99L, "customers"))
            .thenReturn(new FieldPermissionSet(Set.of("name"), Set.of(), Set.of("secret")));
        when(protection.prepare(any(), anyList(), eq("list"))).thenReturn(clearPlan);
        var plan = service.prepare(query(), columns, "mt_customers");
        assertThat(plan.visibleColumns()).containsExactlyEntriesOf(Map.of("name", "customer_name"));
        var source = ArgumentCaptor.forClass(NamedQuery.class);
        verify(protection).prepare(source.capture(), anyList(), eq("list"));
        assertThat(source.getValue().getFromSql()).isEqualTo("SELECT customer_name FROM mt_customers");
    }
    @Test void maskingAllowsProjectionButRejectsPredicateAndAggregateInference() {
        when(fields.getFieldPermissions(99L, "customers")).thenReturn(FieldPermissionSet.allAllowed(columns.keySet()));
        var masked = new NamedQueryFieldProtection.Plan(null,
            List.of(new NamedQueryFieldProtection.Protection(Map.of("private_phone", "secret"), List.of(), List.of())), Map.of());
        when(protection.prepare(any(), anyList(), eq("list"))).thenReturn(masked);
        var projection = query(); projection.setFields(List.of("secret"));
        assertThat(service.prepare(projection, columns, "mt_customers").source()).isSameAs(masked);
        for (var use : secretUses().subList(1, 5)) {
            var dto = query(); use.accept(dto);
            assertThatThrownBy(() -> service.prepare(dto, columns, "mt_customers"))
                .isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(mapper);
    }
    @Test void sourceRewritePrecedesDatabaseReadAndOutputMasking() {
        var plan = new QueryBuilderReadProtection.Plan(columns, clearPlan);
        var params = Map.<String, Object>of("filter_0", "A");
        var raw = List.<Map<String, Object>>of(Map.of("private_phone", "123456789"));
        var masked = List.<Map<String, Object>>of(Map.of("private_phone", "123****89"));
        when(protection.rewrite(clearPlan, "SELECT private_phone FROM mt_customers")).thenReturn("scoped SQL");
        when(mapper.selectByQueryWithoutTenant("scoped SQL", params)).thenReturn(raw);
        when(protection.apply(clearPlan, raw)).thenReturn(masked);
        assertThat(service.execute(plan, "SELECT private_phone FROM mt_customers", params)).isEqualTo(masked);
        var order = inOrder(protection, mapper);
        order.verify(protection).rewrite(clearPlan, "SELECT private_phone FROM mt_customers");
        order.verify(mapper).selectByQueryWithoutTenant("scoped SQL", params);
        order.verify(protection).apply(clearPlan, raw);
    }
    @Test void missingIdentityAndFieldEvaluationFailureDoNotReachTheDatabase() {
        MetaContext.clear();
        assertThatThrownBy(() -> service.prepare(query(), columns, "mt_customers"))
            .isInstanceOf(AccessDeniedException.class);
        MetaContext.setContext(7L, 99L, "actor", "tester");
        when(fields.getFieldPermissions(99L, "customers")).thenThrow(new IllegalStateException("field evaluation failed"));
        assertThatThrownBy(() -> service.prepare(query(), columns, "mt_customers"))
            .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(protection, mapper);
    }
    @Test void comparisonsRejectHiddenPhysicalAliasesBeforeAnySourceRead() {
        when(fields.getFieldPermissions(99L, "customers"))
                .thenReturn(new FieldPermissionSet(Set.of(), Set.of(), Set.of("secret")));
        var aliased = new LinkedHashMap<>(columns);
        aliased.put("private_phone", "private_phone");
        assertThatThrownBy(() -> service.prepareComparison("customers", List.of("private_phone"),
                aliased, "mt_customers")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(protection, mapper);
    }
    @Test void comparisonsRejectMaskedValuesInsteadOfMatchingRedactedOutput() {
        when(fields.getFieldPermissions(99L, "customers"))
                .thenReturn(new FieldPermissionSet(Set.of(), Set.of(), Set.of()));
        var masked = new NamedQueryFieldProtection.Plan(null,
                List.of(new NamedQueryFieldProtection.Protection(Map.of("private_phone", "secret"), List.of(), List.of())), Map.of());
        when(protection.prepare(any(NamedQuery.class), anyList(), eq("list"))).thenReturn(masked);
        assertThatThrownBy(() -> service.prepareComparison("customers", List.of("secret"),
                columns, "mt_customers")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(mapper);
    }
    @Test void comparisonsCannotNameUnregisteredColumns() {
        assertThatThrownBy(() -> service.prepareComparison("customers", List.of("unregistered"),
                columns, "mt_customers")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(fields, protection, mapper);
    }

    @Test void scalarCountUsesRewrittenScopeAndOriginalBoundParameters() {
        var plan = new QueryBuilderReadProtection.Plan(columns, clearPlan);
        var params = Map.<String, Object>of("regex", "^secret$");
        when(protection.rewrite(clearPlan, "count SQL")).thenReturn("scoped count SQL");
        when(mapper.countByQueryWithoutTenant("scoped count SQL", params)).thenReturn(4L);
        assertThat(service.executeCount(plan, "count SQL", params)).isEqualTo(4L);
        var order = inOrder(protection, mapper);
        order.verify(protection).rewrite(clearPlan, "count SQL");
        order.verify(mapper).countByQueryWithoutTenant("scoped count SQL", params);
        verify(protection, never()).apply(any(), anyList());
    }
    @Test void scalarCountCannotTreatMissingOrNegativeResultAsZero() {
        var plan = new QueryBuilderReadProtection.Plan(columns, clearPlan);
        when(protection.rewrite(clearPlan, "count SQL")).thenReturn("scoped count SQL");
        when(mapper.countByQueryWithoutTenant("scoped count SQL", Map.of())).thenReturn(null, -1L);
        for (int i = 0; i < 2; i++) assertThatThrownBy(() -> service.executeCount(plan, "count SQL", Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }

}
