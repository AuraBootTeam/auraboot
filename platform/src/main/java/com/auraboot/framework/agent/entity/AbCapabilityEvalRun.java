package com.auraboot.framework.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName(value = "ab_capability_eval_run", autoResultMap = true)
public class AbCapabilityEvalRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String pid;
    private Long tenantId;
    private LocalDateTime runAt;
    private String evalMode;
    private String scope;
    private Integer totalCases;
    private Double toolSelectionAccuracy;
    private Double parameterCompletionRate;
    private Double safetyComplianceRate;
    private Double composabilityScore;
    private Double hallucinationRate;

    @TableField(typeHandler = JacksonTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Map<String, Object> report;

    private LocalDateTime createdAt;
}
