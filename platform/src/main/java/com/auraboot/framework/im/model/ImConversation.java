package com.auraboot.framework.im.model;

import com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;

@Data
@TableName(value = "ab_im_conversation", autoResultMap = true)
public class ImConversation {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("type")
    private String type; // PRIVATE | GROUP | BOT | OBJECT

    @TableField("name")
    private String name;

    @TableField("avatar_url")
    private String avatarUrl;

    @TableField("owner_id")
    private Long ownerId;

    @TableField("max_seq")
    private Long maxSeq;

    @TableField("last_message_at")
    private Instant lastMessageAt;

    @TableField(value = "metadata", typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String metadata; // JSONB as string

    @TableField("bound_model_code")
    private String boundModelCode;

    @TableField("bound_record_id")
    private Long boundRecordId;

    @TableField("conductor_agent_id")
    private Long conductorAgentId;

    @TableField("ai_context_window")
    private Integer aiContextWindow;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;
}
