package com.auraboot.framework.email.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * Maps a user to a shared email account with a role and assignment weight.
 *
 * @since 6.5.0
 */
@Data
@TableName("ab_email_account_member")
public class EmailAccountMember {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("account_id")
    private Long accountId;

    @TableField("user_id")
    private Long userId;

    /** Role within the shared account: 'owner' or 'member'. */
    @TableField("role")
    private String role;

    /** Weight used for round-robin assignment of incoming messages (higher = more). */
    @TableField("assignment_weight")
    private Integer assignmentWeight;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;
}
