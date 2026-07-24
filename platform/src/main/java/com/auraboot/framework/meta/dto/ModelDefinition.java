package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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

    /**
     * Raw extension map (flattened — nested {"extension":{...}} and flat keys are merged,
     * with flat keys taking precedence). Used by virtual-model executors to read
     * source-type-specific config (e.g. {@code endpointAdapter}).
     */
    private Map<String, Object> extension;

    /**
     * Declares that this model's rows belong to an aggregate root (a master document),
     * and by which local column that ownership is proved.
     *
     * <p>A command is authorized against <em>one</em> aggregate — the record named in the
     * request. Capability reach can only prove "this command may write {@code quote_line}";
     * it can never prove "this command may write <em>Q1001's</em> {@code quote_line} rather
     * than Q2002's". This binding is what closes that gap: every write performed under an
     * open aggregate scope is pinned to the authorized aggregate in the SQL itself.</p>
     *
     * <p>Enforcing it is not a second authorization decision — no policy is consulted. It
     * executes a boundary the entry already decided, which is why it still applies on paths
     * that inherit a command's authority.</p>
     *
     * <p>Absent on a model, nothing changes: the guard only constrains models that opt in.</p>
     */
    private AggregateBinding aggregateBinding;

    /**
     * How a derived model proves which aggregate root its rows belong to.
     */
    // @Builder alone would leave only a package-private all-args constructor, which Jackson
    // cannot use — a binding declared in a plugin's models.json would fail to deserialize and
    // the guard would silently never engage. Same annotation set as FieldDefinition.ImmutableWhen.
    @Data
    @Builder
    public static class AggregateBinding {
        /** Model code of the aggregate root, e.g. {@code quote}. Informational. */
        private String aggregateModel;
        /**
         * Local <em>field code</em> holding the aggregate root's id, e.g. {@code quote_pid}.
         * Resolved to its physical column when a guard is compiled into SQL.
         */
        private String localField;
    }
}