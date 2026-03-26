package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 通用简单结果DTO
 * 用于快速实现各种结果类型
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Data
@Builder
public class SimpleResult {

    /**
     * 是否成功
     */
    private Boolean success;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 数据
     */
    private Object data;

    /**
     * 扩展属性
     */
    private Map<String, Object> properties;

    /**
     * 列表数据
     */
    private List<Object> items;

    /**
     * 计数
     */
    private Integer count;

    /**
     * 消息
     */
    private String message;
}