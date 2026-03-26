package com.auraboot.framework.bpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "ab_bpm_rule", autoResultMap = true)
public class BpmRule {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String pid;
    private Long tenantId;
    private String ruleCode;
    private String ruleName;
    private String ruleType;          // CONDITION, ASSIGNEE, VALIDATION, CUSTOM
    private String ruleContent;       // DRL content

    @TableField(typeHandler = PluginSettingsTypeHandler.class)
    private Map<String, Object> inputSchema;

    @TableField(typeHandler = PluginSettingsTypeHandler.class)
    private Map<String, Object> outputSchema;

    private String description;
    private Boolean enabled;
    private Integer version;
    private Instant createdAt;
    private Instant updatedAt;

    @TableLogic
    private Boolean deletedFlag;
}
