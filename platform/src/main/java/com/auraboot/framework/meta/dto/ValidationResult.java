package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 验证结果
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class ValidationResult {
    
    /**
     * 验证是否通过
     */
    private Boolean valid;
    
    /**
     * 验证错误列表
     */
    private List<String> errors;
    
    /**
     * 验证警告列表
     */
    private List<String> warnings;
    
    /**
     * 验证通过的字段
     */
    private List<String> validFields;
    
    /**
     * 扩展信息
     */
    private Map<String, Object> extraInfo;
    
    // 便利方法
    public boolean isValid() {
        return Boolean.TRUE.equals(valid);
    }
}