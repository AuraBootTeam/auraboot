package com.auraboot.framework.meta.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 数据源创建请求
 */
@Data
public class DataSourceCreateRequest   {
    
    /**
     * 数据源键
     */
    @NotBlank(message = "数据源键不能为空")
    private String code;
    
    /**
     * 数据源类型
     */
    @NotBlank(message = "数据源类型不能为空")
    private String type;
    
    /**
     * 数据源项目配置
     */
    @NotNull(message = "数据源配置不能为空")
    private Map<String, Object> items;

}