package com.auraboot.framework.meta.entity;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 模型字段绑定实体类
 * 对应表：ab_meta_model_field_binding
 * 
 * 该实体用于管理业务实体与字段的多对多关系
 * 支持字段排序和租户隔离
 */
@Data
@TableName(value = "ab_meta_model_field_binding", autoResultMap = true)
public class ModelFieldBinding {

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * Public identifier (ULID)
     */
    @TableField("pid")
    private String pid;

    /**
     * 租户ID
     */
    @TableField("tenant_id")
    private Long tenantId;



    /**
     * 模型ID
     * 关联到ab_meta_model表的id字段
     */
    @TableField("model_id")
    private Long modelId;

    /**
     * 字段ID
     * 关联到ab_meta_field表的id字段
     */
    @TableField("field_id")
    private Long fieldId;

    /**
     * 字段在模型中的排序
     * 用于控制字段在UI中的显示顺序
     */
    @TableField("field_order")
    private Integer fieldOrder;

    /**
     * 是否必填
     */
    @TableField("required")
    private Boolean required;

    /**
     * 是否可见
     */
    @TableField("visible")
    private Boolean visible;

    /**
     * 是否可编辑
     */
    @TableField("editable")
    private Boolean editable;

    /**
     * 是否参与关键词全文搜索
     */
    @TableField("searchable")
    private Boolean searchable;

    /**
     * 默认值
     */
    @TableField("default_value")
    private String defaultValue;

    /**
     * 验证规则
     */
    @TableField("validation_rules")
    private String validationRules;

    /**
     * 显示配置
     */
    @TableField("display_config")
    private String displayConfig;

    /**
     * 备注
     */
    @TableField("remarks")
    private String remarks;

    /**
     * 上下文特定的字段别名
     * Field Management Enhancement: Context-specific alias
     */
    @TableField("alias_code")
    private String aliasCode;

    /**
     * 覆盖字典绑定
     * Field Management Enhancement: Override dictionary binding
     */
    @TableField("dict_override_code")
    private String dictOverrideCode;

    /**
     * UI 提示
     * Field Management Enhancement: UI hint for this context
     */
    @TableField("ui_hint")
    private String uiHint;

    /**
     * 覆盖验证规则
     * Field Management Enhancement: Override validation rules
     */
    @TableField("validation_override")
    private String validationOverride;

    /**
     * 是否为系统绑定
     * 系统字段绑定不可解绑
     */
    @TableField("is_system_binding")
    private Boolean isSystemBinding;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private Instant createdAt;

    /**
     * 更新时间
     */
    @TableField("updated_at")
    private Instant updatedAt;

    /**
     * 构造函数
     */
    public ModelFieldBinding() {
        this.pid = UniqueIdGenerator.generate();
        this.fieldOrder = 0;
        this.required = false;
        this.visible = true;
        this.editable = true;
        this.isSystemBinding = false;
        this.searchable = false;
    }

    /**
     * 构造函数
     * @param tenantId 租户ID
     * @param modelId 模型ID
     * @param fieldId 字段ID
     * @param fieldOrder 字段排序
     */
    public ModelFieldBinding(Long tenantId, Long modelId, Long fieldId, Integer fieldOrder) {
        this.pid = UniqueIdGenerator.generate();
        this.tenantId = tenantId;
        this.modelId = modelId;
        this.fieldId = fieldId;
        this.fieldOrder = fieldOrder != null ? fieldOrder : 0;
        this.required = false;
        this.visible = true;
        this.editable = true;
        this.isSystemBinding = false;
        this.searchable = false;
    }

    /**
     * 检查绑定关系是否有效
     * @return 是否有效
     */
    public boolean isValid() {
        return tenantId != null && modelId != null && fieldId != null;
    }

    /**
     * 获取排序值，确保不为null
     * @return 排序值
     */
    public Integer getFieldOrder() {
        return fieldOrder != null ? fieldOrder : 0;
    }

    /**
     * 设置排序值，确保不为负数
     * @param fieldOrder 排序值
     */
    public void setFieldOrder(Integer fieldOrder) {
        this.fieldOrder = fieldOrder != null && fieldOrder >= 0 ? fieldOrder : 0;
    }
}