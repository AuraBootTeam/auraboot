package com.auraboot.framework.meta.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Command Definition Create/Update Request
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
public class CommandDefinitionCreateRequest {

    @NotBlank(message = "Command code is required")
    private String code;

    private String displayName;
    private String description;

    @NotBlank(message = "Model code is required")
    private String modelCode;

    private String inputSchema;
    private String targetModels;
    private String executionConfig;

    /**
     * 插件PID（用于标识资源来源的插件）
     */
    private String pluginPid;

    /**
     * 扩展属性
     */
    private String extension;
}
