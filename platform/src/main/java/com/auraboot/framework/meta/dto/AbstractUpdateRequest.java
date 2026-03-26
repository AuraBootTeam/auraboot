package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.util.Map;

/**
 * 更新请求DTO抽象基类
 * 包含所有更新请求DTO的通用字段，用于减少代码重复
 * 
 * @author AuraBoot
 */
@Data
public abstract class AbstractUpdateRequest {
    
    /**
     * 状态
     */
    private String status;
    
    /**
     * 版本注释
     */
    private String versionComment;
}