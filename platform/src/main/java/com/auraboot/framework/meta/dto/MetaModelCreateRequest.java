package com.auraboot.framework.meta.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Map;

/**
 * 业务模型创建请求DTO
 * 用于创建业务模型的参数封装
 */
@Data
public class MetaModelCreateRequest {

    /**
     * 模型编码
     */
    @NotBlank(message = "模型编码不能为空")
    @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]*$", message = "模型编码必须以字母开头，只能包含字母、数字和下划线")
    private String code;

    /**
     * 显示名称
     */
    @NotBlank(message = "显示名称不能为空")
    private String displayName;

    /**
     * 描述信息
     */
    private String description;

    /**
     * 模型类型（ENTITY/VIEW/AGGREGATE等）
     */
    private String modelType;

    /**
     * Business object category (DOCUMENT, MASTER, TRANSACTION, ACTIVITY, REFERENCE, ENTITY)
     */
    private String modelCategory;

      

    

    /**
     * 租户ID
     */
    private Long tenantId;

    /**
     * Table name for the model (optional, auto-generated if not specified).
     */
    private String tableName;

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
     * 构造函数
     */
    public MetaModelCreateRequest() {
        this.modelType = "entity";
          
        
        this.autoPublish = false;
    }

    /**
     * 设置租户ID（兼容方法）
     */
    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }
}