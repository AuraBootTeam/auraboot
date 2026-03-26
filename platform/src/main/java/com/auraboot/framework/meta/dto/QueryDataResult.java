package com.auraboot.framework.meta.dto;

import java.util.List;
import java.util.Map;

/**
 * 查询数据结果
 */
public class QueryDataResult extends AbstractResponse {
    
    private PaginationResult<Map<String, Object>> data;
    private List<Map<String, Object>> records;
    private long total;
    
    public PaginationResult<Map<String, Object>> getData() {
        return data;
    }
    
    public void setData(PaginationResult<Map<String, Object>> data) {
        this.data = data;
    }
    
    public List<Map<String, Object>> getRecords() {
        return records;
    }
    
    public void setRecords(List<Map<String, Object>> records) {
        this.records = records;
    }
    
    public long getTotal() {
        return total;
    }
    
    public void setTotal(long total) {
        this.total = total;
    }
}