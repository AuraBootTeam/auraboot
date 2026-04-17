package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 模型定义
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class ModelDefinition {
    
    /**
     * 模型ID
     */
    private Long id;
    
    /**
     * 模型编码
     */
    private String code;
    
    /**
     * 模型名称
     */
    private String name;
    
    /**
     * 显示名称
     */
    private String displayName;
    
    /**
     * 描述
     */
    private String description;
    
    /**
     * 表名
     */
    private String tableName;
    
    /**
     * 模型类型
     */
    private String modelType;

    /**
     * Business object category (DOCUMENT, MASTER, TRANSACTION, ACTIVITY, REFERENCE, ENTITY)
     */
    private String modelCategory;

    /** Phase 1 values: physical | namedQuery | endpoint | sqlView */
    private String sourceType;

    /** For namedQuery: query code; for endpoint: connector endpoint code; for sqlView: view name. Required when sourceType != physical. */
    private String sourceRef;

    /** Primary key field code; required for all models (used as list rowKey and default detailKeyField). */
    private String primaryKey;

    /** Declared capabilities; the runtime truth for feature toggles and whitelist-based validation. */
    private ModelCapabilities capabilities;
    
    /**
     * 版本号
     */
    private Integer version;
    
    /**
     * 状态
     */
    private String status;
    
    /**
     * 字段定义列表
     */
    private List<FieldDefinition> fields;
    
    /**
     * 关联关系列表
     */
    private List<RelationDefinition> relations;
    
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
    
    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * Whether this model uses soft delete (deleted_flag column).
     * When true, delete operations set deleted_flag=true instead of physical delete,
     * and queries automatically filter out soft-deleted records.
     */
    @Builder.Default
    private boolean softDelete = false;

    /**
     * Cross-field validation rules (model-level baseline).
     * Evaluated in Stage 8 (PRE_INVARIANT) after InvariantEngine.
     */
    private List<CrossFieldRule> rules;
}