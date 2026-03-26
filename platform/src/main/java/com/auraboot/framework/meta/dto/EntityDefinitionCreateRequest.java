package com.auraboot.framework.meta.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 实体定义创建请求
 */
@Data
@Schema(description = "实体定义创建请求")
public class EntityDefinitionCreateRequest {

    @Schema(description = "租户ID")
    @NotNull(message = "租户ID不能为空")
    private Long tenantId;

      

    

    @Schema(description = "实体编码")
    @NotBlank(message = "实体编码不能为空")
    private String code;

    @Schema(description = "UI元数据")
    private Map<String, Object> uiMeta;

    @Schema(description = "模型元数据")
    private Map<String, Object> modelMeta;

    @Schema(description = "语义版本")
    private String semver;

    @Schema(description = "关联的字段PID列表")
    private List<String> fieldPids;

    @Schema(description = "字段配置列表")
    private List<EntityFieldCreateRequest> fieldConfigs;
}