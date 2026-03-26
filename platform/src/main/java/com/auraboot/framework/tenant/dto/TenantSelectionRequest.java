package com.auraboot.framework.tenant.dto;

import lombok.Data;

@Data
public class TenantSelectionRequest {
    private String action; // "create", "join", or "select"
    
    // 创建租户时使用
    private String tenantName;
    private String displayName;
    private String industry;
    private String contactEmail;
    private String contactPhone;
    private String description;
    
    // 加入租户时使用
    private String inviteCode;

    // 选择已有租户时使用
    private Long tenantId;
}