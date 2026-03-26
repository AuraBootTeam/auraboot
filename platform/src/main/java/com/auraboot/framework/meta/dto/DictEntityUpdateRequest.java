package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 字典实体更新请求DTO
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DictEntityUpdateRequest extends AbstractUpdateRequest {
    
    /**
     * 实体键
     */
    private String code;
    
    /**
     * UI元数据
     */
    private Map<String, Object> uiMeta;
    
    /**
     * 模型元数据
     */
    private Map<String, Object> modelMeta;
}