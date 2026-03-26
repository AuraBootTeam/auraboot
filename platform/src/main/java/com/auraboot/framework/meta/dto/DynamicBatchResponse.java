package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 动态批量操作响应DTO
 * 
 * @author AuraBoot Framework
 * @since 1.0.0
 */
@Data
public class DynamicBatchResponse {
    
    /**
     * 总数量
     */
    private Integer total;
    
    /**
     * 成功数量
     */
    private Integer success;
    
    /**
     * 失败数量
     */
    private Integer failed;
    
    /**
     * 跳过数量
     */
    private Integer skipped;
    
    /**
     * 错误信息列表
     */
    private List<String> errors = new ArrayList<>();
    
    /**
     * 成功的数据项
     */
    private List<Map<String, Object>> successItems = new ArrayList<>();
    
    /**
     * 失败的数据项
     */
    private List<BatchErrorItem> failedItems = new ArrayList<>();
    
    /**
     * 跳过的数据项
     */
    private List<BatchErrorItem> skippedItems = new ArrayList<>();
    
    /**
     * 处理耗时（毫秒）
     */
    private Long duration;
    
    /**
     * 扩展属性
     */
    private Map<String, Object> metadata;
    
    /**
     * 批量操作错误项
     */
    @Data
    public static class BatchErrorItem {
        /**
         * 数据索引
         */
        private Integer index;
        
        /**
         * 原始数据
         */
        private Map<String, Object> data;
        
         /**
         * 错误信息
         */
        private String error;
        
        /**
         * 错误代码
         */
        private String errorCode;
    }
}