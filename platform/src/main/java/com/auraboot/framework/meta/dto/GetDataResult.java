package com.auraboot.framework.meta.dto;

import java.util.Map;

/**
 * 获取单条数据结果
 */
public class GetDataResult extends AbstractResponse {
    
    private Map<String, Object> data;
    
    public Map<String, Object> getData() {
        return data;
    }
    
    public void setData(Map<String, Object> data) {
        this.data = data;
    }
}