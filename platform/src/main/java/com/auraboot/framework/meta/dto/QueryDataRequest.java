package com.auraboot.framework.meta.dto;

import java.util.Map;

/**
 * 查询数据请求
 */
public class QueryDataRequest extends AbstractQueryRequest {
    
    private String modelCode;
    private PaginationRequest pagination;
    private Map<String, Object> conditions;
    
    public String getModelCode() {
        return modelCode;
    }
    
    public void setModelCode(String modelCode) {
        this.modelCode = modelCode;
    }
    
    public PaginationRequest getPagination() {
        return pagination;
    }
    
    public void setPagination(PaginationRequest pagination) {
        this.pagination = pagination;
    }
    
    public Map<String, Object> getConditions() {
        return conditions;
    }
    
    public void setConditions(Map<String, Object> conditions) {
        this.conditions = conditions;
    }
}