package com.auraboot.framework.meta.dto;

import java.util.Map;

/**
 * 更新数据请求
 */
public class UpdateDataRequest extends AbstractUpdateRequest {
    
    private String modelCode;
    private String recordId;
    private Map<String, Object> data;
    
    public String getModelCode() {
        return modelCode;
    }
    
    public void setModelCode(String modelCode) {
        this.modelCode = modelCode;
    }
    
    public String getRecordId() {
        return recordId;
    }
    
    public void setRecordId(String recordId) {
        this.recordId = recordId;
    }
    
    public Map<String, Object> getData() {
        return data;
    }
    
    public void setData(Map<String, Object> data) {
        this.data = data;
    }
}