package com.auraboot.framework.meta.dto;

import com.auraboot.framework.meta.entity.payload.DataSourceItemBean;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

/**
 * 数据源响应
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DataSourceResponse extends AbstractResponse {
    
    /**
     * 数据源键
     */
    private String code;
    
    /**
     * 数据源类型
     */
    private String type;
    
    /**
     * 数据项列表
     */
    private List<DataSourceItemBean> items;

}