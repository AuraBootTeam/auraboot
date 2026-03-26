package com.auraboot.framework.meta.dto;

import java.util.List;
import java.util.Map;

/**
 * 批量创建数据请求
 */
public class BatchCreateRequest   {
    
    private String modelCode;
    private List<Map<String, Object>> dataList;
    
    public String getModelCode() {
        return modelCode;
    }
    
    public void setModelCode(String modelCode) {
        this.modelCode = modelCode;
    }
    
    public List<Map<String, Object>> getDataList() {
        return dataList;
    }
    
    public void setDataList(List<Map<String, Object>> dataList) {
        this.dataList = dataList;
    }
}