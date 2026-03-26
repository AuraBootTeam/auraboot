package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * 模式差异结果
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class SchemaDiffResult {
    
    /**
     * 模型编码
     */
    private String modelCode;
    
    /**
     * 是否有差异
     */
    private Boolean hasDifferences;
    
    /**
     * 表差异
     */
    private TableDiff tableDiff;
    
    /**
     * 字段差异列表
     */
    private List<FieldDiff> fieldDiffs;
    
    /**
     * 索引差异列表
     */
    private List<IndexDiff> indexDiffs;
    
    /**
     * 约束差异列表
     */
    private List<ConstraintDiff> constraintDiffs;
    
    @Data
    @Builder
    public static class TableDiff {
        private DiffType type;
        private String tableName;
        private String message;
    }
    
    @Data
    @Builder
    public static class FieldDiff {
        private DiffType type;
        private String fieldName;
        private String columnName;
        private String oldType;
        private String newType;
        private String message;
    }
    
    @Data
    @Builder
    public static class IndexDiff {
        private DiffType type;
        private String indexName;
        private List<String> fields;
        private String message;
    }
    
    @Data
    @Builder
    public static class ConstraintDiff {
        private DiffType type;
        private String constraintName;
        private String constraintType;
        private String message;
    }
    
    public enum DiffType {
        ADDED,      // 新增
        MODIFIED,   // 修改
        REMOVED,    // 删除
        UNCHANGED   // 无变化
    }
}