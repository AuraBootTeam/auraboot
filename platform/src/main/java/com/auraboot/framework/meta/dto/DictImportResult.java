package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.util.List;

/**
 * 字典导入结果DTO
 */
@Data
public class DictImportResult {

    /**
     * 是否导入成功
     */
    private Boolean success;

    /**
     * 总数量
     */
    private Integer totalCount;

    /**
     * 成功数量
     */
    private Integer successCount;

    /**
     * 失败数量
     */
    private Integer failureCount;

    /**
     * 跳过数量
     */
    private Integer skipCount;

    /**
     * 成功导入的字典列表
     */
    private List<DictDTO> successItems;

    /**
     * 失败的字典信息
     */
    private List<FailureItem> failureItems;

    /**
     * 跳过的字典信息
     */
    private List<SkipItem> skipItems;

    /**
     * 导入摘要
     */
    private String summary;

    /**
     * 导入时间戳
     */
    private Long importTimestamp;

    /**
     * 导入耗时（毫秒）
     */
    private Long duration;

    /**
     * 失败项信息
     */
    @Data
    public static class FailureItem {
        private String code;
        private String name;
        private String reason;
        private String errorMessage;
    }

    /**
     * 跳过项信息
     */
    @Data
    public static class SkipItem {
        private String code;
        private String name;
        private String reason;
    }

    /**
     * 构造函数
     */
    public DictImportResult() {
        this.success = true;
        this.totalCount = 0;
        this.successCount = 0;
        this.failureCount = 0;
        this.skipCount = 0;
        this.successItems = new java.util.ArrayList<>();
        this.failureItems = new java.util.ArrayList<>();
        this.skipItems = new java.util.ArrayList<>();
        this.importTimestamp = System.currentTimeMillis();
    }

    /**
     * 添加成功项
     */
    public void addSuccessItem(DictDTO dict) {
        this.successItems.add(dict);
        this.successCount++;
        this.totalCount++;
    }

    /**
     * 添加失败项
     */
    public void addFailureItem(String code, String name, String reason, String errorMessage) {
        FailureItem item = new FailureItem();
        item.setCode(code);
        item.setName(name);
        item.setReason(reason);
        item.setErrorMessage(errorMessage);
        this.failureItems.add(item);
        this.failureCount++;
        this.totalCount++;
        this.success = false;
    }

    /**
     * 添加跳过项
     */
    public void addSkipItem(String code, String name, String reason) {
        SkipItem item = new SkipItem();
        item.setCode(code);
        item.setName(name);
        item.setReason(reason);
        this.skipItems.add(item);
        this.skipCount++;
        this.totalCount++;
    }

    /**
     * 生成导入摘要
     */
    public void generateSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("导入完成：");
        sb.append("总计 ").append(totalCount).append(" 项，");
        sb.append("成功 ").append(successCount).append(" 项，");
        sb.append("失败 ").append(failureCount).append(" 项，");
        sb.append("跳过 ").append(skipCount).append(" 项");
        
        if (duration != null) {
            sb.append("，耗时 ").append(duration).append(" 毫秒");
        }
        
        this.summary = sb.toString();
    }
}