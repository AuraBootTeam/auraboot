package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 命名查询依赖信息DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryDependencyInfo {

    /**
     * 查询ID
     */
    private Long queryId;

    /**
     * 查询编码
     */
    private String queryCode;

    /**
     * 分析时间
     */
    private LocalDateTime analysisTime;

    /**
     * 数据库依赖
     */
    private List<DatabaseDependency> databaseDependencies;

    /**
     * 表依赖
     */
    private List<TableDependency> tableDependencies;

    /**
     * 字段依赖
     */
    private List<FieldDependency> fieldDependencies;

    /**
     * 字典依赖
     */
    private List<DictDependency> dictDependencies;

    /**
     * 查询依赖（引用其他查询）
     */
    private List<QueryDependency> queryDependencies;

    /**
     * 被依赖信息（被其他查询引用）
     */
    private List<QueryReference> queryReferences;

    /**
     * 权限依赖
     */
    private List<PermissionDependency> permissionDependencies;

    /**
     * 配置依赖
     */
    private List<ConfigDependency> configDependencies;

    /**
     * 依赖风险评估
     */
    private DependencyRiskAssessment riskAssessment;

    /**
     * 数据库依赖内部类
     */
    @Data
    public static class DatabaseDependency {
        private String databaseName;
        private String databaseType;
        private String connectionInfo;
        private String dependencyType; // READ, WRITE, READ_WRITE
        private Boolean isRequired;
        private String riskLevel; // LOW, MEDIUM, HIGH
    }

    /**
     * 表依赖内部类
     */
    @Data
    public static class TableDependency {
        private String tableName;
        private String schemaName;
        private String tableType; // TABLE, VIEW, MATERIALIZED_VIEW
        private String accessType; // SELECT, INSERT, UPDATE, DELETE
        private Boolean isRequired;
        private Long estimatedRowCount;
        private String riskLevel;
    }

    /**
     * 字段依赖内部类
     */
    @Data
    public static class FieldDependency {
        private String tableName;
        private String fieldName;
        private String fieldType;
        private String usageType; // SELECT, WHERE, ORDER_BY, GROUP_BY
        private Boolean isRequired;
        private Boolean hasIndex;
        private String riskLevel;
    }

    /**
     * 字典依赖内部类
     */
    @Data
    public static class DictDependency {
        private String dictCode;
        private String dictName;
        private String dependencyType; // VALUE_MAPPING, VALIDATION, DISPLAY
        private Boolean isRequired;
        private Integer itemCount;
        private String riskLevel;
    }

    /**
     * 查询依赖内部类
     */
    @Data
    public static class QueryDependency {
        private Long dependentQueryId;
        private String dependentQueryCode;
        private String dependencyType; // SUBQUERY, JOIN, UNION, REFERENCE
        private Boolean isRequired;
        private String riskLevel;
    }

    /**
     * 查询引用内部类
     */
    @Data
    public static class QueryReference {
        private Long referencingQueryId;
        private String referencingQueryCode;
        private String referenceType; // SUBQUERY, JOIN, UNION, REFERENCE
        private LocalDateTime lastReferenced;
        private String riskLevel;
    }

    /**
     * 权限依赖内部类
     */
    @Data
    public static class PermissionDependency {
        private String permissionCode;
        private String permissionName;
        private String resourceType; // TABLE, FIELD, OPERATION
        private String resourceName;
        private String accessLevel; // READ, WRITE, ADMIN
        private Boolean isRequired;
        private String riskLevel;
    }

    /**
     * 配置依赖内部类
     */
    @Data
    public static class ConfigDependency {
        private String configKey;
        private String configValue;
        private String configType; // SYSTEM, TENANT, USER
        private String dependencyType; // REQUIRED, OPTIONAL, CONDITIONAL
        private String riskLevel;
    }

    /**
     * 依赖风险评估内部类
     */
    @Data
    public static class DependencyRiskAssessment {
        private String overallRiskLevel; // LOW, MEDIUM, HIGH, CRITICAL
        private Integer riskScore; // 0-100
        private List<String> riskFactors;
        private List<String> mitigationSuggestions;
        private Integer dependencyComplexity; // 1-10
        private Boolean hasCircularDependencies;
        private Integer maxDependencyDepth;
        private String stabilityRating; // STABLE, MODERATE, UNSTABLE
    }
}