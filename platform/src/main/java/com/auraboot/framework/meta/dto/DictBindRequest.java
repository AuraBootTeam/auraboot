package com.auraboot.framework.meta.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * 字典绑定请求DTO
 * 用于字段绑定字典的参数封装
 */
@Data
public class DictBindRequest {

    /**
     * 字典编码
     */
    @NotBlank(message = "字典编码不能为空")
    private String dictCode;

    /**
     * 绑定类型（可选）
     */
    private String bindType;

    /**
     * 绑定配置（可选）
     */
    private String bindConfig;

    /**
     * 是否覆盖现有绑定
     */
    private Boolean overwrite;

    /**
     * 构造函数
     */
    public DictBindRequest() {
        this.bindType = "default";
        this.overwrite = false;
    }
}