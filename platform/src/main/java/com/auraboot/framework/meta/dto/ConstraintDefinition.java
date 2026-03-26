package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * 约束定义
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class ConstraintDefinition {
    
    /**
     * 约束名称
     */
    private String name;
    
    /**
     * 约束类型
     */
    private ConstraintType type;
    
    /**
     * 约束字段列表
     */
    private List<String> fields;
    
    /**
     * 引用表（外键约束）
     */
    private String referenceTable;
    
    /**
     * 引用字段（外键约束）
     */
    private List<String> referenceFields;
    
    /**
     * 检查条件（检查约束）
     */
    private String checkCondition;
    
    /**
     * 描述
     */
    private String description;
    
    public enum ConstraintType {
        PRIMARY_KEY,    // 主键约束
        FOREIGN_KEY,    // 外键约束
        UNIQUE,         // 唯一约束
        CHECK,          // 检查约束
        NOT_NULL        // 非空约束
    }
}