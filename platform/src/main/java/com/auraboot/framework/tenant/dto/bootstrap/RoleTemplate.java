package com.auraboot.framework.tenant.dto.bootstrap;

import lombok.Data;

/**
 * 角色模板
 * 
 * 定义租户初始化时需要创建的角色信息
 * 
 * @author AuraBoot
 * @since 2.2.0
 */
@Data
public class RoleTemplate {
    
    /**
     * 角色编码
     * 唯一标识角色,例如: TENANT_OWNER, TENANT_ADMIN, TENANT_USER
     * 必填字段
     */
    private String code;
    
    /**
     * 角色名称
     * 显示给用户的角色名称,例如: "租户所有者", "租户管理员"
     * 必填字段
     */
    private String name;
    
    /**
     * 角色描述
     * 详细说明角色的职责和权限范围
     */
    private String description;
    
    /**
     * 角色类型
     * 例如: TENANT (租户级角色), SYSTEM (系统级角色)
     * 默认值: TENANT
     */
    private String type;
    
    /**
     * 作用域类型
     * 定义角色的作用范围,例如: TENANT (租户范围), GLOBAL (全局范围)
     * 默认值: TENANT
     */
    private String scopeType;
    
    /**
     * 优先级
     * 数值越小优先级越高
     * TENANT_OWNER 应该具有最高优先级(最小数值)
     * 例如: TENANT_OWNER=1, TENANT_ADMIN=10, TENANT_USER=100
     */
    private Integer priority;
    
    /**
     * 是否为默认角色
     * 如果为true,新用户加入租户时会自动分配此角色
     */
    private Boolean isDefault;
    
    /**
     * 是否可删除
     * 如果为false,该角色不能被删除
     * TENANT_OWNER 应该设置为 false
     */
    private Boolean isDeletable;
}
