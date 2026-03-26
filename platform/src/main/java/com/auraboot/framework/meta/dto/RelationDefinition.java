package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * 关联关系定义
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class RelationDefinition {
    
    /**
     * 关联名称
     */
    private String name;
    
    /**
     * 源模型
     */
    private String sourceModel;
    
    /**
     * 目标模型
     */
    private String targetModel;
    
    /**
     * 源表
     */
    private String sourceTable;
    
    /**
     * 目标表
     */
    private String targetTable;
    
    /**
     * 关联类型
     */
    private RelationType relationType;
    
    /**
     * 源字段
     */
    private String sourceField;
    
    /**
     * 目标字段
     */
    private String targetField;
    
    /**
     * 中间表（多对多关系）
     */
    private String joinTable;
    
    /**
     * 是否必需
     */
    @Builder.Default
    private Boolean required = false;
    
    /**
     * 是否级联删除
     */
    @Builder.Default
    private Boolean cascadeDelete = false;
    
    /**
     * 是否懒加载
     */
    @Builder.Default
    private Boolean lazy = true;
    
    /**
     * 排序字段
     */
    private List<String> orderBy;
    
    /**
     * 过滤条件
     */
    private String whereClause;
    
    public enum RelationType {
        ONE_TO_ONE,
        ONE_TO_MANY,
        MANY_TO_ONE,
        MANY_TO_MANY
    }
    
    // 便利方法
    public boolean isRequired() {
        return Boolean.TRUE.equals(required);
    }
}