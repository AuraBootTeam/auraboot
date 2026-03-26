package com.auraboot.framework.meta.entity;

import com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler;
import com.auraboot.framework.meta.entity.common.AbstractMultiVersionEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.ibatis.type.JdbcType;

/**
 * State Graph Definition entity.
 * Defines state nodes and transition rules for a business model.
 * Versioned entity following the same pattern as CommandDefinition.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ab_state_graph_definition")
public class StateGraphDefinition extends AbstractMultiVersionEntity {

    @TableField("code")
    private String code;

    @TableField("display_name")
    private String displayName;

    @TableField("description")
    private String description;

    @TableField("model_code")
    private String modelCode;

    @TableField("state_field")
    private String stateField;

    @TableField(value = "nodes", jdbcType = JdbcType.OTHER,
            typeHandler = JsonbStringTypeHandler.class)
    private String nodes;

    @TableField(value = "transitions", jdbcType = JdbcType.OTHER,
            typeHandler = JsonbStringTypeHandler.class)
    private String transitions;
}
