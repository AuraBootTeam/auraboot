package com.auraboot.framework.meta.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Map;

/**
 * 字段定义创建请求DTO
 * 用于创建字段定义的参数封装
 */
@Data
public class MetaFieldCreateRequest {

    /**
     * 字段键
     */
    @NotBlank(message = "字段键不能为空")
    @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]*$", message = "字段键必须以字母开头，只能包含字母、数字和下划线")
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
     * 状态
     */
    private String status;

    /**
     * 扩展属性
     */
    private Map<String, Object> extension;

    /**
     * 插件PID（用于标识资源来源的插件）
     */
    private String pluginPid;

    /**
     * 版本说明
     */
    private String versionNote;

    /**
     * 是否立即发布
     */
    private Boolean autoPublish;

    /**
     * 关联的模型PID (用于自动绑定字段到模型)
     */
    private String modelPid;

    /**
     * 构造函数
     */
    public MetaFieldCreateRequest() {
          
        
        this.status = "draft";
        this.autoPublish = false;
    }

    /**
     * 获取状态（兼容方法）
     */
    public String getStatus() {
        return this.status;
    }
}