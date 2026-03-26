package com.auraboot.framework.meta.dto;

/**
 * 获取单条数据请求
 */
public class GetDataRequest extends AbstractQueryRequest {
    
    private String modelCode;
    private String recordId;
    
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
}