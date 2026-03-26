package com.auraboot.framework.meta.dto;

/**
 * 删除数据请求
 */
public class DeleteDataRequest extends AbstractQueryRequest {
    
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