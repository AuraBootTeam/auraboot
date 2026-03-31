package com.auraboot.framework.meta.entity;

import com.auraboot.framework.meta.entity.common.AbstractMultiVersionEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * 业务实体模型定义实体类
 * 对应表：ab_meta_model
 * 
 * 该实体用于存储业务实体的结构定义，与字典数据完全分离
 * 支持版本控制和多租户隔离
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ab_meta_model", autoResultMap = true)
public class Model extends AbstractMultiVersionEntity {

    /**
     * 实体唯一标识码
     * 在租户、命名空间、环境范围内唯一
     */
    @TableField("code")
    private String code;

    /**
     * 插件PID（用于标识资源来源的插件）
     */
    @TableField("plugin_pid")
    private String pluginPid;

    /**
     * Physical table name for this model.
     * Allows META models to bind to existing platform tables (e.g. ab_tenant_member).
     * When null, defaults to generated name "ab_dyn_{code}".
     */
    @TableField("table_name")
    private String tableName;

    /**
     * Business object category: DOCUMENT, MASTER, TRANSACTION, ACTIVITY, REFERENCE, ENTITY.
     * Drives platform behavior (Document Template, Activity Timeline, etc.)
     */
    @TableField("model_category")
    private String modelCategory;

    /**
     * 获取显示名称（从extension中提取）
     * Supports both nested {"extension":{"displayName":"..."}} and flat {"displayName":"..."} formats
     * @return 显示名称
     */
    public String getDisplayName() {
        Object name = getExtensionValue("displayName");
        return name != null ? name.toString() : null;
    }

    /**
     * 获取描述信息（从extension中提取）
     * Supports both nested and flat extension formats
     * @return 描述信息
     */
    public String getDescription() {
        Object desc = getExtensionValue("description");
        return desc != null ? desc.toString() : null;
    }

    /**
     * 获取实体类型（从extension中提取）
     * Supports both nested and flat extension formats
     * @return 实体类型
     */
    public String getModelType() {
        Object type = getExtensionValue("modelType");
        if (type != null) {
            return type.toString();
        }
        return "entity";
    }

    /**
     * Read extension value from either flat map:
     * {"modelType":"view"}
     * or nested map:
     * {"extension":{"modelType":"view"}}
     */
    private Object getExtensionValue(String key) {
        if (getExtension() == null) {
            return null;
        }
        Object direct = getExtension().get(key);
        if (direct != null) {
            return direct;
        }
        Object nestedObj = getExtension().get("extension");
        if (nestedObj instanceof Map<?, ?> nestedMap) {
            return nestedMap.get(key);
        }
        return null;
    }

    /**
     * 检查是否为当前版本
     * @return 是否为当前版本
     */
    public boolean isCurrentVersion() {
        return Boolean.TRUE.equals(getIsCurrent());
    }

    /**
     * 检查是否为草稿状态
     * @return 是否为草稿状态
     */
    public boolean isDraft() {
        return getStatus() != null && StatusConstants.DRAFT.equals(getStatus());
    }

    /**
     * 检查是否已发布
     * @return 是否已发布
     */
    public boolean isPublished() {
        return getStatus() != null && StatusConstants.PUBLISHED.equals(getStatus());
    }

    /**
     * Check if this model is a VIEW type (ViewModel)
     * @return true if modelType is VIEW
     */
    public boolean isViewType() {
        return "view".equals(getModelType());
    }

    /**
     * Get model category, falling back to extension then defaulting to ENTITY.
     */
    public String getEffectiveModelCategory() {
        if (modelCategory != null) {
            return modelCategory;
        }
        Object cat = getExtensionValue("modelCategory");
        return cat != null ? cat.toString() : "entity";
    }

    /**
     * Check if this model is a DOCUMENT type (Header+Line, status machine, numbering).
     */
    public boolean isDocument() {
        return "document".equals(getEffectiveModelCategory());
    }

    /**
     * Check if this model is a MASTER type (long-lived reference data).
     */
    public boolean isMaster() {
        return "master".equals(getEffectiveModelCategory());
    }

    /**
     * Check if AI NBA (Next Best Action) suggestions are enabled for this model.
     * Returns null if not configured (caller should fall back to global config).
     */
    public Boolean isNbaEnabled() {
        Object val = getExtensionValue("enableNba");
        if (val instanceof Boolean b) {
            return b;
        }
        if (val != null) {
            return Boolean.parseBoolean(val.toString());
        }
        return null;
    }
}
