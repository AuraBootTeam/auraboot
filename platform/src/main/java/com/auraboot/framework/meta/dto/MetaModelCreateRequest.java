package com.auraboot.framework.meta.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
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
     * Business meaning written for Agent / domain-aware tooling.
     */
    private String semanticDescription;

    /**
     * Domain category (CRM, FINANCE, INVENTORY, HR, ...).
     */
    private String domainCategory;

    /**
     * Data sensitivity classification (PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED).
     */
    private String dataSensitivity;

    /**
     * Lifecycle description (e.g. "DRAFT → SUBMITTED → APPROVED").
     */
    private String lifecycleDescription;

    /**
     * 租户ID
     */
    private Long tenantId;

    /**
     * Table name for the model (optional, auto-generated if not specified).
     */
    private String tableName;

    /**
     * Source type for virtual model: physical | namedQuery | endpoint | sqlView.
     * Defaults to "physical" when omitted.
     */
    private String sourceType;

    /**
     * Source reference: for namedQuery the query code; for endpoint the connector
     * endpoint code; for sqlView the view name. Required when sourceType != physical.
     */
    private String sourceRef;

    /**
     * Primary key field code. Used as list rowKey and default detailKeyField.
     */
    private String primaryKey;

    /**
     * Declared capabilities (read/write/sort/filter/paginate/export + whitelists).
     * Runtime truth for feature toggles and whitelist-based validation.
     */
    private ModelCapabilities capabilities;

    /**
     * Field definitions supplied by the virtual-model wizard. Each entry
     * carries at minimum code/dataType/sortable/filterable and is normalized
     * into capabilities.sortableFields/filterableFields at save time.
     *
     * <p><b>Note:</b> {@link MetaModelService#create(MetaModelCreateRequest)}
     * does <b>not</b> persist these field definitions. They are only honored
     * when routed through {@link MetaModelService#saveDefinition} (e.g. by
     * the virtual-model wizard in {@code ModelController.createModel}).
     * Service-direct callers that need custom fields must orchestrate
     * {@code MetaFieldService.create} + {@code bindFieldToModel} explicitly.
     */
    private List<FieldDefinition> fields;

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
     * 构造函数
     */
    public MetaModelCreateRequest() {
        this.modelType = "entity";
    }

    /**
     * 设置租户ID（兼容方法）
     */
    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }
}