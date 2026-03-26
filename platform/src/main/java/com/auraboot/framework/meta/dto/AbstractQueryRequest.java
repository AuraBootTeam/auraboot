package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 查询请求DTO抽象基类
 * 继承PaginationRequest，包含所有查询请求DTO的通用字段，用于减少代码重复
 * 
 * @author AuraBoot
 */
@Data
@EqualsAndHashCode(callSuper = true)
public abstract class AbstractQueryRequest extends PaginationRequest {
    
    /**
     * 状态
     */
    private String status;
    
    /**
     * 搜索关键字
     */
    private String keyword;
    
      
    
    
}