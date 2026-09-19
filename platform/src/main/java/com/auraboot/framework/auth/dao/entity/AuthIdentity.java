package com.auraboot.framework.auth.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * One external identity (provider + app + openid) bound to a platform user.
 * The unionid column correlates identities of the same WeChat user across
 * apps under one open-platform account and may be absent on early logins.
 */
@Data
@TableName("ab_auth_identity")
public class AuthIdentity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String pid;

    private Long userId;

    /** Channel family, e.g. wechat_mini / wechat_web / wechat_official. */
    private String provider;

    private String appId;

    private String openid;

    private String unionid;

    private Instant lastLoginAt;

    private Instant createdAt;

    private Instant updatedAt;
}
