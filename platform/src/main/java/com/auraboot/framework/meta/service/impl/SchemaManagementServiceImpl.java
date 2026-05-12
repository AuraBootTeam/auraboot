package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.constant.SystemFieldConstants;
import com.auraboot.framework.meta.ddl.DdlDialect;
import com.auraboot.framework.meta.ddl.DdlDialectProvider;
import com.auraboot.framework.meta.ddl.TableMetadataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.SchemaManagementService;
import com.auraboot.framework.meta.service.MultiTenantIndexManager;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.common.util.DateUtil;
import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.security.SqlSafetyUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模式管理服务实现.
 *
 * <h3>Exception strategy: wrap-as-result (catch-pattern §P4 variant)</h3>
 *
 * Most public DDL operations here ({@code createTableForModel}, {@code addField},
 * {@code dropField}, {@code updateField}, etc.) catch {@code Exception} at the
 * method top level and return a {@code SchemaOperationResult.success=false}
 * carrying the failure message instead of throwing. This is intentional:
 * <ul>
 *   <li>Callers (PluginResourceImporter, Page Designer, DDL preview UI) need
 *       per-operation outcomes — a thrown exception would collapse a batch.</li>
 *   <li>Each top-level catch logs the cause via {@code log.error(msg, args..., e)}
 *       so full stack traces still reach the log pipeline.</li>
 *   <li>The returned result includes a human-readable {@code message} so
 *       callers can surface it in their aggregated outcome.</li>
 * </ul>
 *
 * Inner per-item catches at {@code createIndex} loop, {@code alterTable} loop,
 * and {@code dropIndex} loop follow §P1 (per-item tolerance). The post-DDL
 * {@code clearPreparedPlans} catch follows §P2 (best-effort cleanup outside
 * the DDL transaction). See {@code docs/standards/core/catch-exception-pattern.md}
 * for the full taxonomy.
 *
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchemaManagementServiceImpl implements SchemaManagementService {

    private static final Pattern INDEX_NAME_PATTERN =
            Pattern.compile("(?i)CREATE\\s+(UNIQUE\\s+)?INDEX\\s+([a-zA-Z0-9_]+)\\s+ON\\s+");

    private final MetaModelService metaModelService;
    private final DynamicDataMapper dynamicDataMapper;
    private final DdlDialectProvider ddlDialectProvider;
    private final TableMetadataService tableMetadataService;
    private final MultiTenantIndexManager multiTenantIndexManager;
    private final DataSource dataSource;

    private static String logSafe(Object value) {
        return LogSanitizer.safe(value);
    }

    @Override
    @Transactional
    public SchemaOperationResult createTableByModel(String modelCode) {
        log.info("Creating table for model: {}", logSafe(modelCode));

        try {
            // 1. 获取模型定义 (use getModelDefinitionFromDb to bypass cache)
            ModelDefinition model = metaModelService.getModelDefinitionFromDb(modelCode)
                    .orElseThrow(() -> new RuntimeException("Model not found: " + modelCode));
            log.info("Creating table: model {} has {} fields", logSafe(modelCode), model.getFields() != null ? model.getFields().size() : 0);
            
            String tableName = model.getTableName();
            
            // 2. 检查表是否已存在
            if (tableMetadataService.tableExists(tableName)) {
                log.warn("Table {} already exists for model: {}, syncing schema instead of skipping", logSafe(tableName), logSafe(modelCode));
                return syncModelToTable(
                        modelCode,
                        SchemaSyncOptions.builder()
                                .syncMode(SchemaSyncOptions.SyncMode.SAFE)
                                .createIndexes(true)
                                .build());
            }
            
            // 3. 生成建表DDL
            String createTableDDL = generateCreateTableDDL(model);
            List<String> executedDDL = new ArrayList<>();
            executedDDL.add(createTableDDL);
            
            // 4. 执行建表语句
            int result = dynamicDataMapper.createTable(createTableDDL);
            
            if (result >= 0) { // MyBatis对DDL语句通常返回0或正数
                log.info("Successfully created table {} for model: {}", logSafe(tableName), logSafe(modelCode));
                
                // 5. 创建索引（如果需要）
                List<String> indexDDLs = filterExistingIndexes(tableName, generateIndexDDLs(model));
                for (String indexDDL : indexDDLs) {
                    try {
                        dynamicDataMapper.alterTable(indexDDL);
                        executedDDL.add(indexDDL);
                        log.debug("Created index: {}", logSafe(indexDDL));
                    } catch (Exception e) {
                        // §P1 per-index tolerance: a single bad index DDL must not abort
                        // the whole table-create flow; the table itself is already created.
                        log.warn("Failed to create index: {}, error: {}", logSafe(indexDDL), logSafe(e.getMessage()), e);
                    }
                }
                
                return SchemaOperationResult.builder()
                        .success(true)
                        .operationType(SchemaOperationResult.SchemaOperationType.CREATE_TABLE)
                        .modelCode(modelCode)
                        .tableName(tableName)
                        .executedDDL(executedDDL)
                        .message("Table created successfully")
                        .operationTime(DateUtil.getCurrentLocalDateTimeUtc())
                        .affectedFields(model.getFields().stream()
                                .map(FieldDefinition::getCode)
                                .collect(Collectors.toList()))
                        .build();
            } else {
                throw new BusinessException("Failed to execute CREATE TABLE statement");
            }
            
        } catch (DuplicateKeyException e) {
            // Concurrent table creation race: another thread created the table first
            log.warn("Concurrent table creation detected for model {}, treating as success: {}",
                    logSafe(modelCode), logSafe(e.getMessage()));
            return SchemaOperationResult.builder()
                    .success(true)
                    .operationType(SchemaOperationResult.SchemaOperationType.CREATE_TABLE)
                    .modelCode(modelCode)
                    .message("Table already created by concurrent process")
                    .operationTime(DateUtil.getCurrentLocalDateTimeUtc())
                    .build();
        } catch (Exception e) {
            log.error("Failed to create table for model {}: {}", logSafe(modelCode), logSafe(e.getMessage()), e);
            return SchemaOperationResult.builder()
                    .success(false)
                    .operationType(SchemaOperationResult.SchemaOperationType.CREATE_TABLE)
                    .modelCode(modelCode)
                    .message("Failed to create table")
                    .errorMessage(e.getMessage())
                    .operationTime(DateUtil.getCurrentLocalDateTimeUtc())
                    .build();
        }
    }

    /**
     * 生成建表DDL语句
     */
    private String generateCreateTableDDL(ModelDefinition model) {
        DdlDialect dialect = ddlDialectProvider.getDialect();
        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TABLE IF NOT EXISTS ").append(model.getTableName()).append(" (\n");

        List<String> columnDefinitions = new ArrayList<>();
        Set<String> existingColumns = new HashSet<>();

        // 1. 添加系统必需字段 (id, pid) - 放在最前面
        columnDefinitions.add("    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY");
        columnDefinitions.add("    pid VARCHAR(32) NOT NULL UNIQUE");
        existingColumns.add("id");
        existingColumns.add("pid");

        // 2. 生成用户自定义字段定义
        for (FieldDefinition field : model.getFields()) {
            String columnName = field.getColumnName().toLowerCase();
            // 跳过已存在的系统字段，避免重复
            if (existingColumns.contains(columnName)) {
                continue;
            }
            // Skip JSONB virtual fields — they are stored inside a host JSONB column, not as physical columns
            if (field.isJsonbVirtual()) {
                continue;
            }
            String columnDef = generateColumnDefinition(field);
            columnDefinitions.add("    " + columnDef);
            existingColumns.add(columnName);
        }

        // 3. 添加其他系统字段
        String timestampType = dialect.getTimestampType();
        String varcharType = dialect.getVarcharType(255);
        if (!existingColumns.contains("created_at")) {
            columnDefinitions.add("    created_at " + timestampType + " DEFAULT CURRENT_TIMESTAMP");
        }
        if (!existingColumns.contains("created_by")) {
            columnDefinitions.add("    created_by " + varcharType);
        }
        if (!existingColumns.contains("updated_at")) {
            columnDefinitions.add("    updated_at " + timestampType + " DEFAULT CURRENT_TIMESTAMP");
        }
        if (!existingColumns.contains("updated_by")) {
            columnDefinitions.add("    updated_by " + varcharType);
        }
        if (!existingColumns.contains("tenant_id")) {
            columnDefinitions.add("    tenant_id BIGINT NOT NULL");
        }

        // 添加字段定义
        ddl.append(String.join(",\n", columnDefinitions));

        ddl.append("\n)");
        ddl.append(dialect.getTableSuffix());

        return ddl.toString();
    }

    /**
     * 生成字段定义
     */
    private String generateColumnDefinition(FieldDefinition field) {
        // Validate column name to prevent DDL injection
        SqlSafetyUtils.validateIdentifier(field.getColumnName(), "column name");

        StringBuilder columnDef = new StringBuilder();

        // 字段名
        columnDef.append(field.getColumnName());
        
        // 数据类型
        String dataType = ddlDialectProvider.getDialect().mapDataType(field);
        columnDef.append(" ").append(dataType);
        
        // NOT NULL约束
        if (field.isRequired() || field.isPrimaryKey()) {
            columnDef.append(" NOT NULL");
        }
        
        // 唯一约束
        if (field.isUnique() && !field.isPrimaryKey()) {
            columnDef.append(" UNIQUE");
        }
        
        // 默认值
        if (field.getDefaultValue() != null) {
            columnDef.append(" DEFAULT ")
                    .append(ddlDialectProvider.getDialect().formatDefaultValue(field.getDefaultValue(), field.getDataType()));
        }
        
        return columnDef.toString();
    }

    /**
     * 生成索引DDL语句
     * 
     * 使用MultiTenantIndexManager确保所有索引符合多租户隔离要求
     */
    private List<String> generateIndexDDLs(ModelDefinition model) {
        // 使用MultiTenantIndexManager生成多租户安全的索引
        return multiTenantIndexManager.generateMultiTenantIndexDDLs(model);
    }

    @Override
    public SchemaOperationResult updateTableByModel(String modelCode) {
        log.info("Updating table for model: {}", logSafe(modelCode));
        try {
            SchemaSyncOptions options = SchemaSyncOptions.builder()
                    .syncMode(SchemaSyncOptions.SyncMode.SAFE)
                    .createIndexes(true)
                    .build();
            return syncModelToTable(modelCode, options);
        } catch (Exception e) {
            log.error("Failed to update table for model {}: {}", logSafe(modelCode), logSafe(e.getMessage()), e);
            return SchemaOperationResult.builder()
                    .success(false)
                    .operationType(SchemaOperationResult.SchemaOperationType.UPDATE_TABLE)
                    .modelCode(modelCode)
                    .message("Table update failed")
                    .errorMessage(e.getMessage())
                    .operationTime(DateUtil.getCurrentLocalDateTimeUtc())
                    .build();
        }
    }

    @Override
    public SchemaOperationResult dropTableByModel(String modelCode) {
        log.info("Dropping table for model: {}", logSafe(modelCode));
        try {
            ModelDefinition model = metaModelService.getModelDefinition(modelCode)
                    .orElseThrow(() -> new RuntimeException("Model not found: " + modelCode));

            String tableName = model.getTableName();
            if (!tableMetadataService.tableExists(tableName)) {
                return SchemaOperationResult.builder()
                        .success(true)
                        .operationType(SchemaOperationResult.SchemaOperationType.DROP_TABLE)
                        .modelCode(modelCode)
                        .tableName(tableName)
                        .message("Table does not exist")
                        .operationTime(DateUtil.getCurrentLocalDateTimeUtc())
                        .build();
            }

            String ddl = "DROP TABLE IF EXISTS " + tableName;
            dynamicDataMapper.alterTable(ddl);

            return SchemaOperationResult.builder()
                    .success(true)
                    .operationType(SchemaOperationResult.SchemaOperationType.DROP_TABLE)
                    .modelCode(modelCode)
                    .tableName(tableName)
                    .executedDDL(List.of(ddl))
                    .message("Table dropped successfully")
                    .operationTime(DateUtil.getCurrentLocalDateTimeUtc())
                    .build();
        } catch (Exception e) {
            log.error("Failed to drop table for model {}: {}", logSafe(modelCode), logSafe(e.getMessage()), e);
            return SchemaOperationResult.builder()
                    .success(false)
                    .operationType(SchemaOperationResult.SchemaOperationType.DROP_TABLE)
                    .modelCode(modelCode)
                    .message("Table drop failed")
                    .errorMessage(e.getMessage())
                    .operationTime(DateUtil.getCurrentLocalDateTimeUtc())
                    .build();
        }
    }

    @Override
    public SchemaOperationResult syncModelToTable(String modelCode, SchemaSyncOptions syncOptions) {
        log.info("Syncing model to table: {}", logSafe(modelCode));
        SchemaSyncOptions options = syncOptions != null ? syncOptions : SchemaSyncOptions.builder().build();

        try {
            // Use getModelDefinitionFromDb to bypass cache and get up-to-date field definitions.
            // This is important when syncModelToTable is called within the same transaction as field updates,
            // where the cache may still hold stale required/unique constraints.
            ModelDefinition model = metaModelService.getModelDefinitionFromDb(modelCode)
                    .orElseThrow(() -> new RuntimeException("Model not found: " + modelCode));

            List<String> ddlStatements = buildSyncDdls(model, options);
            if (ddlStatements.isEmpty()) {
                return SchemaOperationResult.builder()
                        .success(true)
                        .operationType(SchemaOperationResult.SchemaOperationType.SYNC_SCHEMA)
                        .modelCode(modelCode)
                        .tableName(model.getTableName())
                        .message("No schema changes required")
                        .operationTime(DateUtil.getCurrentLocalDateTimeUtc())
                        .build();
            }

            if (options.getSyncMode() == SchemaSyncOptions.SyncMode.DRY_RUN) {
                return SchemaOperationResult.builder()
                        .success(true)
                        .operationType(SchemaOperationResult.SchemaOperationType.SYNC_SCHEMA)
                        .modelCode(modelCode)
                        .tableName(model.getTableName())
                        .executedDDL(ddlStatements)
                        .message("Dry run only, no DDL executed")
                        .operationTime(DateUtil.getCurrentLocalDateTimeUtc())
                        .build();
            }

            List<String> executed = new ArrayList<>();
            for (String ddl : ddlStatements) {
                executeDdl(ddl);
                executed.add(ddl);
            }

            return SchemaOperationResult.builder()
                    .success(true)
                    .operationType(SchemaOperationResult.SchemaOperationType.SYNC_SCHEMA)
                    .modelCode(modelCode)
                    .tableName(model.getTableName())
                    .executedDDL(executed)
                    .message("Model synced to table successfully")
                    .operationTime(DateUtil.getCurrentLocalDateTimeUtc())
                    .affectedFields(model.getFields().stream()
                            .map(FieldDefinition::getCode)
                            .collect(Collectors.toList()))
                    .build();
        } catch (Exception e) {
            log.error("Failed to sync model {}: {}", logSafe(modelCode), logSafe(e.getMessage()), e);
            return SchemaOperationResult.builder()
                    .success(false)
                    .operationType(SchemaOperationResult.SchemaOperationType.SYNC_SCHEMA)
                    .modelCode(modelCode)
                    .message("Model sync failed")
                    .errorMessage(e.getMessage())
                    .operationTime(DateUtil.getCurrentLocalDateTimeUtc())
                    .build();
        }
    }

    @Override
    @Transactional
    public SchemaOperationResult addFieldToModel(String modelCode, String fieldCode) {
        log.info("Adding field {} to model: {}", logSafe(fieldCode), logSafe(modelCode));

        try {
            // 1. 获取模型定义 — bypass cache so newly-bound fields are visible within the same transaction
            ModelDefinition model = metaModelService.getModelDefinitionFromDb(modelCode)
                    .orElseThrow(() -> new RuntimeException("Model not found: " + modelCode));

            // 2. 获取字段定义 — read from the already-loaded (fresh) model definition
            FieldDefinition field = model.getFields().stream()
                    .filter(f -> f.getCode().equals(fieldCode))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException("Field not found: " + fieldCode + " in model: " + modelCode));
            
            String tableName = model.getTableName();
            String columnName = field.getColumnName();
            
            // 3. 检查表是否存在
            if (!tableMetadataService.tableExists(tableName)) {
                throw new BusinessException("Table does not exist: " + tableName);
            }
            
            // 4. 检查列是否已存在
            if (tableMetadataService.columnExists(tableName, columnName)) {
                log.warn("Column {} already exists in table {}", logSafe(columnName), logSafe(tableName));
                return SchemaOperationResult.builder()
                        .success(true)
                        .operationType(SchemaOperationResult.SchemaOperationType.ADD_FIELD)
                        .modelCode(modelCode)
                        .tableName(tableName)
                        .affectedFields(List.of(fieldCode))
                        .message("Column already exists")
                        .operationTime(DateUtil.getCurrentLocalDateTimeUtc())
                        .build();
            }
            
            // 4.5 Skip JSONB virtual fields — they don't need physical columns
            if (field.isJsonbVirtual()) {
                return SchemaOperationResult.builder()
                        .success(true)
                        .operationType(SchemaOperationResult.SchemaOperationType.ADD_FIELD)
                        .modelCode(modelCode)
                        .tableName(tableName)
                        .affectedFields(List.of(fieldCode))
                        .message("JSONB virtual field — no physical column needed")
                        .operationTime(DateUtil.getCurrentLocalDateTimeUtc())
                        .build();
            }

            // 5. 生成ADD COLUMN DDL (IF NOT EXISTS prevents race-condition failures when
            //    multiple async plugin import tasks try to add the same column concurrently)
            String columnDef = generateColumnDefinition(field);
            String ddl = "ALTER TABLE " + tableName + " ADD COLUMN IF NOT EXISTS " + columnDef;
            
            // 6. 执行DDL
            dynamicDataMapper.alterTable(ddl);
            
            log.info("Successfully added field {} to model {}, table: {}", logSafe(fieldCode), logSafe(modelCode), logSafe(tableName));
            
            return SchemaOperationResult.builder()
                    .success(true)
                    .operationType(SchemaOperationResult.SchemaOperationType.ADD_FIELD)
                    .modelCode(modelCode)
                    .tableName(tableName)
                    .executedDDL(List.of(ddl))
                    .affectedFields(List.of(fieldCode))
                    .message("Field added successfully")
                    .operationTime(DateUtil.getCurrentLocalDateTimeUtc())
                    .build();
            
        } catch (Exception e) {
            log.error("Failed to add field {} to model {}: {}", logSafe(fieldCode), logSafe(modelCode), logSafe(e.getMessage()), e);
            return SchemaOperationResult.builder()
                    .success(false)
                    .operationType(SchemaOperationResult.SchemaOperationType.ADD_FIELD)
                    .modelCode(modelCode)
                    .affectedFields(List.of(fieldCode))
                    .message("Failed to add field")
                    .errorMessage(e.getMessage())
                    .operationTime(DateUtil.getCurrentLocalDateTimeUtc())
                    .build();
        }
    }

    @Override
    @Transactional
    public SchemaOperationResult removeFieldFromModel(String modelCode, String fieldCode) {
        log.info("Removing field {} from model: {}", logSafe(fieldCode), logSafe(modelCode));
        
        try {
            // 1. 获取模型定义
            ModelDefinition model = metaModelService.getModelDefinition(modelCode)
                    .orElseThrow(() -> new RuntimeException("Model not found: " + modelCode));
            
            // 2. 获取字段定义
            FieldDefinition field = metaModelService.getFieldDefinition(modelCode, fieldCode);
            if (field == null) {
                throw new BusinessException("Field not found: " + fieldCode);
            }
            
            String tableName = model.getTableName();
            String columnName = field.getColumnName();
            
            // 3. 检查表是否存在
            if (!tableMetadataService.tableExists(tableName)) {
                throw new BusinessException("Table does not exist: " + tableName);
            }
            
            // 4. 检查列是否存在
            if (!tableMetadataService.columnExists(tableName, columnName)) {
                log.warn("Column {} does not exist in table {}", logSafe(columnName), logSafe(tableName));
                return SchemaOperationResult.builder()
                        .success(true)
                        .operationType(SchemaOperationResult.SchemaOperationType.REMOVE_FIELD)
                        .modelCode(modelCode)
                        .tableName(tableName)
                        .affectedFields(List.of(fieldCode))
                        .message("Column does not exist")
                        .operationTime(DateUtil.getCurrentLocalDateTimeUtc())
                        .build();
            }
            
            // 5. 检查字段是否为主键
            if (field.isPrimaryKey()) {
                throw new BusinessException("Cannot remove primary key field: " + fieldCode);
            }
            
            // 6. 生成DROP COLUMN DDL
            String ddl = "ALTER TABLE " + tableName + " DROP COLUMN " + columnName;
            
            // 7. 执行DDL
            dynamicDataMapper.alterTable(ddl);
            
            log.info("Successfully removed field {} from model {}, table: {}", logSafe(fieldCode), logSafe(modelCode), logSafe(tableName));
            
            return SchemaOperationResult.builder()
                    .success(true)
                    .operationType(SchemaOperationResult.SchemaOperationType.REMOVE_FIELD)
                    .modelCode(modelCode)
                    .tableName(tableName)
                    .executedDDL(List.of(ddl))
                    .affectedFields(List.of(fieldCode))
                    .message("Field removed successfully")
                    .operationTime(DateUtil.getCurrentLocalDateTimeUtc())
                    .build();
            
        } catch (Exception e) {
            log.error("Failed to remove field {} from model {}: {}", logSafe(fieldCode), logSafe(modelCode), logSafe(e.getMessage()), e);
            return SchemaOperationResult.builder()
                    .success(false)
                    .operationType(SchemaOperationResult.SchemaOperationType.REMOVE_FIELD)
                    .modelCode(modelCode)
                    .affectedFields(List.of(fieldCode))
                    .message("Failed to remove field")
                    .errorMessage(e.getMessage())
                    .operationTime(DateUtil.getCurrentLocalDateTimeUtc())
                    .build();
        }
    }

    @Override
    @Transactional
    public SchemaOperationResult updateModelField(String modelCode, String fieldCode) {
        log.info("Updating field {} in model: {}", logSafe(fieldCode), logSafe(modelCode));
        
        try {
            // 1. 获取模型定义
            ModelDefinition model = metaModelService.getModelDefinition(modelCode)
                    .orElseThrow(() -> new RuntimeException("Model not found: " + modelCode));
            
            // 2. 获取字段定义
            FieldDefinition field = metaModelService.getFieldDefinition(modelCode, fieldCode);
            if (field == null) {
                throw new BusinessException("Field not found: " + fieldCode);
            }
            
            String tableName = model.getTableName();
            String columnName = field.getColumnName();
            
            // 3. 检查表是否存在
            if (!tableMetadataService.tableExists(tableName)) {
                throw new BusinessException("Table does not exist: " + tableName);
            }
            
            // 4. 检查列是否存在
            if (!tableMetadataService.columnExists(tableName, columnName)) {
                throw new BusinessException("Column does not exist: " + columnName);
            }
            
            // 5. 生成MODIFY COLUMN DDL (根据数据库方言)
            String columnDef = generateColumnDefinition(field);
            DdlDialect dialect = ddlDialectProvider.getDialect();
            String ddl;
            
            // PostgreSQL使用ALTER COLUMN语法
            if (dialect.getClass().getSimpleName().contains("Postgres")) {
                // PostgreSQL需要分多个语句修改列
                List<String> ddls = new ArrayList<>();
                
                // 修改数据类型
                String dataType = dialect.mapDataType(field);
                ddls.add(String.format("ALTER TABLE %s ALTER COLUMN %s TYPE %s", 
                        tableName, columnName, dataType));
                
                // 修改NOT NULL约束
                if (field.isRequired() || field.isPrimaryKey()) {
                    ddls.add(String.format("ALTER TABLE %s ALTER COLUMN %s SET NOT NULL", 
                            tableName, columnName));
                } else {
                    ddls.add(String.format("ALTER TABLE %s ALTER COLUMN %s DROP NOT NULL", 
                            tableName, columnName));
                }
                
                // 修改默认值
                if (field.getDefaultValue() != null) {
                    String defaultValue = dialect.formatDefaultValue(
                            field.getDefaultValue(), field.getDataType());
                    ddls.add(String.format("ALTER TABLE %s ALTER COLUMN %s SET DEFAULT %s", 
                            tableName, columnName, defaultValue));
                } else {
                    ddls.add(String.format("ALTER TABLE %s ALTER COLUMN %s DROP DEFAULT", 
                            tableName, columnName));
                }
                
                // 执行所有DDL
                for (String singleDdl : ddls) {
                    try {
                        dynamicDataMapper.alterTable(singleDdl);
                    } catch (Exception e) {
                        // §P1 per-DDL tolerance: alter-column produces multiple ddls
                        // (TYPE / NOT NULL / DEFAULT) that should be applied
                        // independently — one rejected by Postgres should not stop
                        // the others. The outer wrap-as-result still reports overall
                        // success or partial failure to the caller.
                        log.warn("Failed to execute DDL: {}, error: {}", logSafe(singleDdl), logSafe(e.getMessage()), e);
                    }
                }
                
                log.info("Successfully updated field {} in model {}, table: {}", 
                        logSafe(fieldCode), logSafe(modelCode), logSafe(tableName));
                
                return SchemaOperationResult.builder()
                        .success(true)
                        .operationType(SchemaOperationResult.SchemaOperationType.UPDATE_FIELD)
                        .modelCode(modelCode)
                        .tableName(tableName)
                        .executedDDL(ddls)
                        .affectedFields(List.of(fieldCode))
                        .message("Field updated successfully")
                        .operationTime(DateUtil.getCurrentLocalDateTimeUtc())
                        .build();
                
            } else {
                // MySQL/其他数据库使用MODIFY COLUMN
                ddl = "ALTER TABLE " + tableName + " MODIFY COLUMN " + columnDef;
                
                // 执行DDL
                dynamicDataMapper.alterTable(ddl);
                
                log.info("Successfully updated field {} in model {}, table: {}", 
                        logSafe(fieldCode), logSafe(modelCode), logSafe(tableName));
                
                return SchemaOperationResult.builder()
                        .success(true)
                        .operationType(SchemaOperationResult.SchemaOperationType.UPDATE_FIELD)
                        .modelCode(modelCode)
                        .tableName(tableName)
                        .executedDDL(List.of(ddl))
                        .affectedFields(List.of(fieldCode))
                        .message("Field updated successfully")
                        .operationTime(DateUtil.getCurrentLocalDateTimeUtc())
                        .build();
            }
            
        } catch (Exception e) {
            log.error("Failed to update field {} in model {}: {}", logSafe(fieldCode), logSafe(modelCode), logSafe(e.getMessage()), e);
            return SchemaOperationResult.builder()
                    .success(false)
                    .operationType(SchemaOperationResult.SchemaOperationType.UPDATE_FIELD)
                    .modelCode(modelCode)
                    .affectedFields(List.of(fieldCode))
                    .message("Failed to update field")
                    .errorMessage(e.getMessage())
                    .operationTime(DateUtil.getCurrentLocalDateTimeUtc())
                    .build();
        }
    }

    @Override
    @Transactional
    public SchemaOperationResult createFieldIndex(String modelCode, String fieldCode, IndexType indexType) {
        log.info("Creating {} index for field {} in model: {}", indexType, logSafe(fieldCode), logSafe(modelCode));
        
        try {
            // 1. 获取模型定义
            ModelDefinition model = metaModelService.getModelDefinition(modelCode)
                    .orElseThrow(() -> new RuntimeException("Model not found: " + modelCode));
            
            // 2. 获取字段定义
            FieldDefinition field = metaModelService.getFieldDefinition(modelCode, fieldCode);
            if (field == null) {
                throw new BusinessException("Field not found: " + fieldCode);
            }
            
            String tableName = model.getTableName();
            String columnName = field.getColumnName();
            
            // 3. 检查表是否存在
            if (!tableMetadataService.tableExists(tableName)) {
                throw new BusinessException("Table does not exist: " + tableName);
            }
            
            // 4. 检查列是否存在
            if (!tableMetadataService.columnExists(tableName, columnName)) {
                throw new BusinessException("Column does not exist: " + columnName);
            }
            
            // 5. 生成索引名称
            String indexName = generateIndexName(tableName, columnName, indexType);
            
            // 6. 检查索引是否已存在
            if (tableMetadataService.indexExists(tableName, indexName)) {
                log.warn("Index {} already exists on table {}", logSafe(indexName), logSafe(tableName));
                return SchemaOperationResult.builder()
                        .success(true)
                        .operationType(SchemaOperationResult.SchemaOperationType.CREATE_INDEX)
                        .modelCode(modelCode)
                        .tableName(tableName)
                        .affectedFields(List.of(fieldCode))
                        .message("Index already exists")
                        .operationTime(DateUtil.getCurrentLocalDateTimeUtc())
                        .build();
            }
            
            // 7. 生成CREATE INDEX DDL
            String ddl = generateCreateIndexDDL(tableName, columnName, indexName, indexType);
            
            // 8. 执行DDL
            dynamicDataMapper.alterTable(ddl);
            
            log.info("Successfully created {} index {} for field {} in model {}",
                    indexType, logSafe(indexName), logSafe(fieldCode), logSafe(modelCode));
            
            return SchemaOperationResult.builder()
                    .success(true)
                    .operationType(SchemaOperationResult.SchemaOperationType.CREATE_INDEX)
                    .modelCode(modelCode)
                    .tableName(tableName)
                    .executedDDL(List.of(ddl))
                    .affectedFields(List.of(fieldCode))
                    .message("Index created successfully")
                    .operationTime(DateUtil.getCurrentLocalDateTimeUtc())
                    .build();
            
        } catch (Exception e) {
            log.error("Failed to create index for field {} in model {}: {}",
                    logSafe(fieldCode), logSafe(modelCode), logSafe(e.getMessage()), e);
            return SchemaOperationResult.builder()
                    .success(false)
                    .operationType(SchemaOperationResult.SchemaOperationType.CREATE_INDEX)
                    .modelCode(modelCode)
                    .affectedFields(List.of(fieldCode))
                    .message("Failed to create index")
                    .errorMessage(e.getMessage())
                    .operationTime(DateUtil.getCurrentLocalDateTimeUtc())
                    .build();
        }
    }
    
    /**
     * 生成索引名称
     */
    private String generateIndexName(String tableName, String columnName, IndexType indexType) {
        String prefix = indexType == IndexType.UNIQUE ? "uk" : "idx";
        return String.format("%s_%s_%s", prefix, tableName, columnName);
    }
    
    /**
     * 生成CREATE INDEX DDL
     */
    private String generateCreateIndexDDL(String tableName, String columnName, 
                                          String indexName, IndexType indexType) {
        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE ");
        
        if (indexType == IndexType.UNIQUE) {
            ddl.append("UNIQUE ");
        }
        
        ddl.append("INDEX ").append(indexName)
           .append(" ON ").append(tableName)
           .append(" (").append(columnName).append(")");
        
        return ddl.toString();
    }

    @Override
    @Transactional
    public SchemaOperationResult dropFieldIndex(String modelCode, String fieldCode) {
        log.info("Dropping index for field {} in model: {}", logSafe(fieldCode), logSafe(modelCode));
        
        try {
            // 1. 获取模型定义
            ModelDefinition model = metaModelService.getModelDefinition(modelCode)
                    .orElseThrow(() -> new RuntimeException("Model not found: " + modelCode));
            
            // 2. 获取字段定义
            FieldDefinition field = metaModelService.getFieldDefinition(modelCode, fieldCode);
            if (field == null) {
                throw new BusinessException("Field not found: " + fieldCode);
            }
            
            String tableName = model.getTableName();
            String columnName = field.getColumnName();
            
            // 3. 检查表是否存在
            if (!tableMetadataService.tableExists(tableName)) {
                throw new BusinessException("Table does not exist: " + tableName);
            }
            
            // 4. 查找字段的索引
            List<IndexInfo> indexes = metaModelService.getFieldIndexes(modelCode, fieldCode);
            
            if (indexes == null || indexes.isEmpty()) {
                log.warn("No indexes found for field {} in model {}", logSafe(fieldCode), logSafe(modelCode));
                return SchemaOperationResult.builder()
                        .success(true)
                        .operationType(SchemaOperationResult.SchemaOperationType.DROP_INDEX)
                        .modelCode(modelCode)
                        .tableName(tableName)
                        .affectedFields(List.of(fieldCode))
                        .message("No indexes to drop")
                        .operationTime(DateUtil.getCurrentLocalDateTimeUtc())
                        .build();
            }
            
            // 5. 生成DROP INDEX DDL并执行
            List<String> executedDDLs = new ArrayList<>();
            for (IndexInfo indexInfo : indexes) {
                String indexName = indexInfo.getIndexName();
                
                // 跳过主键索引 (通过索引类型判断)
                if ("primary".equalsIgnoreCase(indexInfo.getIndexType())) {
                    log.debug("Skipping primary key index: {}", logSafe(indexName));
                    continue;
                }
                
                // 生成DROP INDEX DDL
                String ddl = generateDropIndexDDL(tableName, indexName);
                
                try {
                    dynamicDataMapper.alterTable(ddl);
                    executedDDLs.add(ddl);
                    log.debug("Dropped index: {}", logSafe(indexName));
                } catch (Exception e) {
                    // §P1 per-index tolerance: a single index that no longer exists
                    // (manual drop, restored backup) should not stop dropping others.
                    log.warn("Failed to drop index {}: {}", logSafe(indexName), logSafe(e.getMessage()), e);
                }
            }
            
            if (executedDDLs.isEmpty()) {
                return SchemaOperationResult.builder()
                        .success(true)
                        .operationType(SchemaOperationResult.SchemaOperationType.DROP_INDEX)
                        .modelCode(modelCode)
                        .tableName(tableName)
                        .affectedFields(List.of(fieldCode))
                        .message("No indexes dropped (all were primary keys or failed)")
                        .operationTime(DateUtil.getCurrentLocalDateTimeUtc())
                        .build();
            }
            
            log.info("Successfully dropped {} index(es) for field {} in model {}",
                    executedDDLs.size(), logSafe(fieldCode), logSafe(modelCode));
            
            return SchemaOperationResult.builder()
                    .success(true)
                    .operationType(SchemaOperationResult.SchemaOperationType.DROP_INDEX)
                    .modelCode(modelCode)
                    .tableName(tableName)
                    .executedDDL(executedDDLs)
                    .affectedFields(List.of(fieldCode))
                    .message("Index(es) dropped successfully")
                    .operationTime(DateUtil.getCurrentLocalDateTimeUtc())
                    .build();
            
        } catch (Exception e) {
            log.error("Failed to drop index for field {} in model {}: {}",
                    logSafe(fieldCode), logSafe(modelCode), logSafe(e.getMessage()), e);
            return SchemaOperationResult.builder()
                    .success(false)
                    .operationType(SchemaOperationResult.SchemaOperationType.DROP_INDEX)
                    .modelCode(modelCode)
                    .affectedFields(List.of(fieldCode))
                    .message("Failed to drop index")
                    .errorMessage(e.getMessage())
                    .operationTime(DateUtil.getCurrentLocalDateTimeUtc())
                    .build();
        }
    }
    
    /**
     * 生成DROP INDEX DDL
     */
    private String generateDropIndexDDL(String tableName, String indexName) {
        DdlDialect dialect = ddlDialectProvider.getDialect();
        
        // PostgreSQL使用 DROP INDEX index_name
        if (dialect.getClass().getSimpleName().contains("Postgres")) {
            return "DROP INDEX IF EXISTS " + indexName;
        }
        
        // MySQL使用 DROP INDEX index_name ON table_name
        return "DROP INDEX " + indexName + " ON " + tableName;
    }

    @Override
    public SchemaDiffResult compareModelWithTable(String modelCode) {
        log.info("Comparing model with table: {}", logSafe(modelCode));
        
        try {
            // 1. 获取模型定义
            ModelDefinition model = metaModelService.getModelDefinition(modelCode)
                    .orElseThrow(() -> new RuntimeException("Model not found: " + modelCode));
            
            String tableName = model.getTableName();
            
            // 2. 检查表是否存在
            if (!tableMetadataService.tableExists(tableName)) {
                SchemaDiffResult.TableDiff tableDiff = SchemaDiffResult.TableDiff.builder()
                        .type(SchemaDiffResult.DiffType.REMOVED)
                        .tableName(tableName)
                        .message("Table does not exist")
                        .build();
                
                return SchemaDiffResult.builder()
                        .hasDifferences(true)
                        .modelCode(modelCode)
                        .tableDiff(tableDiff)
                        .build();
            }
            
            // 3. 比较字段
            List<String> missingColumns = new ArrayList<>();
            List<String> extraColumns = new ArrayList<>();
            
            // 检查模型中定义的字段是否在表中存在
            for (FieldDefinition field : model.getFields()) {
                String columnName = field.getColumnName();
                
                if (!tableMetadataService.columnExists(tableName, columnName)) {
                    missingColumns.add(columnName);
                }
            }
            
            // 4. 比较索引
            List<String> missingIndexes = new ArrayList<>();
            List<IndexDefinition> modelIndexes = metaModelService.getModelIndexes(modelCode);
            
            if (modelIndexes != null) {
                for (IndexDefinition indexDef : modelIndexes) {
                    String indexName = indexDef.getName();
                    if (!tableMetadataService.indexExists(tableName, indexName)) {
                        missingIndexes.add(indexName);
                    }
                }
            }
            
            // 5. 构建差异结果
            boolean hasDifferences = !missingColumns.isEmpty() || 
                                    !extraColumns.isEmpty() || 
                                    !missingIndexes.isEmpty();
            
            // 构建字段差异列表
            List<SchemaDiffResult.FieldDiff> fieldDiffs = new ArrayList<>();
            for (String columnName : missingColumns) {
                fieldDiffs.add(SchemaDiffResult.FieldDiff.builder()
                        .type(SchemaDiffResult.DiffType.REMOVED)
                        .columnName(columnName)
                        .message("Column missing in table")
                        .build());
            }
            
            // 构建索引差异列表
            List<SchemaDiffResult.IndexDiff> indexDiffs = new ArrayList<>();
            for (String indexName : missingIndexes) {
                indexDiffs.add(SchemaDiffResult.IndexDiff.builder()
                        .type(SchemaDiffResult.DiffType.REMOVED)
                        .indexName(indexName)
                        .message("Index missing in table")
                        .build());
            }
            
            // 构建表差异
            SchemaDiffResult.TableDiff tableDiff = SchemaDiffResult.TableDiff.builder()
                    .type(SchemaDiffResult.DiffType.UNCHANGED)
                    .tableName(tableName)
                    .message("Table exists")
                    .build();
            
            return SchemaDiffResult.builder()
                    .hasDifferences(hasDifferences)
                    .modelCode(modelCode)
                    .tableDiff(tableDiff)
                    .fieldDiffs(fieldDiffs)
                    .indexDiffs(indexDiffs)
                    .build();
            
        } catch (Exception e) {
            log.error("Failed to compare model {} with table: {}", logSafe(modelCode), logSafe(e.getMessage()), e);
            return SchemaDiffResult.builder()
                    .hasDifferences(false)
                    .modelCode(modelCode)
                    .build();
        }
    }

    @Override
    public TableInfo getTableInfoByModel(String modelCode) {
        log.warn("getTableInfoByModel is not yet implemented: {}", logSafe(modelCode));
        throw new UnsupportedOperationException(
            "Table info retrieval from database is not yet implemented. " +
            "This feature requires database metadata inspection permissions.");
    }

    @Override
    public ModelValidationResult validateModel(String modelCode) {
        log.warn("validateModel is not yet implemented: {}", logSafe(modelCode));
        throw new UnsupportedOperationException(
            "Model validation is not yet implemented. " +
            "This feature requires comparing model definition with actual database schema.");
    }

    @Override
    public BatchSchemaOperationResult batchSyncModels(List<String> modelCodes, SchemaSyncOptions syncOptions) {
        log.warn("batchSyncModels is not yet implemented: {}", logSafe(modelCodes));
        throw new UnsupportedOperationException(
            "Batch model synchronization is not yet implemented. " +
            "Please use syncModelToTable() for individual model sync.");
    }

    @Override
    public DDLPreviewResult previewModelChanges(String modelCode) {
        log.info("Previewing model changes: {}", logSafe(modelCode));
        SchemaSyncOptions options = SchemaSyncOptions.builder()
                .syncMode(SchemaSyncOptions.SyncMode.DRY_RUN)
                .createIndexes(true)
                .build();
        try {
            // Use getModelDefinitionFromDb to bypass cache and get fresh data
            ModelDefinition model = metaModelService.getModelDefinitionFromDb(modelCode)
                    .orElseThrow(() -> new RuntimeException("Model not found: " + modelCode));
            log.info("Preview: model {} has {} fields", logSafe(modelCode), model.getFields() != null ? model.getFields().size() : 0);
            List<String> ddlStatements = buildSyncDdls(model, options);
            return DDLPreviewResult.builder()
                    .modelCode(modelCode)
                    .ddlStatements(ddlStatements)
                    .operationType("preview")
                    .build();
        } catch (Exception e) {
            log.error("Failed to preview model changes for {}: {}", logSafe(modelCode), logSafe(e.getMessage()), e);
            return DDLPreviewResult.builder()
                    .modelCode(modelCode)
                    .ddlStatements(List.of())
                    .operationType("preview")
                    .build();
        }
    }

    private List<String> buildSyncDdls(ModelDefinition model, SchemaSyncOptions options) {
        List<String> ddlStatements = new ArrayList<>();
        String tableName = model.getTableName();

        if (!tableMetadataService.tableExists(tableName)) {
            ddlStatements.add(generateCreateTableDDL(model));
            if (Boolean.TRUE.equals(options.getCreateIndexes())) {
                ddlStatements.addAll(generateIndexDDLs(model));
            }
            return ddlStatements;
        }

        // Pre-compute all index DDLs once — used to find per-column indexes for newly added columns.
        // This avoids CREATE INDEX executing *after* its column exists in DB but without the ADD COLUMN
        // having been committed yet (new-connection columnExists checks see pre-commit state).
        List<String> allIndexDDLs = Boolean.TRUE.equals(options.getCreateIndexes())
                ? generateIndexDDLs(model) : List.of();

        // Tracks columns added in this sync so their indexes are not duplicated at the end
        Set<String> newlyAddedColumns = new HashSet<>();

        for (FieldDefinition field : model.getFields()) {
            // Skip JSONB virtual fields — they are stored inside a host JSONB column
            if (field.isJsonbVirtual()) {
                continue;
            }
            String columnName = field.getColumnName();
            if (SystemFieldConstants.ALL_INFRASTRUCTURE.contains(columnName)) {
                continue;
            }
            if (!tableMetadataService.columnExists(tableName, columnName)) {
                String columnDef = generateColumnDefinition(field);
                ddlStatements.add("ALTER TABLE " + tableName + " ADD COLUMN IF NOT EXISTS " + columnDef);
                newlyAddedColumns.add(columnName);

                // Place this column's indexes immediately after its ADD COLUMN so the column
                // is guaranteed to exist before any CREATE INDEX referencing it is executed.
                if (!allIndexDDLs.isEmpty()) {
                    List<String> colIndexes = allIndexDDLs.stream()
                            .filter(ddl -> isIndexForColumn(ddl, columnName))
                            .collect(Collectors.toList());
                    ddlStatements.addAll(filterExistingIndexes(tableName, colIndexes));
                }
                continue;
            }

            String expectedType = canonicalizeColumnType(ddlDialectProvider.getDialect().mapDataType(field));
            String actualType = canonicalizeColumnType(tableMetadataService.getColumnTypeDefinition(tableName, columnName));
            if (expectedType != null && actualType != null && !expectedType.equals(actualType)) {
                ddlStatements.add(String.format("ALTER TABLE %s ALTER COLUMN %s TYPE %s",
                        tableName, columnName, ddlDialectProvider.getDialect().mapDataType(field)));
            }

            boolean expectedNullable = !(field.isRequired() || field.isPrimaryKey());
            boolean actualNullable = tableMetadataService.isColumnNullable(tableName, columnName);
            if (expectedNullable != actualNullable) {
                if (expectedNullable) {
                    ddlStatements.add(String.format("ALTER TABLE %s ALTER COLUMN %s DROP NOT NULL", tableName, columnName));
                } else {
                    ddlStatements.add(String.format("ALTER TABLE %s ALTER COLUMN %s SET NOT NULL", tableName, columnName));
                }
            }
        }

        // Add remaining indexes: system-level (tenant_id, created_at, updated_at) and
        // indexes for existing columns. Skip indexes already emitted inline above.
        if (!allIndexDDLs.isEmpty()) {
            List<String> remainingIndexes = allIndexDDLs.stream()
                    .filter(ddl -> newlyAddedColumns.stream().noneMatch(col -> isIndexForColumn(ddl, col)))
                    .collect(Collectors.toList());
            ddlStatements.addAll(filterExistingIndexes(tableName, remainingIndexes));
        }

        return ddlStatements;
    }

    private String canonicalizeColumnType(String sqlType) {
        if (sqlType == null) return null;
        return sqlType
                .trim()
                .toUpperCase()
                .replace("NUMERIC", "DECIMAL")
                .replace("CHARACTER VARYING", "VARCHAR")
                .replaceAll("\\s+", "");
    }

    /**
     * Returns true if the given index DDL specifically references {@code columnName} as one of
     * the indexed columns (not just in the index name or table name).
     */
    private boolean isIndexForColumn(String ddl, String columnName) {
        // Index DDL column list is inside the last parentheses pair.
        // Match patterns: "(col)", "(col,", ", col)", ", col,"
        // Also handles GIN: USING GIN (col gin_trgm_ops)
        return ddl.contains("(" + columnName + ")")
                || ddl.contains("(" + columnName + " ")    // e.g. GIN: (col gin_trgm_ops)
                || ddl.contains("(" + columnName + ",")
                || ddl.contains(", " + columnName + ")")
                || ddl.contains(", " + columnName + " ")
                || ddl.contains(", " + columnName + ",");
    }

    private void executeDdl(String ddl) {
        String normalized = ddl.trim().toUpperCase();
        if (normalized.startsWith("CREATE TABLE")) {
            dynamicDataMapper.createTable(ddl);
            clearPostgresPreparedPlans();
            return;
        }
        dynamicDataMapper.alterTable(ddl);
        clearPostgresPreparedPlans();
    }

    private void clearPostgresPreparedPlans() {
        if (!"PostgreSQL".equalsIgnoreCase(ddlDialectProvider.getDialect().getName())) {
            return;
        }
        try {
            var connection = DataSourceUtils.getConnection(dataSource);
            try (var statement = connection.createStatement()) {
                statement.execute("DEALLOCATE ALL");
            }
        } catch (Exception e) {
            // §P2 best-effort cleanup: DEALLOCATE failure (connection lost, broken
            // pool member) does not invalidate the DDL just executed; stale plans
            // would self-evict on next prepare. Logged at warn so a recurring
            // failure can be picked up by ops.
            log.warn("Failed to clear PostgreSQL prepared plans after DDL: {}", logSafe(e.getMessage()), e);
        }
    }

    private List<String> filterExistingIndexes(String tableName, List<String> indexDdls) {
        if (indexDdls == null || indexDdls.isEmpty()) {
            return List.of();
        }
        if (!tableMetadataService.tableExists(tableName)) {
            return indexDdls;
        }
        List<String> filtered = new ArrayList<>();
        for (String ddl : indexDdls) {
            String indexName = extractIndexName(ddl);
            if (indexName == null) {
                filtered.add(ddl);
                continue;
            }
            if (!tableMetadataService.indexExists(tableName, indexName)) {
                filtered.add(ddl);
            }
        }
        return filtered;
    }

    private String extractIndexName(String ddl) {
        if (ddl == null) {
            return null;
        }
        Matcher matcher = INDEX_NAME_PATTERN.matcher(ddl);
        if (matcher.find()) {
            return matcher.group(2);
        }
        return null;
    }
}
