package com.auraboot.framework.agent.entity;

import com.auraboot.framework.application.database.mybatis.JsonbListTypeHandler;
import com.auraboot.framework.application.database.mybatis.JsonbMapTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
@TableName(value = "ab_agent_eval_case", autoResultMap = true)
public class AgentEvalCase {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String pid;
    private Long tenantId;
    private String agentCode;
    private String caseId;
    private String category;
    private String taskDescription;

    @TableField(typeHandler = JsonbListTypeHandler.class)
    private List<String> expectedToolCodes;

    @TableField(typeHandler = JsonbListTypeHandler.class)
    private List<String> forbiddenToolCodes;

    @TableField(typeHandler = JsonbMapTypeHandler.class)
    private Map<String, Object> expectedInputKeys;

    private String expectedRiskLevel;
    private Boolean expectsConfirmation;
    private String pluginSource;
    private Boolean deletedFlag;
    private Instant createdAt;
    private Instant updatedAt;
}
