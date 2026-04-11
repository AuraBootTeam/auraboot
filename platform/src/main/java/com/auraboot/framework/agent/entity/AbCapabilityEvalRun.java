package com.auraboot.framework.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.auraboot.framework.application.database.mybatis.JsonbMapTypeHandler;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
@TableName(value = "ab_capability_eval_run", autoResultMap = true)
public class AbCapabilityEvalRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String pid;
    private Long tenantId;
    private Instant runAt;
    private String evalMode;
    private String scope;
    private Integer totalCases;
    private Double toolSelectionAccuracy;
    private Double parameterCompletionRate;
    private Double safetyComplianceRate;
    private Double composabilityScore;
    private Double hallucinationRate;

    @TableField(typeHandler = JsonbMapTypeHandler.class)
    private Map<String, Object> report;

    private Instant createdAt;
}
