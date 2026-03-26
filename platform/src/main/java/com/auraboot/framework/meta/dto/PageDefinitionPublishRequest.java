package com.auraboot.framework.meta.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 页面定义发布请求
 */
@Data
public class PageDefinitionPublishRequest {
    
    /**
     * 版本注释
     */
    @NotBlank(message = "版本注释不能为空")
    private String versionComment;
    
    /**
     * 发布环境
     */
    private String environment;
    
    /**
     * 是否强制发布
     */
    private Boolean forcePublish = false;
    
    /**
     * 发布说明
     */
    private String description;
}