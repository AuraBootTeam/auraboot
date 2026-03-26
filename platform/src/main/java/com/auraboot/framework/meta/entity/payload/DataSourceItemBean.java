package com.auraboot.framework.meta.entity.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Map;

/**
 * 数据源配置项Bean
 * 用于DataSourceEntity的items字段
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DataSourceItemBean {

    /**
     * 项标识
     */
    private String key;

    private String code;

    private String name;


    /**
     * 显示值
     */
    private String label;

    /**
     * 实际值
     */
    private Object value;

    /**
     * 描述信息
     */
    private String description;

    /**
     * 是否禁用
     */
    private Boolean disabled;

    /**
     * 图标
     */
    private String icon;

    /**
     * 分组
     */
    private String group;

    /**
     * 排序权重
     */
    private Integer order;

    /**
     * 扩展属性
     */
    private Map<String, Object> extra;
}