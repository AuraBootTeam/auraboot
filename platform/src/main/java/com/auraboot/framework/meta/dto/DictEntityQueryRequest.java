package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 字典实体查询请求DTO
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DictEntityQueryRequest extends AbstractQueryRequest {
    
    /**
     * 实体键
     */
    private String code;
}