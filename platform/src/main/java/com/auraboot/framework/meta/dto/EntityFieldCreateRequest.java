package com.auraboot.framework.meta.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

/**
 * 实体字段创建请求
 */
@Data
@Schema(description = "实体字段创建请求")
public class EntityFieldCreateRequest {

    @Schema(description = "租户ID")
    @NotNull(message = "租户ID不能为空")
    private Long tenantId;

      

    

    @Schema(description = "字段键")
    @NotBlank(message = "字段键不能为空")
    private String code;

    @Schema(description = "数据类型")
    @NotBlank(message = "数据类型不能为空")
    private String dataType;

    @Schema(description = "数据源ID")
    private String dataSourceId;

    @Schema(description = "特性配置")
    private Map<String, Object> feature;

    @Schema(description = "引用目标")
    private String refTarget;

    @Schema(description = "临时属性")
    private Map<String, Object> adhocAttr;

    @Schema(description = "PII分类")
    private String piiClass;

    @Schema(description = "索引提示")
    private String indexHint;

    @Schema(description = "UI Schema")
    private Map<String, Object> uiSchema;

    @Schema(description = "查询Schema")
    private Map<String, Object> querySchema;

    @Schema(description = "规则Schema")
    private Map<String, Object> ruleSchema;

    @Schema(description = "语义版本")
    private String semver;

    @Schema(description = "排序顺序")
    private Integer sortOrder;

    @Schema(description = "是否必填")
    private Boolean required;

    @Schema(description = "是否唯一")
    private Boolean unique;

    @Schema(description = "默认值")
    private String defaultValue;
}