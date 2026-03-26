package com.auraboot.framework.meta.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 命名查询版本请求DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryVersionRequest {

    /**
     * 版本号
     */
    @NotBlank(message = "版本号不能为空")
    @Size(max = 50, message = "版本号长度不能超过50个字符")
    private String version;

    /**
     * 版本描述
     */
    @Size(max = 1000, message = "版本描述长度不能超过1000个字符")
    private String description;

    /**
     * 版本类型
     */
    private String versionType = "minor";

    /**
     * 是否主要版本
     */
    private Boolean isMajor = false;

    /**
     * 变更说明
     */
    @Size(max = 2000, message = "变更说明长度不能超过2000个字符")
    private String changeLog;

    /**
     * 版本标签
     */
    private String tag;

    /**
     * 创建者备注
     */
    @Size(max = 500, message = "创建者备注长度不能超过500个字符")
    private String creatorNotes;
}