package com.auraboot.framework.permission.controller;

import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.dto.PermissionReferenceDTO;
import com.auraboot.framework.permission.service.PermissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

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
    
    @InjectMocks
    private PermissionController permissionController;
    
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
}
