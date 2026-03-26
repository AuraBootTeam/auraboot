package com.auraboot.framework.meta.bean;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 引用目标Bean
 * 用于DictField的refTarget字段
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RefTargetBean {
    
    /**
     * 目标实体ID
     */
    private String entityId;
    
    /**
     * 目标字段ID
     */
    private String fieldId;
    
    /**
     * 引用类型
     * 如：one-to-one, one-to-many, many-to-one, many-to-many
     */
    private String refType;
    
    /**
     * 级联操作配置
     */
    private CascadeConfig cascade;
    
    /**
     * 外键约束配置
     */
    private ForeignKeyConfig foreignKey;
    
    /**
     * 查询配置
     */
    private QueryConfig query;
    
    /**
     * 扩展属性
     */
    private Map<String, Object> extensions;
    
    /**
     * 级联操作配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CascadeConfig {
        /**
         * 级联删除
         */
        private Boolean delete;
        
        /**
         * 级联更新
         */
        private Boolean update;
        
        /**
         * 级联保存
         */
        private Boolean save;
    }
    
    /**
     * 外键约束配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ForeignKeyConfig {
        /**
         * 约束名称
         */
        private String name;
        
        /**
         * 是否启用约束
         */
        private Boolean enabled;
        
        /**
         * 删除时动作
         */
        private String onDelete;
        
        /**
         * 更新时动作
         */
        private String onUpdate;
    }
    
    /**
     * 查询配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class QueryConfig {
        /**
         * 是否懒加载
         */
        private Boolean lazy;
        
        /**
         * 获取策略
         */
        private String fetchType;
        
        /**
         * 查询条件
         */
        private List<String> conditions;
        
        /**
         * 排序规则
         */
        private List<String> orderBy;
    }
}