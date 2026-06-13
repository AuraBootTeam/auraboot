package com.auraboot.framework.decision.entity;

import com.auraboot.framework.decision.typehandler.JsonNodeTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;

/**
 * Indexed source -> target reference used by DecisionOps impact analysis.
 */
@Data
@TableName(value = "ab_drt_usage_ref", autoResultMap = true)
public class DecisionUsageRefEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("source_type")
    private String sourceType;

    @TableField("source_code")
    private String sourceCode;

    @TableField("source_version")
    private String sourceVersion;

    @TableField("source_pid")
    private String sourcePid;

    @TableField("target_type")
    private String targetType;

    @TableField("target_code")
    private String targetCode;

    @TableField("target_path")
    private String targetPath;

    @TableField("binding")
    private String binding;

    @TableField(value = "metadata_json", typeHandler = JsonNodeTypeHandler.class, jdbcType = JdbcType.OTHER)
    private JsonNode metadataJson;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;
}
