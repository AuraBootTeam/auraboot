package com.auraboot.framework.meta.dto;

import java.util.Map;

/**
 * 数据验证请求
 */
public class ValidationRequest extends AbstractQueryRequest {
    
    private String modelCode;
    private Map<String, Object> data;
    
    public String getModelCode() {
        return modelCode;
    }
    
    public void setModelCode(String modelCode) {
        this.modelCode = modelCode;
    }
    
    public Map<String, Object> getData() {
        return data;
    }
    
    public void setData(Map<String, Object> data) {
        this.data = data;
    }
}