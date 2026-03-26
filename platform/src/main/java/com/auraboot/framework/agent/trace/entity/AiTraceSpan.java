package com.auraboot.framework.agent.trace.entity;

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
@TableName(value = "ab_ai_trace_span", autoResultMap = true)
public class AiTraceSpan {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String spanId;
    private String traceId;
    private String parentSpanId;

    private String type;
    private String name;

    @TableField(typeHandler = JacksonTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Object input;

    @TableField(typeHandler = JacksonTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Object output;

    private String status;
    private String level;
    private String statusMessage;

    private Instant startTime;
    private Instant endTime;
    private Long durationMs;

    private String model;

    @TableField(typeHandler = JacksonTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Map<String, Object> modelParameters;

    private Integer inputTokens;
    private Integer outputTokens;
    private BigDecimal cost;
    private String stopReason;
    private Instant completionStartTime;

    @TableField(typeHandler = JacksonTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Object toolDefinitions;

    @TableField(typeHandler = JacksonTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Object toolCalls;

    private Integer sequenceOrder;

    @TableField(typeHandler = JacksonTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Map<String, Object> metadata;

    private Instant createdAt;
}
