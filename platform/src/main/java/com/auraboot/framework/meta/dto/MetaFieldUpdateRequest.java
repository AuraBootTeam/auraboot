package com.auraboot.framework.meta.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * 字段定义更新请求DTO
 * 用于更新字段定义的参数封装
 */
@Data
public class MetaFieldUpdateRequest {

    /**
     * 字段键
     */
    private String code;

    /**
     * 数据类型
     */
    @NotBlank(message = "数据类型不能为空")
    private String dataType;

    /**
     * 数据源ID
     */
    private Long dataSourceId;

    /**
     * 状态
     */
    private String status;

    /**
     * 字段特性配置
     */
    private Map<String, Object> feature;

    /**
     * 引用目标配置
     */
    private Map<String, Object> refTarget;

    /**
     * 索引提示配置
     */
    private Map<String, Object> indexHint;

    /**
     * UI配置
     */
    private Map<String, Object> uiSchema;

    /**
     * 查询配置
     */
    private Map<String, Object> querySchema;

    /**
     * 规则配置
     */
    private Map<String, Object> ruleSchema;

    /**
     * 扩展属性
     */
    private Map<String, Object> extension;

    /**
     * 版本说明
     */
    private String versionNote;

    /**
     * 获取字段键（兼容方法）
     */
    public String getCode() {
        return this.code;
    }

    /**
     * 获取状态（兼容方法）
     */
    public String getStatus() {
        return this.status;
    }
}