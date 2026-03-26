package com.auraboot.framework.tenant.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class MemberResponse {
    private Long id;
    private String pid;
    private Long userId;
    private Long tenantId;
    private String status;
    private Instant joinDate;
    private Instant leaveDate;
    private String permissions;
    private String settings;
    private Instant createdAt;
    private Instant updatedAt;
    
    // 关联用户信息
    private UserInfo user;
    
    @Data
    public static class UserInfo {
        private Long id;
        private String pid;
        private String username;
        private String email;
        private String phone;
        private String realName;
        private String avatar;
    }
}