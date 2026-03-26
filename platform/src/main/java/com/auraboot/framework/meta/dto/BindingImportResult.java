package com.auraboot.framework.meta.dto;

import lombok.Data;

/**
 * 绑定关系导入结果DTO
 * 
 * @author AuraBoot Framework
 * @since 2.0.0
 */
@Data
public class BindingImportResult {

    /**
     * 导入是否成功
     */
    private Boolean success;

    /**
     * 导入消息
     */
    private String message;

    /**
     * 导入的绑定关系数量
     */
    private Integer importedCount;

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
    public BindingImportResult() {
        this.success = true;
        this.importedCount = 0;
    }
}