package com.auraboot.framework.meta.dto;

import java.util.HashMap;

/**
 * 通用记录项，可以存储任意字段
 */
public class RecordItem extends HashMap<String, Object>  {
    // 获取ID
    public Object getId() {
        return this.get("id");
    }
    
    // 设置ID
    public void setId(Object id) {
        this.put("id", id);
    }
}