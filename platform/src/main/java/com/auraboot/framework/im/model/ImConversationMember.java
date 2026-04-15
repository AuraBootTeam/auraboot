package com.auraboot.framework.im.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("ab_im_conversation_member")
public class ImConversationMember {

    @TableField("conversation_id")
    private Long conversationId;

    @TableField("member_type")
    private String memberType; // human | agent

    @TableField("member_id")
    private Long memberId;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("role")
    private String role; // OWNER | ADMIN | MEMBER

    @TableField("last_read_seq")
    private Long lastReadSeq;

    @TableField("last_pull_seq")
    private Long lastPullSeq;

    @TableField("muted")
    private Boolean muted;

    @TableField("pinned")
    private Boolean pinned;

    @TableField("hidden")
    private Boolean hidden;

    @TableField("joined_at")
    private Instant joinedAt;
}
