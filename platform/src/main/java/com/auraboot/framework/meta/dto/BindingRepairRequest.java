package com.auraboot.framework.meta.dto;

import lombok.Data;

/**
 * 绑定关系修复请求DTO
 * 
 * @author AuraBoot Framework
 * @since 2.0.0
 */
@Data
public class BindingRepairRequest {

    /**
     * 模型ID（可选）
     */
    private Long modelId;

    /**
     * 字段ID（可选）
     */
    private Long fieldId;

    /**
     * 修复类型
     */
    private RepairType repairType;

    /**
     * 是否自动修复
     */
    private Boolean autoRepair;

    /**
     * 扩展信息
     */
    private Object extension;

    /**
     * 构造函数
     */
    public BindingRepairRequest() {
        this.repairType = RepairType.CONSISTENCY;
        this.autoRepair = true;
    }

    /**
     * 修复类型
     */
    public enum RepairType {
        CONSISTENCY,    // 一致性修复
        INTEGRITY,      // 完整性修复
        PERFORMANCE     // 性能修复
    }
}