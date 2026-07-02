package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.ExportTask;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.ExportTaskMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Tenant-ownership tests for async export task status/download.
 *
 * <p>Security regression: {@code ab_export_task} is excluded from the tenant line
 * interceptor and resolved by a global pid, and the download/status endpoints did not
 * re-assert tenant ownership → a tenant could download another tenant's export file /
 * read its status via the (ULID) taskPid. Now cross-tenant resolves to not-found.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExportTaskService tenant-ownership guard")
class ExportTaskServiceTenantAuthzTest {

    @Mock
    private ExportTaskMapper exportTaskMapper;

    @InjectMocks
    private ExportTaskService service;

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    private ExportTask task(Long tenantId, String fileKey) {
        ExportTask t = new ExportTask();
        t.setTenantId(tenantId);
        t.setFileKey(fileKey);
        t.setStatus("completed");
        return t;
    }

    @Test
    @DisplayName("getFileKey returns null for another tenant's task")
    void getFileKey_crossTenant_null() {
        MetaContext.setContext(1L, 100L, "u", "user");
        when(exportTaskMapper.findByPid("t-other")).thenReturn(task(2L, "/tmp/other.xlsx"));
        assertNull(service.getFileKey("t-other"));
    }

    @Test
    @DisplayName("getFileKey returns the key for the caller's own tenant task")
    void getFileKey_sameTenant_ok() {
        MetaContext.setContext(1L, 100L, "u", "user");
        when(exportTaskMapper.findByPid("t-own")).thenReturn(task(1L, "/tmp/own.xlsx"));
        assertEquals("/tmp/own.xlsx", service.getFileKey("t-own"));
    }

    @Test
    @DisplayName("getTaskStatus throws not-found for another tenant's task")
    void getTaskStatus_crossTenant_notFound() {
        MetaContext.setContext(1L, 100L, "u", "user");
        lenient().when(exportTaskMapper.findByPid("t-other")).thenReturn(task(2L, "/tmp/other.xlsx"));
        assertThrows(MetaServiceException.class, () -> service.getTaskStatus("t-other"));
    }
}
