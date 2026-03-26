package com.auraboot.framework.saas.config.entity;

import com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

@Data
@TableName(value = "ab_bootstrap", autoResultMap = true)
public class BootstrapEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String pid;
    private String bootstrapMode;
    private String status;
    private String systemMode;
    private Long systemTenantId;
    private Long defaultTenantId;
    private Long adminUserId;
    private Long platformAccountId;
    private String currentStep;
    private Integer totalSteps;
    private Integer completedSteps;
    private String errorMessage;
    private Instant startedAt;
    private Instant completedAt;
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String inputParams;
}
