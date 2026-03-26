package com.auraboot.framework.meta.dto;

/**
 * 删除数据结果
 */
public class DeleteDataResult extends AbstractResponse {
    
    private String recordId;
    
    public String getRecordId() {
        return recordId;
    }
    
    public void setRecordId(String recordId) {
        this.recordId = recordId;
    }
}