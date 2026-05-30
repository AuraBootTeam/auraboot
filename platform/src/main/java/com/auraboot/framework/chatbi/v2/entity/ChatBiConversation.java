package com.auraboot.framework.chatbi.v2.entity;

import com.auraboot.framework.tenant.typehandler.JsonStringTypeHandler;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;

/**
 * Multi-turn conversation state. PRD 17 §5 table 2 + §7.4.
 *
 * <p>{@code messagesJson} is the rolling history bounded at {@code multi-turn=5}
 * (PRD §15) — {@code ConversationService} enforces the cap and trims older
 * turns. {@code contextResetAt} non-null means the user explicitly cleared
 * context — subsequent reads ignore prior turns.
 *
 * <p>Auraboot convention: {@code id BIGINT PRIMARY KEY} maps to
 * {@link IdType#ASSIGN_ID} (snowflake), not AUTO (ENT engineering-gotchas
 * §「auraboot id 列约定」). The JSONB column requires
 * {@link JsonStringTypeHandler} + {@link JdbcType#OTHER} or PG rejects the
 * String payload (ENT §「MyBatis-Plus + PG JSONB 列」).
 */
@Data
@TableName("chatbi_conversation")
public class ChatBiConversation {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String pid;

    private Long tenantId;

    private Long userId;

    /** Nullable — allows cross-model conversation (PRD §5). */
    private String semanticModelPid;

    /** JSON: {@code [{role:user|assistant, content, tokens?}]}. */
    @TableField(jdbcType = JdbcType.OTHER, typeHandler = JsonStringTypeHandler.class)
    private String messagesJson;

    private Instant contextResetAt;

    private Integer tokenBudgetUsed;

    /** {@code ACTIVE / CLOSED}. */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
