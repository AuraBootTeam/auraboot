package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.plugin.extension.BackgroundDataAccessor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantProjectionAccessorImplTest {

    @Test
    void bindsBoundedProjectionPagingWithoutLoadingAnUnboundedQuery() {
        BackgroundDataAccessor delegate = mock(BackgroundDataAccessor.class);
        var accessor = new TenantProjectionAccessorImpl(41L, delegate);
        var query = new com.auraboot.framework.plugin.extension.DataPageQuery(
                Map.of("owner_pid", "supplier-1"), Map.of(), List.of(), 2, 20);
        var page = new com.auraboot.framework.plugin.extension.DataPage(
                List.of(Map.of("pid", "quote-21")), 41, 2, 20);
        when(delegate.queryPage(41L, "quote", query)).thenReturn(page);
        assertThat(accessor.queryPage("quote", query)).isEqualTo(page);
        verify(delegate).queryPage(41L, "quote", query);
        org.mockito.Mockito.verifyNoMoreInteractions(delegate);
    }

    @Test
    void bindsEveryReadToTheConstructedTenant() {
        BackgroundDataAccessor delegate = mock(BackgroundDataAccessor.class);
        TenantProjectionAccessorImpl accessor = new TenantProjectionAccessorImpl(41L, delegate);
        Map<String, Object> record = Map.of("pid", "CLS-1");
        List<Map<String, Object>> rows = List.of(record);
        when(delegate.getById(41L, "xy_classroom", "CLS-1")).thenReturn(record);
        when(delegate.query(41L, "xy_classroom", Map.of("status", "active"))).thenReturn(rows);
        when(delegate.queryIn(41L, "xy_student", "pid", List.of("STU-1"))).thenReturn(rows);

        assertThat(accessor.getById("xy_classroom", "CLS-1")).isEqualTo(record);
        assertThat(accessor.query("xy_classroom", Map.of("status", "active"))).isEqualTo(rows);
        assertThat(accessor.queryIn("xy_student", "pid", List.of("STU-1"))).isEqualTo(rows);

        verify(delegate).getById(41L, "xy_classroom", "CLS-1");
        verify(delegate).query(41L, "xy_classroom", Map.of("status", "active"));
        verify(delegate).queryIn(41L, "xy_student", "pid", List.of("STU-1"));
    }
}
