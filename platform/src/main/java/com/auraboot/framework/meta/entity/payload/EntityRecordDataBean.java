package com.auraboot.framework.meta.entity.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Map;

/**
 * 实体记录数据Bean
 * 用于EntityRecord的data字段
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EntityRecordDataBean {
    
    /**
     * 字段值映射
     * key为字段名，value为字段值
     */
    private Map<String, Object> fields;
    
    /**
     * 关联数据
     */
    private Map<String, Object> relations;
    
    /**
     * 计算字段
     */
    private Map<String, Object> computed;
    
    /**
     * 元数据信息
     */
    private MetaInfo meta;
    
    /**
     * 扩展属性
     */
    private Map<String, Object> extra;
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MetaInfo {
        /**
         * 数据版本
         */
        private String version;
        
        /**
         * 数据来源
         */
        private String source;
        
        /**
         * 最后修改时间
         */
        private String lastModified;
        
        /**
         * 校验状态
         */
        private String validationStatus;
    }
}