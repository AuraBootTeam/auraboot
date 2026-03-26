package com.auraboot.framework.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * OTP verification code entity for login, bind, reset-password, and deactivation flows.
 *
 * @since 7.0.0
 */
@Data
@TableName("ab_verification_code")
public class VerificationCode {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Phone number or email address */
    private String target;

    /** 6-digit verification code */
    private String code;

    /** LOGIN | BIND | RESET_PASSWORD | DEACTIVATION */
    private String type;

    private Instant createdAt;

    private Instant expiresAt;

    /** Whether this code has been successfully verified */
    private Boolean verified;

    /** Number of verification attempts */
    private Integer attempts;

    /** IP address of the requester */
    private String ipAddress;
}
