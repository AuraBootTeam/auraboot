package com.auraboot.framework.user.dto;

import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 用户个人资料响应DTO
 */
@Data
public class UserProfileResponse {
    
    /**
     * 用户业务ID
     */
    private String pid;
    
    /**
     * 用户名
     */
    @Schema(nullable = true)
    private String userName;
    
    /**
     * 昵称
     */
    @Schema(nullable = true)
    private String nickName;
    
    /**
     * 邮箱
     */
    @Schema(nullable = true)
    private String email;

    private Boolean emailVerified;
    
    /**
     * 手机号
     */
    @Schema(nullable = true)
    private String mobile;
    
    /**
     * 地区
     */
    @Schema(nullable = true)
    private String area;
    
    /**
     * 个性签名
     */
    @Schema(nullable = true)
    private String signature;
    
    /**
     * 头像文件ID
     */
    @Schema(nullable = true)
    private String imgId;
    
    /**
     * 头像URL
     */
    @Schema(nullable = true)
    private String avatarUrl;
    
    /**
     * 最后登录时间
     */
    @Schema(nullable = true)
    private LocalDateTime lastSignInAt;

    /**
     * 创建时间
     */
    @Schema(nullable = true)
    private LocalDateTime createdAt;
}
