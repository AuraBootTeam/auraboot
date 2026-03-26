package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 多租户索引管理器
 * 
 * 核心功能：
 * 1. 确保所有唯一性约束包含tenant_id字段
 * 2. 管理租户隔离的索引创建
 * 3. 验证索引的多租户一致性
 * 
 * @author AuraBoot Team
 * @since 2.1.2
 */
@Slf4j
@Service
public class MultiTenantIndexManager {
    
    /**
     * 生成多租户安全的索引DDL语句
     * 
     * 关键原则：
     * - 所有唯一索引必须包含tenant_id
     * - 普通索引建议包含tenant_id以提高查询性能
     * - 系统字段索引自动包含tenant_id
     * 
     * @param model 模型定义
     * @return 索引DDL语句列表
     */
    public List<String> generateMultiTenantIndexDDLs(ModelDefinition model) {
        List<String> indexDDLs = new ArrayList<>();
        String tableName = model.getTableName();
        
        // 1. 为唯一字段创建多租户唯一索引
        for (FieldDefinition field : model.getFields()) {
            if (field.isUnique() && !field.isPrimaryKey()) {
                String indexName = "idx_" + tableName + "_" + field.getColumnName() + "_tenant_unique";
                // 关键：唯一索引必须包含tenant_id
                String indexDDL = String.format(
                    "CREATE UNIQUE INDEX IF NOT EXISTS %s ON %s (tenant_id, %s)",
                    indexName, tableName, field.getColumnName()
                );
                indexDDLs.add(indexDDL);
                log.debug("Generated multi-tenant unique index: {}", indexName);
            }
        }
        
        // 2. 为常用查询字段创建复合索引（包含tenant_id）
        for (FieldDefinition field : model.getFields()) {
            // 为外键、状态字段等创建复合索引
            if (field.getCode().endsWith("_id") || 
                field.getCode().equals("status") || 
                field.getCode().equals("code")) {
                
                String indexName = "idx_" + tableName + "_tenant_" + field.getColumnName();
                // 复合索引：tenant_id在前，提高租户隔离查询性能
                String indexDDL = String.format(
                    "CREATE INDEX IF NOT EXISTS %s ON %s (tenant_id, %s)",
                    indexName, tableName, field.getColumnName()
                );
                indexDDLs.add(indexDDL);
                log.debug("Generated multi-tenant composite index: {}", indexName);
            }
        }
        
        // 3. 为系统字段创建多租户索引
        // tenant_id单独索引
        String tenantIndexName = "idx_" + tableName + "_tenant_id";
        String tenantIndexDDL = String.format(
            "CREATE INDEX IF NOT EXISTS %s ON %s (tenant_id)",
            tenantIndexName, tableName
        );
        indexDDLs.add(tenantIndexDDL);
        
        // tenant_id + created_at 复合索引（常用于时间范围查询）
        String tenantTimeIndexName = "idx_" + tableName + "_tenant_created";
        String tenantTimeIndexDDL = String.format(
            "CREATE INDEX IF NOT EXISTS %s ON %s (tenant_id, created_at)",
            tenantTimeIndexName, tableName
        );
        indexDDLs.add(tenantTimeIndexDDL);
        
        // tenant_id + updated_at 复合索引
        String tenantUpdateIndexName = "idx_" + tableName + "_tenant_updated";
        String tenantUpdateIndexDDL = String.format(
            "CREATE INDEX IF NOT EXISTS %s ON %s (tenant_id, updated_at)",
            tenantUpdateIndexName, tableName
        );
        indexDDLs.add(tenantUpdateIndexDDL);
        
        // 4. For searchable STRING/TEXT fields, create pg_trgm GIN indexes for ILIKE performance
        for (FieldDefinition field : model.getFields()) {
            if (field.isSearchable() && !field.isJsonbVirtual() && !field.isTransientField()) {
                String dt = field.getDataType() != null ? field.getDataType() : "";
                if ("string".equalsIgnoreCase(dt) || "text".equalsIgnoreCase(dt)
                        || "enum".equalsIgnoreCase(dt) || "dict".equalsIgnoreCase(dt)) {
                    String trgmIndexName = "idx_" + tableName + "_" + field.getColumnName() + "_trgm";
                    String trgmIndexDDL = String.format(
                        "CREATE INDEX IF NOT EXISTS %s ON %s USING GIN (%s gin_trgm_ops)",
                        trgmIndexName, tableName, field.getColumnName()
                    );
                    indexDDLs.add(trgmIndexDDL);
                    log.debug("Generated pg_trgm GIN index for searchable field: {}.{}", tableName, field.getColumnName());
                }
            }
        }

        log.info("Generated {} multi-tenant indexes for table: {}", indexDDLs.size(), tableName);
        return indexDDLs;
    }
    
    /**
     * 验证索引是否符合多租户隔离要求
     * 
     * @param indexDDL 索引DDL语句
     * @return 是否符合多租户要求
     */
    public boolean validateMultiTenantIndex(String indexDDL) {
        if (indexDDL == null || indexDDL.trim().isEmpty()) {
            return false;
        }
        
        String upperDDL = indexDDL.toUpperCase();
        
        // 唯一索引必须包含tenant_id
        if (upperDDL.contains("CREATE UNIQUE INDEX")) {
            if (!upperDDL.contains("TENANT_ID")) {
                log.warn("Unique index missing tenant_id: {}", indexDDL);
                return false;
            }
        }
        
        // 普通索引建议包含tenant_id（警告但不强制）
        if (upperDDL.contains("CREATE INDEX") && !upperDDL.contains("TENANT_ID")) {
            log.info("Regular index without tenant_id (acceptable but not optimal): {}", indexDDL);
        }
        
        return true;
    }
    
    /**
     * 修复不符合多租户要求的索引DDL
     * 
     * @param indexDDL 原始索引DDL
     * @return 修复后的索引DDL
     */
    public String fixMultiTenantIndex(String indexDDL) {
        if (indexDDL == null || indexDDL.trim().isEmpty()) {
            return indexDDL;
        }
        
        String upperDDL = indexDDL.toUpperCase();
        
        // 如果是唯一索引且缺少tenant_id，则添加
        if (upperDDL.contains("CREATE UNIQUE INDEX") && !upperDDL.contains("TENANT_ID")) {
            // 提取索引定义的列部分
            int openParen = indexDDL.indexOf('(');
            int closeParen = indexDDL.lastIndexOf(')');
            
            if (openParen > 0 && closeParen > openParen) {
                String before = indexDDL.substring(0, openParen + 1);
                String columns = indexDDL.substring(openParen + 1, closeParen);
                String after = indexDDL.substring(closeParen);
                
                // 在列列表前添加tenant_id
                String fixedDDL = before + "tenant_id, " + columns + after;
                log.info("Fixed multi-tenant index: {} -> {}", indexDDL, fixedDDL);
                return fixedDDL;
            }
        }
        
        return indexDDL;
    }
    
    /**
     * 生成多租户唯一约束DDL
     * 
     * @param tableName 表名
     * @param constraintName 约束名
     * @param columns 列名列表
     * @return 约束DDL
     */
    public String generateMultiTenantUniqueConstraint(String tableName, String constraintName, List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("Columns cannot be empty for unique constraint");
        }
        
        // 确保tenant_id在列列表中
        List<String> allColumns = new ArrayList<>();
        allColumns.add("tenant_id");
        allColumns.addAll(columns);
        
        String columnList = String.join(", ", allColumns);
        String ddl = String.format(
            "ALTER TABLE %s ADD CONSTRAINT %s UNIQUE (%s)",
            tableName, constraintName, columnList
        );
        
        log.debug("Generated multi-tenant unique constraint: {}", ddl);
        return ddl;
    }
    
    /**
     * 检查表的所有唯一约束是否包含tenant_id
     * 
     * @param tableName 表名
     * @param uniqueConstraints 唯一约束列表
     * @return 验证结果
     */
    public MultiTenantIndexValidationResult validateTableConstraints(
            String tableName, 
            List<UniqueConstraintInfo> uniqueConstraints) {
        
        MultiTenantIndexValidationResult result = new MultiTenantIndexValidationResult();
        result.setTableName(tableName);
        result.setValid(true);
        
        List<String> violations = new ArrayList<>();
        
        for (UniqueConstraintInfo constraint : uniqueConstraints) {
            boolean hasTenantId = constraint.getColumns() != null
                    && constraint.getColumns().stream()
                    .filter(Objects::nonNull)
                    .anyMatch(column -> "tenant_id".equalsIgnoreCase(column));
            if (!hasTenantId) {
                String violation = String.format(
                    "Unique constraint '%s' on columns %s missing tenant_id",
                    constraint.getConstraintName(),
                    constraint.getColumns()
                );
                violations.add(violation);
                result.setValid(false);
                log.warn("Multi-tenant violation in table {}: {}", tableName, violation);
            }
        }
        
        result.setViolations(violations);
        result.setViolationCount(violations.size());
        
        return result;
    }
    
    /**
     * 唯一约束信息
     */
    public static class UniqueConstraintInfo {
        private String constraintName;
        private List<String> columns;
        
        public UniqueConstraintInfo(String constraintName, List<String> columns) {
            this.constraintName = constraintName;
            this.columns = columns;
        }
        
        public String getConstraintName() {
            return constraintName;
        }
        
        public List<String> getColumns() {
            return columns;
        }
    }
    
    /**
     * 多租户索引验证结果
     */
    public static class MultiTenantIndexValidationResult {
        private String tableName;
        private boolean valid;
        private List<String> violations;
        private int violationCount;
        
        public String getTableName() {
            return tableName;
        }
        
        public void setTableName(String tableName) {
            this.tableName = tableName;
        }
        
        public boolean isValid() {
            return valid;
        }
        
        public void setValid(boolean valid) {
            this.valid = valid;
        }
        
        public List<String> getViolations() {
            return violations;
        }
        
        public void setViolations(List<String> violations) {
            this.violations = violations;
        }
        
        public int getViolationCount() {
            return violationCount;
        }
        
        public void setViolationCount(int violationCount) {
            this.violationCount = violationCount;
        }
    }
}
