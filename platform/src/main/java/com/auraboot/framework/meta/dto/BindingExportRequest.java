package com.auraboot.framework.meta.dto;

import lombok.Data;

/**
 * 绑定关系导出请求DTO
 * 
 * @author AuraBoot Framework
 * @since 2.0.0
 */
@Data
public class BindingExportRequest {

    /**
     * 模型ID（可选）
     */
    private Long modelId;

    /**
     * 字段ID（可选）
     */
    private Long fieldId;

    /**
     * 导出格式
     */
    private ExportFormat exportFormat;

    /**
     * 是否包含详细信息
     */
    private Boolean includeDetails;

    /**
     * 扩展信息
     */
    private Object extension;

    /**
     * 构造函数
     */
    public BindingExportRequest() {
        this.exportFormat = ExportFormat.JSON;
        this.includeDetails = true;
    }

    /**
     * 导出格式
     */
    public enum ExportFormat {
        JSON,   // JSON格式
        CSV,    // CSV格式
        EXCEL   // Excel格式
    }
}