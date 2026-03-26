package com.auraboot.framework.meta.view.schema.common;

import lombok.Data;

import java.util.Map;

@Data
public class CommonConfig
{
    /**
     * 字段属性
     */
    private Map<String, Object> props;

    /**
     * 布局配置
     */
    private Map<String, Object> layout;

    /**
     * 布局配置
     */
    private Map<String, Object> style;
}
