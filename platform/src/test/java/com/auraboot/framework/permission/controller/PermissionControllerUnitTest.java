package com.auraboot.framework.permission.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.dto.PermissionAuditLogDTO;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.dto.PermissionReferenceDTO;
import com.auraboot.framework.permission.entity.PermissionAuditLog;
import com.auraboot.framework.permission.service.PermissionAuditRecordPidResolver;
import com.auraboot.framework.permission.service.PermissionAuditService;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.plugin.service.PluginResourceTracker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * PermissionController Unit Test
 * 
 * Tests the new permission API endpoints.
 * 
 * @author AuraBoot Platform
 * @since V4
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PermissionController Unit Tests")
class PermissionControllerUnitTest {
    
    @Mock
    private PermissionService permissionService;

    @Mock
    private PluginResourceTracker pluginResourceTracker;

    @Mock
    private PermissionAuditService permissionAuditService;

    @Mock
    private PermissionAuditRecordPidResolver auditRecordPidResolver;
    
    @InjectMocks
    private PermissionController permissionController;

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }
    
    @Test
    @DisplayName("GET /api/permissions/model/{modelCode} - Should return model permissions")
    void testGetModelPermissions() {
        // Given
        String modelCode = "user_model";
        PermissionDTO permission1 = new PermissionDTO();
        permission1.setId(1L);
        permission1.setCode("MODEL.user_model.create");
        permission1.setName("Create User Model");
        
        PermissionDTO permission2 = new PermissionDTO();
        permission2.setId(2L);
        permission2.setCode("MODEL.user_model.read");
        permission2.setName("Read User Model");
        
        when(permissionService.findByResource("model", modelCode))
            .thenReturn(List.of(permission1, permission2));
        
        // When
        var response = permissionController.getModelPermissions(modelCode);
        
        // Then
        assertNotNull(response);
        assertNotNull(response.getData());
        assertEquals(2, response.getData().size());
        assertEquals("MODEL.user_model.create", response.getData().get(0).getCode());
        assertEquals("MODEL.user_model.read", response.getData().get(1).getCode());
        
        verify(permissionService).findByResource("model", modelCode);
    }
    
    @Test
    @DisplayName("GET /api/permissions/role/{roleId} - Should return role permissions")
    void testGetRolePermissions() {
        // Given
        Long roleId = 1L;
        PermissionDTO permission = new PermissionDTO();
        permission.setId(1L);
        permission.setCode("MODEL.user_model.create");
        
        when(permissionService.findRolePermissions(roleId))
            .thenReturn(List.of(permission));
        
        // When
        var response = permissionController.getRolePermissions(roleId);
        
        // Then
        assertNotNull(response);
        assertNotNull(response.getData());
        assertEquals(1, response.getData().size());
        
        verify(permissionService).findRolePermissions(roleId);
    }
    
    @Test
    @DisplayName("GET /api/permissions/{permissionId}/references - Should return permission references")
    void testGetPermissionReferences() {
        // Given
        Long permissionId = 1L;
        PermissionReferenceDTO reference = new PermissionReferenceDTO();
        reference.setId(1L);
        reference.setRoleId(2L);
        reference.setRoleName("Admin");
        reference.setGrantType("grant");
        
        when(permissionService.findReferences(permissionId))
            .thenReturn(List.of(reference));
        
        // When
        var response = permissionController.getPermissionReferences(permissionId);
        
        // Then
        assertNotNull(response);
        assertNotNull(response.getData());
        assertEquals(1, response.getData().size());
        assertEquals("Admin", response.getData().get(0).getRoleName());
        
        verify(permissionService).findReferences(permissionId);
    }

    @Test
    @DisplayName("GET /api/permissions/audit - Should return public record PID without internal record ID")
    void testGetAuditLogReturnsPublicRecordPid() throws Exception {
        MetaContext.setCurrentTenantId(100L);
        PermissionAuditLog auditLog = new PermissionAuditLog();
        auditLog.setId(7L);
        auditLog.setTenantId(100L);
        auditLog.setMemberId(5L);
        auditLog.setResourceCode("wd_leave_request");
        auditLog.setActionCode("view");
        auditLog.setRecordId(999L);
        auditLog.setResult(false);
        auditLog.setReason("record.data.salary is not available in permission ABAC fact catalog");
        auditLog.setEvaluationTrace(List.of(Map.of(
                "evaluatorName", "Policy",
                "verdict", "DENY",
                "reason", "record.data.salary is not available in permission ABAC fact catalog")));
        auditLog.setCreatedAt(Instant.parse("2026-07-13T10:00:00Z"));

        when(permissionAuditService.getLogsByResource(100L, "wd_leave_request", 25))
                .thenReturn(List.of(auditLog));
        when(auditRecordPidResolver.resolve(auditLog)).thenReturn("01PUBLICREC");

        var response = permissionController.getAuditLog(null, null, "wd_leave_request", 25);

        assertNotNull(response);
        assertNotNull(response.getData());
        assertEquals(1, response.getData().size());
        PermissionAuditLogDTO dto = response.getData().get(0);
        assertEquals("01PUBLICREC", dto.getRecordPid());
        assertEquals("wd_leave_request", dto.getResourceCode());
        assertEquals(false, dto.getResult());
        assertEquals("record.data.salary is not available in permission ABAC fact catalog", dto.getReason());
        assertEquals("Policy", ((Map<?, ?>) dto.getEvaluationTrace().get(0)).get("evaluatorName"));
        assertEquals("DENY", ((Map<?, ?>) dto.getEvaluationTrace().get(0)).get("verdict"));

        String json = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .writeValueAsString(dto);
        assertFalse(json.contains("recordId"));
        assertFalse(json.contains("999"));
        assertTrue(json.contains("01PUBLICREC"));
        verify(permissionAuditService).getLogsByResource(100L, "wd_leave_request", 25);
        verify(auditRecordPidResolver).resolve(auditLog);
    }

    @Test
    @DisplayName("GET /api/permissions/audit - Should filter by Rule Center trace ID")
    void testGetAuditLogFiltersByTraceId() {
        MetaContext.setCurrentTenantId(100L);
        PermissionAuditLog auditLog = new PermissionAuditLog();
        auditLog.setId(8L);
        auditLog.setTenantId(100L);
        auditLog.setMemberId(6L);
        auditLog.setResourceCode("wd_leave_request");
        auditLog.setActionCode("approve");
        auditLog.setResult(false);
        auditLog.setReason("Rule Center policy denied");
        auditLog.setEvaluationTrace(List.of(Map.of(
                "evaluatorName", "Rule Center",
                "verdict", "DENY",
                "details", Map.of("ruleTraceId", "trace-permission-001"))));

        when(permissionAuditService.getLogsByTraceId(100L, "trace-permission-001", 10))
                .thenReturn(List.of(auditLog));
        when(auditRecordPidResolver.resolve(auditLog)).thenReturn("01PUBLICREC");

        var response = permissionController.getAuditLog(" trace-permission-001 ", null, "ignored_resource", 10);

        assertNotNull(response.getData());
        assertEquals(1, response.getData().size());
        assertEquals("wd_leave_request", response.getData().get(0).getResourceCode());
        verify(permissionAuditService).getLogsByTraceId(100L, "trace-permission-001", 10);
        verify(permissionAuditService, never()).getLogsByResource(anyLong(), anyString(), anyInt());
    }
}
