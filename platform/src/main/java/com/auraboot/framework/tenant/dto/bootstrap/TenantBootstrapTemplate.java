package com.auraboot.framework.tenant.dto.bootstrap;

import lombok.Data;
import java.util.List;

/**
 * 租户初始化模板
 * 
 * 定义租户创建时的默认角色、菜单、权限配置
 * 模板文件存储在 resources/tenant-templates/ 目录下
 * 
 * @author AuraBoot
 * @since 2.2.0
 */
@Data
public class TenantBootstrapTemplate {
    
    /**
     * 模板版本号
     * 格式: major.minor.patch (例如: "1.0.0")
     */
    private String version;
    
    /**
     * 模板名称
     * 用于标识不同的模板类型 (例如: "default-bootstrap", "enterprise-bootstrap")
     */
    private String name;
    
    /**
     * 模板描述
     * 说明模板的用途和适用场景
     */
    private String description;
    
    /**
     * Permission definitions
     * Defines system-level permissions needed during tenant initialization.
     * These permissions are created as system-level (tenant_id = NULL), shared by all tenants.
     */
    private List<PermissionTemplate> permissions;
    
    /**
     * 角色定义列表
     * 定义租户初始化时需要创建的角色
     */
    private List<RoleTemplate> roles;
    
    /**
     * 菜单定义列表
     * 定义租户初始化时需要创建的菜单结构
     */
    private List<MenuTemplate> menus;
    
    /**
     * Role-permission bindings
     * Defines associations between roles and permissions.
     */
    private List<RolePermissionBinding> rolePermissionBindings;





}
