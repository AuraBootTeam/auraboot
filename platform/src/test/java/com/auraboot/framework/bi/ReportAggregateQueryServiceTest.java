package com.auraboot.framework.bi;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bi.service.ReportAggregateQueryService;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.dto.AggregateQueryRequest;
import com.auraboot.framework.meta.dto.AggregateQueryResponse;
import com.auraboot.framework.meta.service.AggregateQueryService;
import com.auraboot.framework.meta.service.ChartRuntimeFilterResolver;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.service.UserPermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportAggregateQueryServiceTest {
    private final AggregateQueryService queries = mock(AggregateQueryService.class);
    private final ChartRuntimeFilterResolver filters = mock(ChartRuntimeFilterResolver.class);
    private final UserPermissionService permissions = mock(UserPermissionService.class);
    private final ReportAggregateQueryService service = new ReportAggregateQueryService(queries, filters, permissions);

    @BeforeEach void context() { MetaContext.setContext(7L, 99L, "actor", "tester"); }
    @AfterEach void clear() { MetaContext.clear(); }
    private AggregateQueryRequest query() {
        var query = new AggregateQueryRequest();
        query.setType("aggregate");
        query.setModelCode("orders");
        return query;
    }
    @Test void deniesBeforeResolvingFiltersOrQuerying() {
        assertThatThrownBy(() -> service.execute(query())).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(filters, queries);
    }
    @Test void anonymousCannotQuery() {
        MetaContext.clear();
        assertThatThrownBy(() -> service.execute(query())).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(permissions, filters, queries);
    }
    @Test void preservesSemanticQueryAndRowsWithSharedDefaultLimit() {
        var query = query();
        query.setSemanticModelCode("sales");
        when(permissions.hasPermission(99L, MetaPermission.META_SEMANTIC_USE)).thenReturn(true);
        var response = new AggregateQueryResponse();
        response.setRows(List.of(Map.of("region", "East", "revenue", 120), Map.of("region", "West", "revenue", 80)));
        when(queries.execute(query)).thenReturn(response);
        assertThat(service.execute(query)).isSameAs(response);
        assertThat(query.getLimit()).isEqualTo(100);
        assertThat(query.getSemanticModelCode()).isEqualTo("sales");
        var order = inOrder(filters, queries);
        order.verify(filters).resolve(query);
        order.verify(queries).execute(query);
        verify(permissions).hasPermission(99L, MetaPermission.META_SEMANTIC_USE);
    }
    @Test void rejectsOutOfRangeLimitBeforeAccessOrQuery() {
        for (int limit : new int[]{0, -1, 1001}) {
            var query = query(); query.setLimit(limit);
            assertThatThrownBy(() -> service.execute(query)).isInstanceOf(ValidationException.class);
        }
        verifyNoInteractions(permissions, filters, queries);
    }
    @Test void underlyingMetricDenialPropagates() {
        var query = query();
        when(permissions.hasPermission(99L, "model.orders.read")).thenReturn(true);
        when(queries.execute(query)).thenThrow(new AccessDeniedException("Metric access denied"));
        assertThatThrownBy(() -> service.execute(query)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void missingEnvelopeIsAnErrorButEmptyRowsAreValid() {
        var query = query();
        when(permissions.hasPermission(99L, "model.orders.read")).thenReturn(true);
        assertThatThrownBy(() -> service.execute(query)).isInstanceOf(IllegalStateException.class);
        var empty = new AggregateQueryResponse(); empty.setRows(List.of());
        when(queries.execute(query)).thenReturn(empty);
        assertThat(service.execute(query).getRows()).isEmpty();
    }
}
