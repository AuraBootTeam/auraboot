package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.util.List;

/**
 * 字典导出结果DTO
 * 用于字典导出功能的结果返回
 */
@Data
public class DictExportResult {

    /**
     * 是否成功
     */
    private Boolean success;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 导出的字典数据
     */
    private List<DictDTO> dicts;

    /**
     * 导出格式
     */
    private String format;

    /**
     * 导出文件名
     */
    private String fileName;

    /**
     * 导出文件大小（字节）
     */
    private Long fileSize;

    /**
     * 导出的字典数量
     */
    private Integer dictCount;

    /**
     * 导出的字典项数量
     */
    private Integer itemCount;

    /**
     * 导出时间戳
     */
    private Long exportTimestamp;

    /**
     * 导出耗时（毫秒）
     */
    private Long exportDuration;

    /**
     * 是否包含字典项
     */
    private Boolean includeItems;

    /**
     * 构造函数
     */
    public DictExportResult() {
        this.success = false;
        this.dictCount = 0;
        this.itemCount = 0;
        this.includeItems = true;
        this.exportTimestamp = System.currentTimeMillis();
    }

    /**
     * 设置成功状态
     */
    public void setSuccess() {
        this.success = true;
        this.errorMessage = null;
    }

    /**
     * 设置失败状态
     * @param errorMessage 错误信息
     */
    public void setFailure(String errorMessage) {
        this.success = false;
        this.errorMessage = errorMessage;
    }
}