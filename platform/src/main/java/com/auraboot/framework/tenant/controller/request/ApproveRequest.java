package com.auraboot.framework.tenant.controller.request;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;

/**
 * 审批请求
 * 
 * @author AuraBoot Team
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
}
