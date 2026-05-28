package com.auraboot.framework.chatbi.v2.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Multi-turn conversation state. PRD 17 §5 table 2 + §7.4.
 *
 * <p>{@code messages_json} is the rolling history bounded at {@code multi-turn=5}
 * (PRD §15). {@code context_reset_at} non-null means the user explicitly
 * cleared context — subsequent rows ignore prior turns.
 */
@Data
@TableName("chatbi_conversation")
public class ChatBiConversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String pid;

    private Long tenantId;

    private Long userId;

    /** Nullable — allows cross-model conversation (PRD §5). */
    private String semanticModelPid;

    /** JSON: {@code [{role:user|assistant, content, tokens?}]}. */
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
