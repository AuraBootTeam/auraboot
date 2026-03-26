package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.List;
import java.util.HashMap;
import java.util.ArrayList;

/**
 * 页面渲染响应DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PageRenderResponse {
    
    /**
     * 页面Key
     */
    private String pageKey;
    
    /**
     * 页面版本
     */
    private String version;
    
    /**
     * 渲染是否成功
     */
    @Builder.Default
    private Boolean success = true;
    
    /**
     * 错误消息
     */
    private String errorMessage;
    
    /**
     * 错误代码
     */
    private String errorCode;
    
    /**
     * 渲染后的页面配置
     */
    @Builder.Default
    private Map<String, Object> pageConfig = new HashMap<>();
    
    /**
     * 页面元数据
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
    
    /**
     * 预加载的数据
     */
    @Builder.Default
    private Map<String, Object> preloadData = new HashMap<>();
    
    /**
     * 用户权限
     */
    @Builder.Default
    private Map<String, Boolean> permissions = new HashMap<>();
    
    /**
     * 渲染时间戳
     */
    @Builder.Default
    private LocalDateTime renderTime = LocalDateTime.now(ZoneOffset.UTC);
    
    /**
     * 渲染耗时（毫秒）
     */
    private Long renderDuration;
    
    /**
     * 缓存信息
     */
    @Builder.Default
    private Map<String, Object> cacheInfo = new HashMap<>();
    
    /**
     * 调试信息
     */
    @Builder.Default
    private Map<String, Object> debugInfo = new HashMap<>();
    
    /**
     * 警告信息
     */
    @Builder.Default
    private List<String> warnings = new ArrayList<>();
    
    /**
     * 依赖资源
     */
    @Builder.Default
    private List<String> dependencies = new ArrayList<>();
    
    /**
     * 添加页面配置
     */
    public void addPageConfig(String code, Object value) {
        if (this.pageConfig == null) {
            this.pageConfig = new HashMap<>();
        }
        this.pageConfig.put(code, value);
    }
    
    /**
     * 添加元数据
     */
    public void addMetadata(String code, Object value) {
        if (this.metadata == null) {
            this.metadata = new HashMap<>();
        }
        this.metadata.put(code, value);
    }
    
    /**
     * 添加预加载数据
     */
    public void addPreloadData(String code, Object value) {
        if (this.preloadData == null) {
            this.preloadData = new HashMap<>();
        }
        this.preloadData.put(code, value);
    }
    
    /**
     * 设置权限
     */
    public void setPermission(String permission, Boolean allowed) {
        if (this.permissions == null) {
            this.permissions = new HashMap<>();
        }
        this.permissions.put(permission, allowed);
    }
    
    /**
     * 添加缓存信息
     */
    public void addCacheInfo(String code, Object value) {
        if (this.cacheInfo == null) {
            this.cacheInfo = new HashMap<>();
        }
        this.cacheInfo.put(code, value);
    }
    
    /**
     * 添加调试信息
     */
    public void addDebugInfo(String code, Object value) {
        if (this.debugInfo == null) {
            this.debugInfo = new HashMap<>();
        }
        this.debugInfo.put(code, value);
    }
    
    /**
     * 添加警告
     */
    public void addWarning(String warning) {
        if (this.warnings == null) {
            this.warnings = new ArrayList<>();
        }
        this.warnings.add(warning);
    }
    
    /**
     * 添加依赖
     */
    public void addDependency(String dependency) {
        if (this.dependencies == null) {
            this.dependencies = new ArrayList<>();
        }
        this.dependencies.add(dependency);
    }
    
    /**
     * 创建错误响应
     */
    public static PageRenderResponse error(String pageKey, String errorMessage) {
        return PageRenderResponse.builder()
                .pageKey(pageKey)
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
    
    /**
     * 创建错误响应（带错误代码）
     */
    public static PageRenderResponse error(String pageKey, String errorCode, String errorMessage) {
        return PageRenderResponse.builder()
                .pageKey(pageKey)
                .success(false)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }
}