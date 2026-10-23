package com.auraboot.framework.meta.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.service.NamedQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BatchQueryControllerTest {
    @AfterEach void clear() { MetaContext.clear(); }

    @Test void workersReceiveIdentityAndEnvironmentAndKeepFailuresIsolated() {
        var queries = mock(NamedQueryService.class);
        long callerThread = Thread.currentThread().threadId();
        when(queries.executeQuery(eq("visible"), any())).thenAnswer(inv -> {
            assertThat(Thread.currentThread().threadId()).isNotEqualTo(callerThread);
            assertThat(MetaContext.getCurrentTenantId()).isEqualTo(91L);
            assertThat(MetaContext.getCurrentMemberId()).isEqualTo(93L);
            assertThat(MetaContext.getCurrentEnvironmentId()).isEqualTo(94L);
            return PaginationResult.of(List.of(Map.of("owner", MetaContext.getCurrentUserId())), 1L, 1, 200);
        });
        when(queries.executeQuery(eq("denied"), any())).thenThrow(new IllegalArgumentException("denied"));
        MetaContext.setContext(91L, 92L, "owner", "owner", Set.of(7L));
        MetaContext.setMemberId(93L);
        MetaContext.setEnvironmentId(94L);
        var request = new BatchQueryController.BatchRequest();
        var allowed = new BatchQueryController.QueryItem();
        allowed.setId("ok"); allowed.setDatasourceId("nq:visible");
        var denied = new BatchQueryController.QueryItem();
        denied.setId("bad"); denied.setDatasourceId("nq:denied");
        request.setQueries(List.of(allowed, denied));
        var result = new BatchQueryController(queries).batchQuery(request).getData();
        assertThat(result.get("ok")).isInstanceOf(PaginationResult.class);
        assertThat(((PaginationResult<?>) result.get("ok")).getRecords()).hasSize(1);
        assertThat(result.get("bad")).isEqualTo(Map.of("error", "denied"));
        assertThat(MetaContext.getCurrentTenantId()).isEqualTo(91L);
    }
}
