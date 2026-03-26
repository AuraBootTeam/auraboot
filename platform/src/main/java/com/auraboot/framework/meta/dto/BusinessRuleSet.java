package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * 业务规则集
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class BusinessRuleSet {
    
    /**
     * 规则列表
     */
    private List<BusinessRule> rules;
    
    /**
     * 规则集名称
     */
    private String name;
    
    /**
     * 规则集描述
     */
    private String description;
    
    /**
     * 是否启用
     */
    private boolean enabled;
}