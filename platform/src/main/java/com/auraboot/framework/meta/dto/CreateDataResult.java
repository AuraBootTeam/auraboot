package com.auraboot.framework.meta.dto;

import java.util.Map;

/**
 * 创建数据结果
 */
public class CreateDataResult extends AbstractResponse {
    
    private Map<String, Object> data;
    private String recordId;
    
    public Map<String, Object> getData() {
        return data;
    }
    
    public void setData(Map<String, Object> data) {
        this.data = data;
    }
    
    public String getRecordId() {
        return recordId;
    }
    
    public void setRecordId(String recordId) {
        this.recordId = recordId;
    }
}