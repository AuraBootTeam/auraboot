package com.auraboot.framework.meta.entity;

import com.auraboot.framework.meta.entity.common.AbstractMultiVersionEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.ibatis.type.JdbcType;

/**
 * Command Definition entity
 * Defines command input schema, target models, binding rules, and execution config.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ab_command_definition")
public class CommandDefinition extends AbstractMultiVersionEntity {

    @TableField("code")
    private String code;

    @TableField("display_name")
    private String displayName;

    @TableField("description")
    private String description;

    @TableField("model_code")
    private String modelCode;

    @TableField(value = "input_schema", jdbcType = JdbcType.OTHER,
            typeHandler = com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler.class)
    private String inputSchema;

    @TableField(value = "target_models", jdbcType = JdbcType.OTHER,
            typeHandler = com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler.class)
    private String targetModels;

    @TableField(value = "execution_config", jdbcType = JdbcType.OTHER,
            typeHandler = com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler.class)
    private String executionConfig;

    /**
     * Agent-friendly description of this command's business intent.
     */
    @TableField("agent_hint")
    private String agentHint;

    /**
     * Command risk level: L0 (read) / L1 (internal write) / L2 (cross-object) / L3 (external) / L4 (irreversible).
     */
    @TableField("cmd_risk_level")
    private String cmdRiskLevel;

    /**
     * 插件PID（用于标识资源来源的插件）
     */
    @TableField("plugin_pid")
    private String pluginPid;

    /**
     * Optional feature entitlement key required to execute this command.
     * Format: {@code pluginId.featureCode}, e.g. {@code crm.ai_scoring}.
     * When non-null, the command executor checks this feature is active for the current tenant.
     */
    @TableField("required_feature")
    private String requiredFeature;
}
