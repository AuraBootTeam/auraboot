package com.auraboot.framework.meta.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 实体导入数据DTO
 */
@Data
@Schema(description = "实体导入数据DTO")
public class EntityImportData {

    @Schema(description = "导入ID")
    private String importId;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "导入模式")
    private ImportMode importMode;

    @Schema(description = "导入格式")
    private String importFormat;

    @Schema(description = "导入文件路径")
    private String importFilePath;

    @Schema(description = "导入文件内容")
    private byte[] importFileContent;

    @Schema(description = "实体定义列表")
    private List<EntityDefinition> entityDefinitions;

    @Schema(description = "导入配置")
    private Map<String, Object> importConfig;

    @Schema(description = "是否覆盖现有实体")
    private Boolean overrideExisting;

    @Schema(description = "是否验证数据完整性")
    private Boolean validateIntegrity;

    @Schema(description = "是否备份现有数据")
    private Boolean backupExisting;

    @Schema(description = "导入时间")
    private LocalDateTime importTime;

    @Schema(description = "导入人")
    private String importedBy;

    /**
     * 导入模式枚举
     */
    public enum ImportMode {
        CREATE_ONLY("仅创建"),
        UPDATE_ONLY("仅更新"),
        CREATE_OR_UPDATE("创建或更新"),
        MERGE("合并"),
        REPLACE("替换");

        private final String description;

        ImportMode(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * 实体定义
     */
    @Data
    @Schema(description = "实体定义")
    public static class EntityDefinition {
        @Schema(description = "实体PID")
        private String entityPid;
        
        @Schema(description = "实体键")
        private String entityCode;
        
        @Schema(description = "实体名称")
        private String entityName;
        
        @Schema(description = "实体描述")
        private String description;
        
        @Schema(description = "存储模式")
        private String storageMode;
        
        @Schema(description = "实体配置")
        private Map<String, Object> entityConfig;
        
        @Schema(description = "字段定义列表")
        private List<FieldDefinition> fieldDefinitions;
        
        @Schema(description = "索引定义列表")
        private List<IndexDefinition> indexDefinitions;
    }

    /**
     * 字段定义
     */
    @Data
    @Schema(description = "字段定义")
    public static class FieldDefinition {
        @Schema(description = "字段键")
        private String code;
        
        @Schema(description = "字段名称")
        private String fieldName;
        
        @Schema(description = "数据类型")
        private String dataType;
        
        @Schema(description = "字段长度")
        private Integer fieldLength;
        
        @Schema(description = "是否必填")
        private Boolean required;
        
        @Schema(description = "默认值")
        private String defaultValue;
        
        @Schema(description = "验证规则")
        private Map<String, Object> validationRules;
        
        @Schema(description = "字段配置")
        private Map<String, Object> fieldConfig;
    }

    /**
     * 索引定义
     */
    @Data
    @Schema(description = "索引定义")
    public static class IndexDefinition {
        @Schema(description = "索引名称")
        private String indexName;
        
        @Schema(description = "索引类型")
        private String indexType;
        
        @Schema(description = "索引字段")
        private List<String> indexFields;
        
        @Schema(description = "是否唯一索引")
        private Boolean unique;
        
        @Schema(description = "索引配置")
        private Map<String, Object> indexConfig;
    }

    /**
     * 验证导入数据
     */
    public boolean isValid() {
        return tenantId != null && 
               (importFilePath != null || importFileContent != null || 
                (entityDefinitions != null && !entityDefinitions.isEmpty()));
    }
}