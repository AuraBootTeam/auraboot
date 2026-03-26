package com.auraboot.framework.tenant.dto;

import lombok.Data;

@Data
public class TenantSelectionResponse {
    private String status; // "success", "pending", "error"
    private String message;
    private Long tenantId;
    private String tenantName;
    private String jwt; // 更新后的JWT令牌（包含租户信息）
    private Boolean needsApproval; // 是否需要管理员审批
}