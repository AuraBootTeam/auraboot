package com.auraboot.framework.tenant.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 审批请求DTO
 * 用于成员审批操作
 * 
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
public class ApproveRequest {
    
    /**
     * 审批动作：APPROVE（通过）、REJECT（拒绝）
     */
    @NotBlank(message = "审批动作不能为空")
    private String action;
    
    /**
     * 审批理由
     */
    private String reason;
    
    /**
     * 备注信息
     */
    private String remark;
}