package com.auraboot.framework.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.auraboot.framework.application.database.mybatis.JsonbListTypeHandler;
import com.auraboot.framework.application.database.mybatis.JsonbMapTypeHandler;
import com.auraboot.framework.application.database.mybatis.JsonbObjectTypeHandler;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@TableName(value = "ab_capability", autoResultMap = true)
public class AbCapability {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String pid;
    private Long tenantId;
    private String code;
    private String type;
    private String modelCode;
    private String pluginCode;
    private String sourceTable;
    private Long sourceId;
    private String displayName;
    private String purpose;
    private String whenToUse;
    private String whenNotToUse;

    @TableField(typeHandler = JsonbListTypeHandler.class)
    private List<String> preconditions;

    @TableField(typeHandler = JsonbListTypeHandler.class)
    private List<String> sideEffects;

    @TableField(typeHandler = JsonbMapTypeHandler.class)
    private Map<String, Object> inputContract;

    @TableField(typeHandler = JsonbMapTypeHandler.class)
    private Map<String, Object> outputContract;

    private String riskLevel;
    private String confirmationPolicy;
    private Boolean idempotent;
    private Boolean reversible;

    @TableField(typeHandler = JsonbMapTypeHandler.class)
    private Map<String, Object> exampleInput;

    @TableField(typeHandler = JsonbListTypeHandler.class)
    private List<String> composableWith;

    @TableField(typeHandler = JsonbObjectTypeHandler.class)
    private List<Map<String, Object>> interactionModes;

    private Integer version;
    private String status;
    private String contractHash;
    private Instant lastSyncedAt;
    private Long publishedBy;
    private Instant createdAt;
    private Instant updatedAt;
    private Boolean deletedFlag;
}
