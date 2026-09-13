package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.event.config.TenantAwareTaskDecorator;
import com.auraboot.framework.meta.dto.ExportResult;
import com.auraboot.framework.meta.entity.ExportTask;
import com.auraboot.framework.meta.mapper.ExportTaskMapper;
import com.auraboot.framework.meta.mapper.NamedQueryMapper;
import com.auraboot.framework.meta.service.NamedQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExportTaskWorkerTest {
    @AfterEach
    void clearContext() { MetaContext.clear(); }

    @Test
    void workerReusesQueryServiceWithCapturedIdentityAndPersistedParameters() throws Exception {
        MetaContext.setContext(1L, 100L, "owner", "owner", Set.of(7L));
        ExportTaskMapper mapper = mock(ExportTaskMapper.class);
        NamedQueryService queries = mock(NamedQueryService.class);
        ObjectMapper json = new ObjectMapper();
        ExportTaskService service = new ExportTaskService(mapper, mock(NamedQueryMapper.class), queries, json);
        AtomicReference<Runnable> queued = new AtomicReference<>();
        ReflectionTestUtils.setField(service, "exportTaskExecutor", (Executor) command ->
                queued.set(new TenantAwareTaskDecorator().decorate(command)));
        ExportTask task = new ExportTask();
        task.setId(8L);
        task.setPid("worker-test");
        task.setTenantId(1L);
        task.setCreatedBy(100L);
        task.setQueryCode("governed");
        task.setStatus("pending");
        task.setRequestParams(json.valueToTree(Map.of("request", Map.of("format", "CSV", "parameters", Map.of("marker", "unique")))));
        when(mapper.selectById(8L)).thenReturn(task);
        when(queries.exportData(eq("governed"), any())).thenAnswer(call -> {
            assertEquals(100L, MetaContext.getCurrentUserId());
            assertEquals(Set.of(7L), MetaContext.snapshot().roleIds());
            com.auraboot.framework.meta.dto.NamedQueryDataExportRequest request = call.getArgument(1);
            assertEquals("unique", request.getParameters().get("marker"));
            return ExportResult.builder().success(false).errorMessage("Denied source").build();
        });
        service.processExportAsync(8L, 1L);
        verifyNoInteractions(queries);
        assertEquals("pending", task.getStatus());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                queued.get().run();
                assertNull(MetaContext.snapshot());
            } catch (Throwable t) { failure.set(t); }
        });
        worker.start();
        worker.join(5000);
        assertFalse(worker.isAlive());
        assertNull(failure.get());
        verify(queries).exportData(eq("governed"), any());
        assertEquals("failed", task.getStatus());
        assertNull(task.getFileKey());
        assertEquals(100L, MetaContext.getCurrentUserId());
    }
}
