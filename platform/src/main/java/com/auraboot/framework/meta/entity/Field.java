package com.auraboot.framework.meta.entity;

import com.auraboot.framework.application.database.mybatis.*;
import com.auraboot.framework.meta.entity.common.AbstractMultiVersionEntity;
import com.auraboot.framework.meta.entity.payload.*;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 字段定义实体类
 * 对应表：ab_meta_field
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ab_meta_field", autoResultMap = true)
public class Field extends AbstractMultiVersionEntity {

    @TableField("code")
    private String code;
    
    @TableField("data_type")
    private String dataType;

    @TableField("data_source_id")
    private Long dataSourceId;
    
    @TableField(value = "feature", typeHandler = FieldFeatureBeanTypeHandler.class)
    private FieldFeatureBean feature;

    @TableField(value = "ref_target", typeHandler = FieldRefTargetBeanTypeHandler.class)
    private FieldRefTargetBean refTarget;
    
    @TableField(value = "index_hint", typeHandler = FieldIndexHintBeanTypeHandler.class)
    private FieldIndexHintBean indexHint;
    
    @TableField(value = "ui_schema", typeHandler = FieldUiSchemaBeanTypeHandler.class)
    private FieldUiSchemaBean uiSchema;
    
    @TableField(value = "query_schema", typeHandler = FieldQuerySchemaBeanTypeHandler.class)
    private FieldQuerySchemaBean querySchema;
    
    @TableField(value = "rule_schema", typeHandler = FieldRuleSchemaBeanTypeHandler.class)
    private FieldRuleSchemaBean ruleSchema;

    /**
     * 插件PID（用于标识资源来源的插件）
     */
    @TableField("plugin_pid")
    private String pluginPid;

}