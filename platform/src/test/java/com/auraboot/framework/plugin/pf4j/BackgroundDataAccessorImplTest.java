package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.service.DynamicDataService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackgroundDataAccessorImplTest {

    private DynamicDataService dds;
    private BackgroundDataAccessorImpl accessor;

    @BeforeEach
    void setUp() {
        dds = mock(DynamicDataService.class);
        accessor = new BackgroundDataAccessorImpl(dds);
        MetaContext.clear();
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void create_bindsTenantContextDuringDelegate_thenClears() {
        when(dds.create(eq("cr_crawl_url"), any())).thenAnswer(inv -> {
            assertThat(MetaContext.getCurrentTenantId()).isEqualTo(42L);
            return Map.of("id", "x1");
        });

        Map<String, Object> result = accessor.create(42L, "cr_crawl_url", Map.of("k", "v"));

        assertThat(result).containsEntry("id", "x1");
        assertThat(MetaContext.exists()).isFalse();
    }

    @Test
    void tryCreate_returnsResult_onSuccess() {
        when(dds.create(eq("cr_crawl_url"), any())).thenReturn(Map.of("id", "x1"));

        Optional<Map<String, Object>> result = accessor.tryCreate(42L, "cr_crawl_url", Map.of("k", "v"));

        assertThat(result).isPresent().get().asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.MAP).containsEntry("id", "x1");
    }

    @Test
    void tryCreate_returnsEmpty_onDuplicateKey() {
        when(dds.create(anyString(), any()))
                .thenThrow(new DuplicateKeyException("unique constraint failed"));

        Optional<Map<String, Object>> result = accessor.tryCreate(42L, "cr_crawl_url", Map.of("k", "v"));

        assertThat(result).isEmpty();
        assertThat(MetaContext.exists()).isFalse();
    }

    @Test
    void tryCreate_letsOtherExceptionsPropagate() {
        when(dds.create(anyString(), any())).thenThrow(new IllegalStateException("validation"));

        assertThatThrownBy(() -> accessor.tryCreate(42L, "cr_crawl_url", Map.of("k", "v")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("validation");
        assertThat(MetaContext.exists()).isFalse();
    }

    @Test
    void context_isRestoredEvenOnException() {
        MetaContext.setCurrentTenantId(7L);
        when(dds.create(anyString(), any())).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> accessor.create(42L, "cr_crawl_url", Map.of()))
                .isInstanceOf(RuntimeException.class);

        // Prior tenant context is restored.
        assertThat(MetaContext.getCurrentTenantId()).isEqualTo(7L);
    }

    @Test
    void query_buildsEqualityConditions_andUnwrapsRecords() {
        PaginationResult<Map<String, Object>> page = new PaginationResult<>();
        page.setRecords(List.of(Map.of("id", "x1")));
        when(dds.list(eq("cr_crawl_url"), any(DynamicQueryRequest.class))).thenReturn(page);

        List<Map<String, Object>> result = accessor.query(42L, "cr_crawl_url",
                Map.of("cr_cu_url_hash", "abc123"));

        ArgumentCaptor<DynamicQueryRequest> captor = ArgumentCaptor.forClass(DynamicQueryRequest.class);
        verify(dds).list(eq("cr_crawl_url"), captor.capture());
        assertThat(captor.getValue().getConditions()).hasSize(1);
        assertThat(captor.getValue().getConditions().get(0).getFieldName()).isEqualTo("cr_cu_url_hash");
        assertThat(captor.getValue().getConditions().get(0).getValue()).isEqualTo("abc123");
        assertThat(result).hasSize(1);
    }

    @Test
    void query_withNullFilters_yieldsEmptyConditions() {
        PaginationResult<Map<String, Object>> page = new PaginationResult<>();
        page.setRecords(List.of());
        when(dds.list(eq("cr_crawl_url"), any(DynamicQueryRequest.class))).thenReturn(page);

        accessor.query(42L, "cr_crawl_url", null);

        ArgumentCaptor<DynamicQueryRequest> captor = ArgumentCaptor.forClass(DynamicQueryRequest.class);
        verify(dds).list(eq("cr_crawl_url"), captor.capture());
        assertThat(captor.getValue().getConditions()).isEmpty();
    }

    @Test
    void delete_returnsNullSafely_andClearsContext() {
        doThrow(new RuntimeException("no")).when(dds).delete(anyString(), anyString());

        assertThatThrownBy(() -> accessor.delete(42L, "cr_crawl_url", "id1"))
                .isInstanceOf(RuntimeException.class);
        assertThat(MetaContext.exists()).isFalse();

        // happy path
        org.mockito.Mockito.reset(dds);
        accessor.delete(42L, "cr_crawl_url", "id1");
        verify(dds, times(1)).delete("cr_crawl_url", "id1");
    }
}
