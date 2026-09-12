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
        t.setCreatedBy(100L);
        t.setFileKey(fileKey);
        t.setStatus("completed");
        t.setExpiresAt(java.time.Instant.now().plusSeconds(3600));
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

    @Test
    void sameTenantPeerCannotReadStatusOrFile() {
        MetaContext.setContext(1L, 200L, "peer", "user");
        when(exportTaskMapper.findByPid("private-task")).thenReturn(task(1L, "/tmp/private.xlsx"));
        assertThrows(MetaServiceException.class, () -> service.getTaskStatus("private-task"));
        assertNull(service.getFileKey("private-task"));
    }

    @Test
    void ownerCanReadStatus() {
        MetaContext.setContext(1L, 100L, "owner", "user");
        when(exportTaskMapper.findByPid("own-task")).thenReturn(task(1L, "/tmp/own.xlsx"));
        assertEquals("completed", service.getTaskStatus("own-task").getStatus());
    }

    @Test
    void recentTasksRequireBothTenantAndUserBeforeQuerying() {
        MetaContext.clear();
        assertThrows(IllegalStateException.class, () -> service.getRecentTasks("query", 10));
        org.mockito.Mockito.verifyNoInteractions(exportTaskMapper);
    }

    @Test
    void recentTasksPassOwnerScopeToDatabaseBeforeLimit() {
        MetaContext.setContext(1L, 100L, "owner", "user");
        when(exportTaskMapper.findByQueryCode("query", 1L, 100L, 2))
                .thenReturn(java.util.List.of(task(1L, "/tmp/own.xlsx")));
        assertEquals(1, service.getRecentTasks("query", 2).size());
        org.mockito.Mockito.verify(exportTaskMapper).findByQueryCode("query", 1L, 100L, 2);
    }
    @Test
    void expiredArtifactIsUnavailableBeforeScheduledCleanup() {
        MetaContext.setContext(1L, 100L, "owner", "user");
        ExportTask expired = task(1L, "/tmp/expired.xlsx");
        expired.setExpiresAt(java.time.Instant.now().minusSeconds(1));
        when(exportTaskMapper.findByPid("expired")).thenReturn(expired);
        assertNull(service.getFileKey("expired"));
        assertNull(service.getTaskStatus("expired").getDownloadUrl());
    }
}
