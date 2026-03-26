package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.util.List;

/**
 * 字典导出请求DTO
 * 用于字典导出功能的参数封装
 */
@Data
public class DictExportRequest {

    /**
     * 字典编码列表（为空时导出所有字典）
     */
    private List<String> codes;

      

    

    /**
     * 是否包含字典项
     */
    private Boolean includeItems;

    /**
     * 是否包含历史版本
     */
    private Boolean includeHistory;

    /**
     * 导出格式（JSON/EXCEL/CSV）
     */
    private String format;

    /**
     * 过滤条件
     */
    private String filter;

    /**
     * 构造函数
     */
    public DictExportRequest() {
          
        
        this.includeItems = true;
        this.includeHistory = false;
        this.format = "json";
    }
}