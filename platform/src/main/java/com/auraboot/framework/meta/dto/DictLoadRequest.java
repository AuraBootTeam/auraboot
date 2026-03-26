package com.auraboot.framework.meta.dto;

import lombok.Data;

/**
 * 字典加载请求DTO
 */
@Data
public class DictLoadRequest {

    /**
     * 租户ID
     */
    private Long tenantId;

      

    

    /**
     * 字典编码
     */
    private String code;

    /**
     * 版本策略（PINNED/LATEST）
     */
    private String versionStrategy;

    /**
     * 固定版本号（当策略为PINNED时使用）
     */
    private String pinnedVersion;

    /**
     * 是否包含禁用项
     */
    private Boolean includeDisabled = false;

    /**
     * 是否使用缓存
     */
    private Boolean useCache = true;

    /**
     * 级联参数（用于级联字典）
     */
    private String cascadeParam;

    /**
     * 构造函数
     */
    public DictLoadRequest() {}

    /**
     * 构造函数
     */
    public DictLoadRequest(    String code) {
        this.tenantId = tenantId;
          
        
        this.code = code;
        this.versionStrategy = "latest";
    }

    /**
     * 构造函数
     */
    public DictLoadRequest(    String code, String versionStrategy, String pinnedVersion) {
        this.tenantId = tenantId;
          
        
        this.code = code;
        this.versionStrategy = versionStrategy;
        this.pinnedVersion = pinnedVersion;
    }
}