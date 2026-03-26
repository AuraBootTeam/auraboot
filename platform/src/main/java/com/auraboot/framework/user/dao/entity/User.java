package com.auraboot.framework.user.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("ab_user")
public class User {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String pid;                // 业务ID(ULID)

    private Instant createdAt;
    private Instant updatedAt;

    private String userName;


    private String nickName;           // add
    private String mobile;
    private String email;

    private String password;           // 加密后的密码

    private String resetPasswordToken;
    private Instant resetPasswordSentAt;
    private Instant rememberCreatedAt;


    //todo use json
    private Integer signInCount = 0;
    private Instant currentSignInAt;
    private Instant lastSignInAt;

    private boolean isEnabled = true;
    private boolean isAccountNonExpired = true;
    private boolean isAccountNonLocked = true;
    private boolean isCredentialsNonExpired = true;

    private Integer failedLoginAttempts = 0;
    private Instant lockedAt;
    private Instant passwordChangedAt;
    private Boolean mustChangePassword = false;
    private Integer securityVersion = 0;

    private Boolean phoneVerified = false;
    private Boolean emailVerified = false;
    private String deactivationStatus;

    @TableLogic
    @TableField("deleted_flag")
    private Boolean deletedFlag = false;

    private String area;               //
    private String signature;          // 个性签名
    private String imgId;              // 头像图片id

    /**
     * User type discriminator.
     * HUMAN         — regular human user (default)
     * SYSTEM_AGENT  — synthetic user bound to an agent definition; excluded from human user lists
     * SERVICE_ACCOUNT — machine identity for integrations
     */
    private String userType = "human";

}
