package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.Map;
import java.util.HashMap;

/**
 * 页面渲染请求DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PageRenderRequest {
    
    /**
     * 页面Key
     */
    private String pageKey;
    
    /**
     * 页面版本
     */
    private String version;
    
    /**
     * 用户ID
     */
    private String userId;
    
    /**
     * 渲染上下文
     */
    @Builder.Default
    private Map<String, Object> context = new HashMap<>();
    
    /**
     * 设备类型
     */
    private String deviceType;
    
    /**
     * 语言环境
     */
    private String locale;
    
    /**
     * 主题
     */
    private String theme;
    
    /**
     * 是否预览模式
     */
    @Builder.Default
    private Boolean preview = false;
    
    /**
     * 是否调试模式
     */
    @Builder.Default
    private Boolean debug = false;
    
    /**
     * 渲染选项
     */
    @Builder.Default
    private Map<String, Object> options = new HashMap<>();
    
    /**
     * 添加上下文参数
     */
    public void addContext(String code, Object value) {
        if (this.context == null) {
            this.context = new HashMap<>();
        }
        this.context.put(code, value);
    }
    
    /**
     * 添加渲染选项
     */
    public void addOption(String code, Object value) {
        if (this.options == null) {
            this.options = new HashMap<>();
        }
        this.options.put(code, value);
    }
    
    /**
     * 获取上下文参数
     */
    public Object getContextValue(String code) {
        return this.context != null ? this.context.get(code) : null;
    }
    
    /**
     * 获取渲染选项
     */
    public Object getOptionValue(String code) {
        return this.options != null ? this.options.get(code) : null;
    }
}