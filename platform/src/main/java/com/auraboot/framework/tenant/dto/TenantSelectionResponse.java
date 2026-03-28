package com.auraboot.framework.tenant.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

@Data
public class TenantSelectionResponse {
    private String status; // "success", "pending", "error"
    private String message;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;
    private String tenantName;
    private String jwt; // 更新后的JWT令牌（包含租户信息）
    private Boolean needsApproval; // 是否需要管理员审批
}