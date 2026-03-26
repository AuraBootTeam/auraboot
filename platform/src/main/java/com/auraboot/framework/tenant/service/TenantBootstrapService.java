package com.auraboot.framework.tenant.service;

import com.auraboot.framework.tenant.dto.bootstrap.TenantBootstrapTemplate;

/**
 * 租户初始化服务
 * 
 * 负责从模板加载配置并初始化租户的角色、菜单、权限等数据
 * 
 * @author AuraBoot
 * @since 2.2.0
 */
public interface TenantBootstrapService {
    
    /**
     * 初始化租户
     * 
     * @param tenantId 租户ID
     * @param userId 创建者用户ID
     * @return 初始化结果
     */
    BootstrapResult bootstrapTenant(Long tenantId, Long userId);
    
    /**
     * 加载模板
     * 
     * @param templateName 模板名称（不含.json后缀）
     * @return 模板对象
     */
    TenantBootstrapTemplate loadTemplate(String templateName);
    
    /**
     * 验证模板
     * 
     * @param template 模板对象
     * @throws TemplateValidationException 验证失败时抛出
     */
    void validateTemplate(TenantBootstrapTemplate template);
    
    /**
     * 初始化结果
     */
    class BootstrapResult {
        private boolean success;
        private String message;
        private int rolesCreated;
        private int menusCreated;
        private int permissionsAssigned;
        private long durationMs;
        
        public static BootstrapResult success(int rolesCreated, int menusCreated, int permissionsAssigned, long durationMs) {
            BootstrapResult result = new BootstrapResult();
            result.success = true;
            result.message = "租户初始化成功";
            result.rolesCreated = rolesCreated;
            result.menusCreated = menusCreated;
            result.permissionsAssigned = permissionsAssigned;
            result.durationMs = durationMs;
            return result;
        }
        
        public static BootstrapResult failure(String message) {
            BootstrapResult result = new BootstrapResult();
            result.success = false;
            result.message = message;
            return result;
        }
        
        // Getters and setters
        public boolean isSuccess() {
            return success;
        }
        
        public void setSuccess(boolean success) {
            this.success = success;
        }
        
        public String getMessage() {
            return message;
        }
        
        public void setMessage(String message) {
            this.message = message;
        }
        
        public int getRolesCreated() {
            return rolesCreated;
        }
        
        public void setRolesCreated(int rolesCreated) {
            this.rolesCreated = rolesCreated;
        }
        
        public int getMenusCreated() {
            return menusCreated;
        }
        
        public void setMenusCreated(int menusCreated) {
            this.menusCreated = menusCreated;
        }
        
        public int getPermissionsAssigned() {
            return permissionsAssigned;
        }
        
        public void setPermissionsAssigned(int permissionsAssigned) {
            this.permissionsAssigned = permissionsAssigned;
        }
        
        public long getDurationMs() {
            return durationMs;
        }
        
        public void setDurationMs(long durationMs) {
            this.durationMs = durationMs;
        }
    }
}
