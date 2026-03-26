package com.auraboot.framework.meta.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 创建页面版本请求DTO
 */
@Data
@Schema(description = "创建页面版本请求")
public class PageSchemaVersionCreateRequest {

    @Schema(description = "操作类型: CREATE, UPDATE, PUBLISH, ARCHIVE, DELETE, RESTORE")
    private String operation;

    @Schema(description = "版本描述")
    private String description;

    @Schema(description = "变更日志")
    private String changelog;

    @Schema(description = "版本类型: SNAPSHOT, MINOR, MAJOR")
    private String type;

    @Schema(description = "基础版本ID（用于基于某版本创建）")
    private String baseVersionId;
}
