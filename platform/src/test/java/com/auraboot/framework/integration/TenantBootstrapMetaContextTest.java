package com.auraboot.framework.integration;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.tenant.service.TenantBootstrapService;
import com.auraboot.framework.tenant.service.TenantBootstrapService.BootstrapResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tenant Bootstrap MetaContext Test
 * 
 * 专门测试MetaContext设置和清理的正确性
 * 
 * @author AuraBoot Platform
 * @version 1.0.0
 * @since 2026-01-09
 */
@DisplayName("Tenant Bootstrap MetaContext Tests")
class TenantBootstrapMetaContextTest extends BaseIntegrationTest {
    
    @Autowired
    private TenantBootstrapService tenantBootstrapService;
    
    @Autowired
    private PermissionMapper permissionMapper;
    
    @Test
    @DisplayName("租户初始化 - MetaContext修复验证")
    void testBootstrapTenant_MetaContextFix() {
        // Given
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        
        // 清理现有的MetaContext
        if (MetaContext.exists()) {
            MetaContext.clear();
        }
        
        // When - 执行租户初始化
        // 如果MetaContext没有被正确设置，这里会抛出IllegalStateException
        BootstrapResult result = tenantBootstrapService.bootstrapTenant(tenantId, userId);
        
        // Then - 验证初始化成功（说明MetaContext被正确设置了）
        assertTrue(result.isSuccess(), "Bootstrap should succeed - MetaContext was set correctly");
        
        // 验证MetaContext被清理
        assertFalse(MetaContext.exists(), 
            "MetaContext should be cleaned up after bootstrap completes");
        
        // 验证系统级Permission被创建（需要重新设置MetaContext才能查询）
        MetaContext.setContext(tenantId,   userId, null, null);
        try {
            Permission modelManage = permissionMapper.findByCode("MODEL.model.manage");
            assertNotNull(modelManage, 
                "MODEL.model.manage permission should exist - MetaContext was available during creation");
        } finally {
            MetaContext.clear();
        }
    }
    
    @Test
    @DisplayName("租户初始化 - 嵌套调用MetaContext恢复")
    void testBootstrapTenant_NestedContextRestoration() {
        // Given - 设置一个原有的MetaContext
        Long originalTenantId = 999L;
        Long originalUserId = 888L;
        MetaContext.setContext(originalTenantId,   originalUserId, null, null);
        
        // 验证原有上下文存在
        assertTrue(MetaContext.exists());
        assertEquals(originalTenantId, MetaContext.getCurrentTenantId());
        
        // When - 执行租户初始化（嵌套调用）
        Long newTenantId = getTestTenant().getId();
        Long newUserId = getTestUser().getId();
        BootstrapResult result = tenantBootstrapService.bootstrapTenant(newTenantId, newUserId);
        
        // Then - 验证初始化成功
        assertTrue(result.isSuccess());
        
        // 验证原有MetaContext被恢复
        assertTrue(MetaContext.exists());
        assertEquals(originalTenantId, MetaContext.getCurrentTenantId(), 
            "Original tenant ID should be restored");

        // 清理
        MetaContext.clear();
    }
}
