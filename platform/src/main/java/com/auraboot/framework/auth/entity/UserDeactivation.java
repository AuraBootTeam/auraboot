package com.auraboot.framework.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * User account deactivation record.
 * Tracks the lifecycle of an account deactivation request through
 * PENDING -> COOLING_OFF -> COMPLETED (or CANCELLED).
 *
 * @since 7.1.0
 */
@Data
@TableName("ab_user_deactivation")
public class UserDeactivation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String pid;

    private Long userId;

    private String userEmail;

    /** PENDING | COOLING_OFF | COMPLETED | CANCELLED */
    private String status;

    private String reason;

    private Instant requestedAt;

    private Instant coolingOffUntil;

    private Instant anonymizedAt;

    private Instant completedAt;

    private Instant cancelledAt;

    /** Consent agreement snapshot with timestamp (stored as TEXT) */
    private String consentSnapshot;
}
