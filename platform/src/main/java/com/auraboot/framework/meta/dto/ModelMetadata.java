package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * 模型元数据
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class ModelMetadata {
    
    /**
     * 模型定义
     */
    private ModelDefinition definition;
    
    /**
     * 字段列表
     */
    private List<FieldDefinition> fields;
    
    /**
     * 关联关系列表
     */
    private List<RelationDefinition> relations;
    
    /**
     * 索引列表
     */
    private List<IndexDefinition> indexes;
    
    /**
     * 约束列表
     */
    private List<ConstraintDefinition> constraints;
    
    /**
     * 业务规则
     */
    private BusinessRuleSet businessRules;
}