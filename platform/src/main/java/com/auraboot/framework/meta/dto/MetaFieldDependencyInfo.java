package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;
import java.util.ArrayList;

/**
 * 字段依赖关系信息DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaFieldDependencyInfo {

    /**
     * 字段PID
     */
    private String fieldPid;

    /**
     * 字段键
     */
    private String code;

    /**
     * 依赖的字段列表
     */
    @Builder.Default
    private List<FieldDependency> dependencies = new ArrayList<>();

    /**
     * 被依赖的字段列表
     */
    @Builder.Default
    private List<FieldDependency> dependents = new ArrayList<>();

    /**
     * 依赖的数据源列表
     */
    @Builder.Default
    private List<DataSourceDependency> dataSourceDependencies = new ArrayList<>();

    /**
     * 依赖的字典列表
     */
    @Builder.Default
    private List<DictDependency> dictDependencies = new ArrayList<>();

    /**
     * 循环依赖检查结果
     */
    private Boolean hasCircularDependency;

    /**
     * 循环依赖路径
     */
    @Builder.Default
    private List<String> circularDependencyPath = new ArrayList<>();

    /**
     * 依赖深度
     */
    private Integer dependencyDepth;

    /**
     * 影响范围评估
     */
    private String impactScope;

    /**
     * 字段依赖信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FieldDependency {
        /**
         * 依赖字段PID
         */
        private String fieldPid;

        /**
         * 依赖字段键
         */
        private String code;

        /**
         * 依赖类型（引用、计算、验证等）
         */
        private String dependencyType;

        /**
         * 依赖强度（强依赖、弱依赖）
         */
        private String dependencyStrength;

        /**
         * 依赖描述
         */
        private String description;

        /**
         * 创建时间
         */
        private Long createdAt;
    }

    /**
     * 数据源依赖信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DataSourceDependency {
        /**
         * 数据源ID
         */
        private Long dataSourceId;

        /**
         * 数据源名称
         */
        private String dataSourceName;

        /**
         * 依赖类型
         */
        private String dependencyType;

        /**
         * 表名
         */
        private String tableName;

        /**
         * 列名
         */
        private String columnName;

        /**
         * 创建时间
         */
        private Long createdAt;
    }

    /**
     * 字典依赖信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DictDependency {
        /**
         * 字典PID
         */
        private String dictPid;

        /**
         * 字典编码
         */
        private String dictCode;

        /**
         * 字典显示名称
         */
        private String dictDisplayName;

        /**
         * 依赖类型
         */
        private String dependencyType;

        /**
         * 是否为强依赖
         */
        private Boolean isStrongDependency;

        /**
         * 创建时间
         */
        private Long createdAt;
    }

    /**
     * 添加字段依赖
     * @param fieldPid 依赖字段PID
     * @param code 依赖字段键
     * @param dependencyType 依赖类型
     * @param dependencyStrength 依赖强度
     * @param description 依赖描述
     */
    public void addDependency(String fieldPid, String code, String dependencyType, String dependencyStrength, String description) {
        if (dependencies == null) {
            dependencies = new ArrayList<>();
        }
        dependencies.add(FieldDependency.builder()
            .fieldPid(fieldPid)
            .code(code)
            .dependencyType(dependencyType)
            .dependencyStrength(dependencyStrength)
            .description(description)
            .createdAt(System.currentTimeMillis())
            .build());
    }

    /**
     * 添加被依赖字段
     * @param fieldPid 被依赖字段PID
     * @param code 被依赖字段键
     * @param dependencyType 依赖类型
     * @param dependencyStrength 依赖强度
     * @param description 依赖描述
     */
    public void addDependent(String fieldPid, String code, String dependencyType, String dependencyStrength, String description) {
        if (dependents == null) {
            dependents = new ArrayList<>();
        }
        dependents.add(FieldDependency.builder()
            .fieldPid(fieldPid)
            .code(code)
            .dependencyType(dependencyType)
            .dependencyStrength(dependencyStrength)
            .description(description)
            .createdAt(System.currentTimeMillis())
            .build());
    }

    /**
     * 添加数据源依赖
     * @param dataSourceId 数据源ID
     * @param dataSourceName 数据源名称
     * @param dependencyType 依赖类型
     * @param tableName 表名
     * @param columnName 列名
     */
    public void addDataSourceDependency(Long dataSourceId, String dataSourceName, String dependencyType, String tableName, String columnName) {
        if (dataSourceDependencies == null) {
            dataSourceDependencies = new ArrayList<>();
        }
        dataSourceDependencies.add(DataSourceDependency.builder()
            .dataSourceId(dataSourceId)
            .dataSourceName(dataSourceName)
            .dependencyType(dependencyType)
            .tableName(tableName)
            .columnName(columnName)
            .createdAt(System.currentTimeMillis())
            .build());
    }

    /**
     * 添加字典依赖
     * @param dictPid 字典PID
     * @param dictCode 字典编码
     * @param dictDisplayName 字典显示名称
     * @param dependencyType 依赖类型
     * @param isStrongDependency 是否为强依赖
     */
    public void addDictDependency(String dictPid, String dictCode, String dictDisplayName, String dependencyType, Boolean isStrongDependency) {
        if (dictDependencies == null) {
            dictDependencies = new ArrayList<>();
        }
        dictDependencies.add(DictDependency.builder()
            .dictPid(dictPid)
            .dictCode(dictCode)
            .dictDisplayName(dictDisplayName)
            .dependencyType(dependencyType)
            .isStrongDependency(isStrongDependency)
            .createdAt(System.currentTimeMillis())
            .build());
    }

    /**
     * 检查是否有依赖
     * @return 是否有依赖
     */
    public boolean hasDependencies() {
        return (dependencies != null && !dependencies.isEmpty()) ||
               (dataSourceDependencies != null && !dataSourceDependencies.isEmpty()) ||
               (dictDependencies != null && !dictDependencies.isEmpty());
    }

    /**
     * 检查是否被依赖
     * @return 是否被依赖
     */
    public boolean hasDependents() {
        return dependents != null && !dependents.isEmpty();
    }

    /**
     * 获取依赖总数
     * @return 依赖总数
     */
    public int getTotalDependencies() {
        int count = 0;
        if (dependencies != null) count += dependencies.size();
        if (dataSourceDependencies != null) count += dataSourceDependencies.size();
        if (dictDependencies != null) count += dictDependencies.size();
        return count;
    }

    /**
     * 获取被依赖总数
     * @return 被依赖总数
     */
    public int getTotalDependents() {
        return dependents != null ? dependents.size() : 0;
    }
}