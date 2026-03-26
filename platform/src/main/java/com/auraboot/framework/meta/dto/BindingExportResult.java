package com.auraboot.framework.meta.dto;

import lombok.Data;

/**
 * 绑定关系导出结果DTO
 * 
 * @author AuraBoot Framework
 * @since 2.0.0
 */
@Data
public class BindingExportResult {

    /**
     * 导出是否成功
     */
    private Boolean success;

    /**
     * 导出消息
     */
    private String message;

    /**
     * 导出的绑定关系数量
     */
    private Integer exportedCount;

    /**
     * 导出数据
     */
    private Object exportData;

    /**
     * 文件路径（如果导出到文件）
     */
    private String filePath;

    /**
     * 处理时间（毫秒）
     */
    private Long processingTime;

    /**
     * 扩展信息
     */
    private Object extension;

    /**
     * 构造函数
     */
    public BindingExportResult() {
        this.success = true;
        this.exportedCount = 0;
    }
}