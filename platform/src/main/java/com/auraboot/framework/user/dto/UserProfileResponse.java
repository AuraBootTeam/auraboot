package com.auraboot.framework.user.dto;

import lombok.Data;

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
    private String userName;
    
    /**
     * 昵称
     */
    private String nickName;
    
    /**
     * 邮箱
     */
    private String email;
    
    /**
     * 手机号
     */
    private String mobile;
    
    /**
     * 地区
     */
    private String area;
    
    /**
     * 个性签名
     */
    private String signature;
    
    /**
     * 头像文件ID
     */
    private String imgId;
    
    /**
     * 头像URL
     */
    private String avatarUrl;
    
    /**
     * 最后登录时间
     */
    private LocalDateTime lastSignInAt;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}