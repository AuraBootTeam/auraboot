package com.auraboot.framework.branding;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.auraboot.framework.application.typehandler.JsonNodeTypeHandler;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.ibatis.type.JdbcType;
import lombok.Data;
import java.time.Instant;

@Data
@TableName(value = "ab_auth_appearance_revision", autoResultMap = true)
public class AuthAppearanceRevision {
    @TableId(value = "version", type = IdType.INPUT)
    private Long version;
    private String action;
    @TableField(value = "snapshot", typeHandler = JsonNodeTypeHandler.class, jdbcType = JdbcType.OTHER)
    private JsonNode snapshot;
    @TableField("actor_id")
    private Long actorId;
    @TableField("created_at")
    private Instant createdAt;
}
