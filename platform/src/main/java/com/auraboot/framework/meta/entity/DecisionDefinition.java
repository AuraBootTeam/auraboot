package com.auraboot.framework.meta.entity;

import com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler;
import com.auraboot.framework.meta.entity.common.AbstractMultiVersionEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.ibatis.type.JdbcType;

/**
 * Decision Definition entity.
 * Defines what evidence is required, what invariants to check,
 * and what outcomes are possible for a given subject type and stage.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ab_decision_definition")
public class DecisionDefinition extends AbstractMultiVersionEntity {

    @TableField("code")
    private String code;

    @TableField("display_name")
    private String displayName;

    @TableField("description")
    private String description;

    @TableField("subject_type")
    private String subjectType;

    @TableField("stage")
    private String stage;

    @TableField(value = "required_evidence", jdbcType = JdbcType.OTHER,
            typeHandler = JsonbStringTypeHandler.class)
    private String requiredEvidence;

    @TableField(value = "invariants", jdbcType = JdbcType.OTHER,
            typeHandler = JsonbStringTypeHandler.class)
    private String invariants;

    @TableField(value = "outcome_options", jdbcType = JdbcType.OTHER,
            typeHandler = JsonbStringTypeHandler.class)
    private String outcomeOptions;

    @TableField("auto_adjudicate")
    private Boolean autoAdjudicate;
}
