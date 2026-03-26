package com.auraboot.framework.meta.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 实体定义更新请求
 */
@Data
@Schema(description = "实体定义更新请求")
public class EntityDefinitionUpdateRequest {

      

    

    @Schema(description = "实体编码")
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
    private List<EntityFieldUpdateRequest> fieldConfigs;
}