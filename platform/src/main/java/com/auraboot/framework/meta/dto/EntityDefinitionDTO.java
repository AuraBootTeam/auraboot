package com.auraboot.framework.meta.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 实体定义DTO
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Schema(description = "实体定义DTO")
public class EntityDefinitionDTO {

    @Schema(description = "业务主键")
    private String pid;

    @Schema(description = "租户ID")
    private Long tenantId;

      

    

    @Schema(description = "状态")
    private String status;

    @Schema(description = "实体编码")
    private String code;

    @Schema(description = "UI元数据")
    private Map<String, Object> uiMeta;

    @Schema(description = "模型元数据")
    private Map<String, Object> modelMeta;

    @Schema(description = "版本号")
    private Integer version;

    @Schema(description = "语义版本")
    private String semver;

    @Schema(description = "行版本")
    private Long rowVersion;

    @Schema(description = "是否当前版本")
    private Boolean isCurrent;

    @Schema(description = "关联的字段列表")
    private List<EntityFieldDTO> fields;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @Schema(description = "创建人")
    private String createdBy;

    @Schema(description = "更新人")
    private String updatedBy;
}