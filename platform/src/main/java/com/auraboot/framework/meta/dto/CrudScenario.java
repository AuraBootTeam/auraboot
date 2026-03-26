package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * CRUD场景信息
 * 用于描述实体的CRUD操作场景配置
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CrudScenario {
    
    /**
     * 场景类型
     */
    private String scenarioType;
    
    /**
     * 场景Key
     */
    private String scenarioKey;
    
    /**
     * 场景名称
     */
    private String scenarioName;
    
    /**
     * 标题国际化Key
     */
    private String title;
    
    /**
     * 场景描述
     */
    private String description;
    
    /**
     * 实体Key
     */
    private String entityCode;
    
    /**
     * 支持的操作类型
     */
    private String[] supportedOperations;
    
    /**
     * 支持的动作列表
     */
    private java.util.List<String> supportedActions;
    
    /**
     * 是否启用
     */
    private Boolean enabled;
    
    /**
     * 配置模板
     */
    private String configTemplate;
    
    /**
     * 优先级
     */
    private Integer priority;
    
    /**
     * 创建时间
     */
    private Long createdAt;
    
    /**
     * 更新时间
     */
    private Long updatedAt;
}