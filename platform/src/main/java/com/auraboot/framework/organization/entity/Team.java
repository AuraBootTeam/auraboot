package com.auraboot.framework.organization.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;
import java.util.Map;

@Data
@TableName(value = "ab_team", autoResultMap = true)
public class Team {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String pid;

    private Long tenantId;

    private String code;

    private String name;

    private String description;

    private String leaderId; // user PID

    private String status; // ACTIVE, INACTIVE

    @TableField(typeHandler = JacksonTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Map<String, Object> extension;

    private Boolean deletedFlag = false;

    private Instant createdAt;

    private Instant updatedAt;

    private Long createdBy;

    private Long updatedBy;
}
