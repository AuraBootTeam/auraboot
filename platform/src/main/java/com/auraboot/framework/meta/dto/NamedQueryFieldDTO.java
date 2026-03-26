package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 命名查询字段DTO
 * 用于API响应和数据传输
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryFieldDTO {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 租户ID
     */
    private Long tenantId;

      

    

    /**
     * 查询编码
     */
    private String queryCode;

    /**
     * 字段编码
     */
    private String fieldCode;

    /**
     * 列表达式
     */
    private String columnExpr;

    /**
     * 数据类型
     */
    private String dataType;

    /**
     * 允许的操作符白名单
     */
    private String[] operators;

    /**
     * 关联的字典编码
     */
    private String dictCode;

    /**
     * 是否可排序
     */
    private Boolean sortable;

    /**
     * 是否可搜索
     */
    private Boolean searchable;

    /**
     * UI component type: text, number, numberRange, select, dateRange, date, userPicker, cascader, search, switch
     */
    private String uiComponent;

    private String placeholder;

    private String defaultValue;

    private String linkedField;

    private JsonNode uiConfig;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * 操作符列表
     */
    private List<String> operatorList;

    /**
     * 完整字段标识
     */
    private String fullFieldCode;

    /**
     * 是否有字典关联
     */
    private Boolean hasDict;

    /**
     * 是否有操作符限制
     */
    private Boolean hasOperators;

    /**
     * 默认操作符列表
     */
    private List<String> defaultOperators;

    /**
     * 字段摘要
     */
    private String summary;

    /**
     * 字段显示名称
     */
    private String displayName;

    /**
     * 字段描述
     */
    private String description;

    /**
     * 字段顺序
     */
    private Integer sortOrder;

    /**
     * 是否必填
     */
    private Boolean required;

    /**
     * 字段分组
     */
    private String fieldGroup;

    /**
     * 字段标签
     */
    private List<String> tags;

    /**
     * 验证规则
     */
    private String validationRules;

    /**
     * 格式化规则
     */
    private String formatRules;

    /**
     * 字段权重
     */
    private Integer weight;

    /**
     * 是否敏感字段
     */
    private Boolean sensitive;

    /**
     * 脱敏规则
     */
    private String maskingRule;
}