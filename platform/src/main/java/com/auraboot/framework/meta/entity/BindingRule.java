package com.auraboot.framework.meta.entity;

import com.auraboot.framework.meta.entity.common.AbstractEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.ibatis.type.JdbcType;

/**
 * Binding Rule entity
 * Defines execution rules for a command: ASSERT, FIELD_MAP, HANDLER, EFFECT.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ab_binding_rule")
public class BindingRule extends AbstractEntity {

    @TableField("command_id")
    private Long commandId;

    @TableField("rule_type")
    private String ruleType;

    @TableField("expression")
    private String expression;

    @TableField("target_model")
    private String targetModel;

    @TableField("target_field")
    private String targetField;

    @TableField("source_field")
    private String sourceField;

    @TableField("handler_class")
    private String handlerClass;

    @TableField("event_type")
    private String eventType;

    @TableField(value = "config", jdbcType = JdbcType.OTHER,
            typeHandler = com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler.class)
    private String config;

    @TableField("sequence")
    private Integer sequence;

    @TableField("enabled")
    private Boolean enabled;
}
