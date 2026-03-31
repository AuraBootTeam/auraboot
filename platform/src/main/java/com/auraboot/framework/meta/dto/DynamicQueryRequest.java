package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * 动态查询请求
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class DynamicQueryRequest {
    
    /**
     * 页码
     */
    private Integer pageNum;
    
    /**
     * 页面大小
     */
    private Integer pageSize;
    
    /**
     * 查询条件
     */
    private List<QueryCondition> conditions;
    
    /**
     * 排序字段
     */
    private List<SortField> sortFields;
    
    /**
     * 搜索关键词
     */
    private String keyword;
    
    /**
     * 额外参数
     */
    private java.util.Map<String, Object> extraParams;

    /**
     * SavedView PID (optional)
     * When provided, applies the saved view's filter, sort, and column configurations
     */
    private String viewId;

    /**
     * Cursor for keyset pagination (optional).
     * When provided, uses WHERE id > cursor instead of OFFSET for efficient deep pagination.
     * The value is the last record's ID from the previous page.
     */
    private Long cursor;
}