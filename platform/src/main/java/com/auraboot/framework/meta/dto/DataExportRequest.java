package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.Map;

/**
 * 数据导出请求
 *
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataExportRequest {
    
    /**
     * 导出格式
     */
    @Builder.Default
    private ExportFormat format = ExportFormat.EXCEL;
    
    /**
     * 导出的字段列表
     */
    private List<String> fields;
    
    /**
     * 查询条件
     */
    private List<QueryCondition> conditions;
    
    /**
     * 排序字段
     */
    private List<SortField> sortFields;
    
    /**
     * 限制导出数量
     */
    private Integer limit;
    
    /**
     * 是否包含表头
     */
    @Builder.Default
    private Boolean includeHeader = true;
    
    /**
     * 文件名
     */
    private String fileName;
    
    /**
     * 扩展参数
     */
    private Map<String, Object> extraParams;
    
    public enum ExportFormat {
        EXCEL, CSV, JSON, XML
    }
}