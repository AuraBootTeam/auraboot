package com.auraboot.framework.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("ab_password_history")
public class PasswordHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String passwordHash;
    private Instant createdAt;
}
