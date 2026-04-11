package com.auraboot.framework.tenant.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.tenant.dto.bootstrap.TenantBootstrapTemplate;
import com.auraboot.framework.tenant.exception.TemplateNotFoundException;
import com.auraboot.framework.tenant.exception.TemplateValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.*;

/**
 * TenantBootstrapService 集成测试
 *
 * @author AuraBoot
 * @since 2.2.0
 */
class TenantBootstrapServiceTest extends BaseIntegrationTest {
    
    @Autowired
    private TenantBootstrapService tenantBootstrapService;
    
    @Test
    void testLoadDefaultTemplate() {
        // 测试加载默认模板
        TenantBootstrapTemplate template = tenantBootstrapService.loadTemplate("default-bootstrap");
        
        assertThat(template).isNotNull();
        assertThat(template.getName()).isEqualTo("default-bootstrap");
        assertThat(template.getVersion()).isEqualTo("1.0.0");
        assertThat(template.getRoles()).isNotEmpty();
        assertThat(template.getMenus()).isNotEmpty();
        assertThat(template.getRolePermissionBindings()).isNotEmpty();
    }
    
    @Test
    void testLoadNonExistentTemplate() {
        // 测试加载不存在的模板
        assertThatThrownBy(() -> 
            tenantBootstrapService.loadTemplate("non-existent-template")
        ).isInstanceOf(TemplateNotFoundException.class);
    }
    
    @Test
    void testValidateTemplate() {
        // 测试模板验证
        TenantBootstrapTemplate template = tenantBootstrapService.loadTemplate("default-bootstrap");
        
        // 验证应该通过
        assertThatCode(() -> 
            tenantBootstrapService.validateTemplate(template)
        ).doesNotThrowAnyException();
    }
    
    @Test
    void testValidateInvalidTemplate() {
        // 测试验证无效模板
        TenantBootstrapTemplate template = new TenantBootstrapTemplate();
        template.setName("test");
        template.setVersion("1.0.0");
        // 缺少roles和menus
        
        assertThatThrownBy(() -> 
            tenantBootstrapService.validateTemplate(template)
        ).isInstanceOf(TemplateValidationException.class)
         .hasMessageContaining("至少一个角色定义");
    }
    
    @Test
    void testTemplateStructure() {
        // 测试模板结构
        TenantBootstrapTemplate template = tenantBootstrapService.loadTemplate("default-bootstrap");

        // Verify permissions — count reflects the current default-bootstrap.json
        assertThat(template.getPermissions()).hasSizeGreaterThanOrEqualTo(66);
        assertThat(template.getPermissions().get(0).getCode()).isEqualTo("meta_management");
        assertThat(template.getPermissions().get(0).getType()).isEqualTo("menu");

        // 验证角色
        assertThat(template.getRoles()).hasSize(3);
        assertThat(template.getRoles().get(0).getCode()).isEqualTo("tenant_admin");
        assertThat(template.getRoles().get(0).getPriority()).isEqualTo(1);
        assertThat(template.getRoles().get(0).getIsDeletable()).isFalse();

        // Verify menus — count reflects the current default-bootstrap.json (48 as of 2026-04)
        assertThat(template.getMenus()).hasSizeGreaterThanOrEqualTo(40);

        // 验证角色-权限绑定
        assertThat(template.getRolePermissionBindings()).hasSize(3);
        assertThat(template.getRolePermissionBindings().get(0).getRoleCode())
            .isEqualTo("tenant_admin");
        assertThat(template.getRolePermissionBindings().get(0).getPermissionCodes())
            .hasSize(1)
            .contains("*");
    }
}
