package com.auraboot.framework.meta.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 实体统计DTO
 */
@Data
@Schema(description = "实体统计DTO")
public class EntityStatisticsDTO {

    @Schema(description = "总实体数")
    private Long totalEntities;

    @Schema(description = "活跃实体数")
    private Long activeEntities;

    @Schema(description = "总字段数")
    private Long totalFields;

    @Schema(description = "总记录数")
    private Long totalRecords;

    @Schema(description = "按数据类型分组的字段统计")
    private Map<String, Long> fieldsByDataType;

    @Schema(description = "按存储模式分组的实体统计")
    private Map<String, Long> entitiesByStorageMode;

    @Schema(description = "按租户分组的实体统计")
    private Map<String, Long> entitiesByTenant;

    @Schema(description = "最近创建的实体数（7天内）")
    private Long recentEntities;

    @Schema(description = "最近修改的实体数（7天内）")
    private Long recentlyModifiedEntities;

    @Schema(description = "统计时间")
    private LocalDateTime statisticsTime;

    @Schema(description = "平均每个实体的字段数")
    private Double averageFieldsPerEntity;

    @Schema(description = "平均每个实体的记录数")
    private Double averageRecordsPerEntity;

    /**
     * 实体使用情况统计
     */
    @Data
    @Schema(description = "实体使用情况统计")
    public static class EntityUsageStats {
        @Schema(description = "实体PID")
        private String entityPid;
        
        @Schema(description = "实体名称")
        private String entityName;
        
        @Schema(description = "记录数")
        private Long recordCount;
        
        @Schema(description = "字段数")
        private Long fieldCount;
        
        @Schema(description = "最后访问时间")
        private LocalDateTime lastAccessTime;
    }
}