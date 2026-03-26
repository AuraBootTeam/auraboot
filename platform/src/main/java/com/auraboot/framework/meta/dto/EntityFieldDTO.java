package com.auraboot.framework.meta.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 实体字段DTO
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Schema(description = "实体字段DTO")
public class EntityFieldDTO {

    @Schema(description = "业务主键")
    private String pid;

    @Schema(description = "租户ID")
    private Long tenantId;

      

    

    @Schema(description = "状态")
    private String status;

    @Schema(description = "字段键")
    private String code;

    @Schema(description = "数据类型")
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

    @Schema(description = "版本号")
    private Integer version;

    @Schema(description = "语义版本")
    private String semver;

    @Schema(description = "行版本")
    private Long rowVersion;

    @Schema(description = "是否当前版本")
    private Boolean isCurrent;

    @Schema(description = "排序顺序")
    private Integer sortOrder;

    @Schema(description = "是否必填")
    private Boolean required;

    @Schema(description = "是否唯一")
    private Boolean unique;

    @Schema(description = "默认值")
    private String defaultValue;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @Schema(description = "创建人")
    private String createdBy;

    @Schema(description = "更新人")
    private String updatedBy;
}