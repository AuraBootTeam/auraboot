package com.auraboot.framework.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("ab_user_session")
public class UserSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String pid;
    private Long userId;
    private String tokenHash;
    private String deviceInfo;
    private String ipAddress;
    private String userAgent;
    private Instant createdAt;
    private Instant lastActiveAt;
    private Boolean revoked = false;
    private Instant revokedAt;
}
