package com.auraboot.framework.agent.trace.entity;

import com.auraboot.framework.application.database.mybatis.StringArrayTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Data
@TableName(value = "ab_ai_trace", autoResultMap = true)
public class AiTrace {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String traceId;
    private Long tenantId;
    private String sessionId;
    private String name;
    private Long userId;

    private String input;
    private String output;

    private String status;
    private String errorMessage;

    private Long durationMs;
    private Integer totalInputTokens;
    private Integer totalOutputTokens;
    private BigDecimal totalCost;

    @TableField(typeHandler = JacksonTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Map<String, Object> metadata;

    @TableField(typeHandler = StringArrayTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String[] tags;

    private Instant startTime;
    private Instant endTime;
    private Instant createdAt;
}
