package com.auraboot.framework.meta.dto;

/**
 * 验证上下文枚举
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
public enum ValidationContext {
    
    /**
     * 创建操作
     */
    CREATE,
    
    /**
     * 更新操作
     */
    UPDATE,
    
    /**
     * 删除操作
     */
    DELETE,
    
    /**
     * 导入操作
     */
    IMPORT,
    
    /**
     * 自定义操作
     */
    CUSTOM
}