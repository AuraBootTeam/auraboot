package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

/**
 * 实体依赖关系DTO
 * 
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityDependencyDTO {
    
    /**
     * 实体ID
     */
    private Long entityId;
    
    /**
     * 实体编码
     */
    private String entityCode;
    
    /**
     * 实体名称
     */
    private String entityName;
    
    /**
     * 依赖的实体列表
     */
    private List<DependentEntity> dependencies;
    
    /**
     * 被依赖的实体列表
     */
    private List<DependentEntity> dependents;
    
    /**
     * 依赖层级
     */
    private Integer dependencyLevel;
    
    /**
     * 是否存在循环依赖
     */
    private Boolean hasCircularDependency;
    
    /**
     * 源实体编码
     */
    private String sourceEntityCode;
    
    /**
     * 源实体名称
     */
    private String sourceEntityName;
    
    /**
     * 目标实体编码
     */
    private String targetEntityCode;
    
    /**
     * 依赖类型
     */
    private String dependencyType;
    
    /**
     * 字段列表
     */
    private List<String> fieldList;
    
    /**
     * 是否循环依赖
     */
    private Boolean circularDependency;
    
    /**
     * 依赖强度
     */
    private String dependencyStrength;
    
    /**
     * 依赖实体信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DependentEntity {
        /**
         * 实体ID
         */
        private Long entityId;
        
        /**
         * 实体编码
         */
        private String entityCode;
        
        /**
         * 实体名称
         */
        private String entityName;
        
        /**
         * 依赖类型
         */
        private String dependencyType;
        
        /**
         * 依赖字段
         */
        private String dependencyField;
        
        /**
         * 依赖强度（强依赖/弱依赖）
         */
        private String dependencyStrength;
    }
}