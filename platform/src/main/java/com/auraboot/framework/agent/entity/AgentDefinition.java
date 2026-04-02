package com.auraboot.framework.agent.entity;

import com.auraboot.framework.application.database.mybatis.JsonbListTypeHandler;
import com.auraboot.framework.application.database.mybatis.JsonbMapTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@TableName(value = "ab_agent_definition", autoResultMap = true)
public class AgentDefinition {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String pid;
    private Long tenantId;
    private String agentCode;
    private String name;
    private String description;
    private String avatarUrl;
    private String agentType;
    private String model;
    private String systemPrompt;
    private String tools;
    private String skills;
    private String guardrails;

    @TableField(typeHandler = JsonbMapTypeHandler.class)
    private Map<String, Object> soulProfile;

    private String personality;
    private String expertise;
    private String communicationStyle;
    private String boundaries;
    private String soulGoals;

    private Long systemUserId;
    private Long serviceAccountId;

    @TableField(typeHandler = JsonbListTypeHandler.class)
    private List<String> allowedModels;

    @TableField(typeHandler = JsonbListTypeHandler.class)
    private List<String> allowedOperations;

    private Integer maxTools;
    private Integer maxConcurrentRuns;
    private Integer executionTimeoutSeconds;

    @TableField(typeHandler = JsonbMapTypeHandler.class)
    private Map<String, Object> eventTriggers;

    // AI Employee fields
    @TableField("employee_id")
    private Long employeeId;

    @TableField("auto_reply_mode")
    private String autoReplyMode;

    private String status;
    private String stats;
    private String visibility;

    private Instant createdAt;
    private Instant updatedAt;
    private Long createdBy;
    private Long updatedBy;
    private Boolean deletedFlag;
}
