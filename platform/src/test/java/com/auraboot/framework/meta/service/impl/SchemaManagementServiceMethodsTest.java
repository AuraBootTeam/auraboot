package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.ddl.DdlDialect;
import com.auraboot.framework.meta.ddl.DdlDialectProvider;
import com.auraboot.framework.meta.ddl.TableMetadataService;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.MultiTenantIndexManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.withSettings;

/**
 * SchemaManagementService方法实现测试
 * 
 * 测试Task 9实现的方法:
 * - addFieldToModel
 * - removeFieldFromModel
 * - updateModelField
 * - createFieldIndex
 * - dropFieldIndex
 * - compareModelWithTable
 * 
 * @author AuraBoot Team
 * @since 2.2.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Task 9: Schema管理方法实现测试")
class SchemaManagementServiceMethodsTest {

    @Mock
    private MetaModelService metaModelService;

    @Mock
    private DynamicDataMapper dynamicDataMapper;

    @Mock
    private DdlDialectProvider ddlDialectProvider;

    @Mock
    private TableMetadataService tableMetadataService;

    @Mock
    private MultiTenantIndexManager multiTenantIndexManager;

    @Mock
    private DdlDialect ddlDialect;

    @InjectMocks
    private SchemaManagementServiceImpl schemaManagementService;

    private ModelDefinition testModel;
    private FieldDefinition testField;

    @BeforeEach
    void setUp() {
        // 准备测试数据
        testField = FieldDefinition.builder()
                .code("test_field")
                .columnName("test_column")
                .dataType("string")
                .required(false)
                .unique(false)
                .primaryKey(false)
                .defaultValue(null)
                .build();

        List<FieldDefinition> fields = new ArrayList<>();
        fields.add(testField);

        testModel = ModelDefinition.builder()
                .code("test_model")
                .tableName("tb_test")
                .fields(fields)
                .build();

        // 配置DDL方言
        when(ddlDialectProvider.getDialect()).thenReturn(ddlDialect);
        when(ddlDialect.mapDataType(any())).thenReturn("VARCHAR(255)");
        when(ddlDialect.formatDefaultValue(any(), any())).thenReturn("'default'");
    }

    // ==================== addFieldToModel 测试 ====================

    @Test
    @DisplayName("addFieldToModel - 成功添加字段")
    void testAddFieldToModel_Success() {
        // Given — addFieldToModel uses getModelDefinitionFromDb (bypasses cache)
        // and reads fields from the model's field list directly
        when(metaModelService.getModelDefinitionFromDb("test_model"))
                .thenReturn(Optional.of(testModel));
        when(tableMetadataService.tableExists("tb_test")).thenReturn(true);
        when(tableMetadataService.columnExists("tb_test", "test_column")).thenReturn(false);
        when(dynamicDataMapper.alterTable(anyString())).thenReturn(0);

        // When
        SchemaOperationResult result = schemaManagementService.addFieldToModel("test_model", "test_field");

        // Then
        assertTrue(result.getSuccess());
        assertEquals(SchemaOperationResult.SchemaOperationType.ADD_FIELD, result.getOperationType());
        assertEquals("test_model", result.getModelCode());
        assertEquals("tb_test", result.getTableName());
        assertNotNull(result.getExecutedDDL());
        assertEquals(1, result.getExecutedDDL().size());
        assertTrue(result.getExecutedDDL().get(0).contains("ALTER TABLE"));
        assertTrue(result.getExecutedDDL().get(0).contains("ADD COLUMN"));

        verify(dynamicDataMapper, times(1)).alterTable(anyString());
    }

    @Test
    @DisplayName("addFieldToModel - 列已存在")
    void testAddFieldToModel_ColumnAlreadyExists() {
        // Given — addFieldToModel uses getModelDefinitionFromDb (bypasses cache)
        when(metaModelService.getModelDefinitionFromDb("test_model"))
                .thenReturn(Optional.of(testModel));
        when(tableMetadataService.tableExists("tb_test")).thenReturn(true);
        when(tableMetadataService.columnExists("tb_test", "test_column")).thenReturn(true);

        // When
        SchemaOperationResult result = schemaManagementService.addFieldToModel("test_model", "test_field");

        // Then
        assertTrue(result.getSuccess());
        assertEquals("Column already exists", result.getMessage());

        verify(dynamicDataMapper, never()).alterTable(anyString());
    }

    @Test
    @DisplayName("addFieldToModel - 模型不存在")
    void testAddFieldToModel_ModelNotFound() {
        // Given — addFieldToModel uses getModelDefinitionFromDb (bypasses cache)
        when(metaModelService.getModelDefinitionFromDb("test_model"))
                .thenReturn(Optional.empty());

        // When
        SchemaOperationResult result = schemaManagementService.addFieldToModel("test_model", "test_field");

        // Then
        assertFalse(result.getSuccess());
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("Model not found"));
    }

    @Test
    @DisplayName("addFieldToModel - 表不存在")
    void testAddFieldToModel_TableNotExists() {
        // Given — addFieldToModel uses getModelDefinitionFromDb (bypasses cache)
        when(metaModelService.getModelDefinitionFromDb("test_model"))
                .thenReturn(Optional.of(testModel));
        when(tableMetadataService.tableExists("tb_test")).thenReturn(false);

        // When
        SchemaOperationResult result = schemaManagementService.addFieldToModel("test_model", "test_field");

        // Then
        assertFalse(result.getSuccess());
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("Table does not exist"));
    }

    @Test
    @DisplayName("syncModelToTable - 已存在列类型漂移时应生成 ALTER COLUMN TYPE")
    void testSyncModelToTable_TypeDriftGeneratesAlterColumnType() {
        testField = FieldDefinition.builder()
                .code("test_field")
                .columnName("test_column")
                .dataType("decimal")
                .precision(19)
                .scale(2)
                .required(false)
                .build();
        testModel.setFields(List.of(testField));

        when(metaModelService.getModelDefinitionFromDb("test_model"))
                .thenReturn(Optional.of(testModel));
        when(tableMetadataService.tableExists("tb_test")).thenReturn(true);
        when(tableMetadataService.columnExists("tb_test", "test_column")).thenReturn(true);
        when(tableMetadataService.getColumnTypeDefinition("tb_test", "test_column"))
                .thenReturn("DECIMAL(2,2)");
        when(tableMetadataService.isColumnNullable("tb_test", "test_column")).thenReturn(true);
        when(ddlDialect.mapDataType(testField)).thenReturn("DECIMAL(19,2)");
        when(dynamicDataMapper.alterTable(anyString())).thenReturn(0);

        SchemaOperationResult result = schemaManagementService.syncModelToTable("test_model", null);

        assertTrue(result.getSuccess());
        assertNotNull(result.getExecutedDDL());
        assertTrue(result.getExecutedDDL().stream().anyMatch(ddl ->
                ddl.contains("ALTER TABLE tb_test ALTER COLUMN test_column TYPE DECIMAL(19,2)")));
        verify(dynamicDataMapper, atLeastOnce()).alterTable(anyString());
    }

    // ==================== removeFieldFromModel 测试 ====================

    @Test
    @DisplayName("removeFieldFromModel - 成功移除字段")
    void testRemoveFieldFromModel_Success() {
        // Given
        when(metaModelService.getModelDefinition("test_model"))
                .thenReturn(Optional.of(testModel));
        when(metaModelService.getFieldDefinition("test_model", "test_field"))
                .thenReturn(testField);
        when(tableMetadataService.tableExists("tb_test")).thenReturn(true);
        when(tableMetadataService.columnExists("tb_test", "test_column")).thenReturn(true);
        when(dynamicDataMapper.alterTable(anyString())).thenReturn(0);

        // When
        SchemaOperationResult result = schemaManagementService.removeFieldFromModel("test_model", "test_field");

        // Then
        assertTrue(result.getSuccess());
        assertEquals(SchemaOperationResult.SchemaOperationType.REMOVE_FIELD, result.getOperationType());
        assertNotNull(result.getExecutedDDL());
        assertEquals(1, result.getExecutedDDL().size());
        assertTrue(result.getExecutedDDL().get(0).contains("DROP COLUMN"));
        
        verify(dynamicDataMapper, times(1)).alterTable(anyString());
    }

    @Test
    @DisplayName("removeFieldFromModel - 列不存在")
    void testRemoveFieldFromModel_ColumnNotExists() {
        // Given
        when(metaModelService.getModelDefinition("test_model"))
                .thenReturn(Optional.of(testModel));
        when(metaModelService.getFieldDefinition("test_model", "test_field"))
                .thenReturn(testField);
        when(tableMetadataService.tableExists("tb_test")).thenReturn(true);
        when(tableMetadataService.columnExists("tb_test", "test_column")).thenReturn(false);

        // When
        SchemaOperationResult result = schemaManagementService.removeFieldFromModel("test_model", "test_field");

        // Then
        assertTrue(result.getSuccess());
        assertEquals("Column does not exist", result.getMessage());
        
        verify(dynamicDataMapper, never()).alterTable(anyString());
    }

    @Test
    @DisplayName("removeFieldFromModel - 不能删除主键字段")
    void testRemoveFieldFromModel_CannotRemovePrimaryKey() {
        // Given
        FieldDefinition pkField = FieldDefinition.builder()
                .code("id")
                .columnName("id")
                .dataType("long")
                .primaryKey(true)
                .build();
        
        when(metaModelService.getModelDefinition("test_model"))
                .thenReturn(Optional.of(testModel));
        when(metaModelService.getFieldDefinition("test_model", "id"))
                .thenReturn(pkField);
        when(tableMetadataService.tableExists("tb_test")).thenReturn(true);
        when(tableMetadataService.columnExists("tb_test", "id")).thenReturn(true);

        // When
        SchemaOperationResult result = schemaManagementService.removeFieldFromModel("test_model", "id");

        // Then
        assertFalse(result.getSuccess());
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("Cannot remove primary key"));
    }

    // ==================== updateModelField 测试 ====================

    @Test
    @DisplayName("updateModelField - 成功更新字段(非PostgreSQL)")
    void testUpdateModelField_Success_NonPostgres() {
        // Given
        // 创建一个非Postgres的Dialect mock
        DdlDialect mysqlDialect = mock(DdlDialect.class, withSettings().name("MySQLDialect"));
        when(ddlDialectProvider.getDialect()).thenReturn(mysqlDialect);
        when(mysqlDialect.mapDataType(any())).thenReturn("VARCHAR(255)");
        
        when(metaModelService.getModelDefinition("test_model"))
                .thenReturn(Optional.of(testModel));
        when(metaModelService.getFieldDefinition("test_model", "test_field"))
                .thenReturn(testField);
        when(tableMetadataService.tableExists("tb_test")).thenReturn(true);
        when(tableMetadataService.columnExists("tb_test", "test_column")).thenReturn(true);
        when(dynamicDataMapper.alterTable(anyString())).thenReturn(0);

        // When
        SchemaOperationResult result = schemaManagementService.updateModelField("test_model", "test_field");

        // Then
        assertTrue(result.getSuccess());
        assertEquals(SchemaOperationResult.SchemaOperationType.UPDATE_FIELD, result.getOperationType());
        assertNotNull(result.getExecutedDDL());
        assertTrue(result.getExecutedDDL().get(0).contains("MODIFY COLUMN"));
    }

    @Test
    @DisplayName("updateModelField - 列不存在")
    void testUpdateModelField_ColumnNotExists() {
        // Given
        when(metaModelService.getModelDefinition("test_model"))
                .thenReturn(Optional.of(testModel));
        when(metaModelService.getFieldDefinition("test_model", "test_field"))
                .thenReturn(testField);
        when(tableMetadataService.tableExists("tb_test")).thenReturn(true);
        when(tableMetadataService.columnExists("tb_test", "test_column")).thenReturn(false);

        // When
        SchemaOperationResult result = schemaManagementService.updateModelField("test_model", "test_field");

        // Then
        assertFalse(result.getSuccess());
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("Column does not exist"));
    }

    // ==================== createFieldIndex 测试 ====================

    @Test
    @DisplayName("createFieldIndex - 成功创建索引")
    void testCreateFieldIndex_Success() {
        // Given
        when(metaModelService.getModelDefinition("test_model"))
                .thenReturn(Optional.of(testModel));
        when(metaModelService.getFieldDefinition("test_model", "test_field"))
                .thenReturn(testField);
        when(tableMetadataService.tableExists("tb_test")).thenReturn(true);
        when(tableMetadataService.columnExists("tb_test", "test_column")).thenReturn(true);
        when(tableMetadataService.indexExists(eq("tb_test"), anyString())).thenReturn(false);
        when(dynamicDataMapper.alterTable(anyString())).thenReturn(0);

        // When
        SchemaOperationResult result = schemaManagementService.createFieldIndex(
                "test_model", "test_field", IndexType.NORMAL);

        // Then
        assertTrue(result.getSuccess());
        assertEquals(SchemaOperationResult.SchemaOperationType.CREATE_INDEX, result.getOperationType());
        assertNotNull(result.getExecutedDDL());
        assertTrue(result.getExecutedDDL().get(0).contains("CREATE INDEX"));
        
        verify(dynamicDataMapper, times(1)).alterTable(anyString());
    }

    @Test
    @DisplayName("createFieldIndex - 创建唯一索引")
    void testCreateFieldIndex_UniqueIndex() {
        // Given
        when(metaModelService.getModelDefinition("test_model"))
                .thenReturn(Optional.of(testModel));
        when(metaModelService.getFieldDefinition("test_model", "test_field"))
                .thenReturn(testField);
        when(tableMetadataService.tableExists("tb_test")).thenReturn(true);
        when(tableMetadataService.columnExists("tb_test", "test_column")).thenReturn(true);
        when(tableMetadataService.indexExists(eq("tb_test"), anyString())).thenReturn(false);
        when(dynamicDataMapper.alterTable(anyString())).thenReturn(0);

        // When
        SchemaOperationResult result = schemaManagementService.createFieldIndex(
                "test_model", "test_field", IndexType.UNIQUE);

        // Then
        assertTrue(result.getSuccess());
        assertNotNull(result.getExecutedDDL());
        assertTrue(result.getExecutedDDL().get(0).contains("CREATE UNIQUE INDEX"));
    }

    @Test
    @DisplayName("createFieldIndex - 索引已存在")
    void testCreateFieldIndex_IndexAlreadyExists() {
        // Given
        when(metaModelService.getModelDefinition("test_model"))
                .thenReturn(Optional.of(testModel));
        when(metaModelService.getFieldDefinition("test_model", "test_field"))
                .thenReturn(testField);
        when(tableMetadataService.tableExists("tb_test")).thenReturn(true);
        when(tableMetadataService.columnExists("tb_test", "test_column")).thenReturn(true);
        when(tableMetadataService.indexExists(eq("tb_test"), anyString())).thenReturn(true);

        // When
        SchemaOperationResult result = schemaManagementService.createFieldIndex(
                "test_model", "test_field", IndexType.NORMAL);

        // Then
        assertTrue(result.getSuccess());
        assertEquals("Index already exists", result.getMessage());
        
        verify(dynamicDataMapper, never()).alterTable(anyString());
    }

    // ==================== dropFieldIndex 测试 ====================

    @Test
    @DisplayName("dropFieldIndex - 成功删除索引")
    void testDropFieldIndex_Success() {
        // Given
        IndexInfo indexInfo = IndexInfo.builder()
                .indexName("idx_test_column")
                .indexType("normal")
                .unique(false)
                .build();
        
        List<IndexInfo> indexes = List.of(indexInfo);
        
        when(metaModelService.getModelDefinition("test_model"))
                .thenReturn(Optional.of(testModel));
        when(metaModelService.getFieldDefinition("test_model", "test_field"))
                .thenReturn(testField);
        when(tableMetadataService.tableExists("tb_test")).thenReturn(true);
        when(metaModelService.getFieldIndexes("test_model", "test_field"))
                .thenReturn(indexes);
        when(dynamicDataMapper.alterTable(anyString())).thenReturn(0);

        // When
        SchemaOperationResult result = schemaManagementService.dropFieldIndex("test_model", "test_field");

        // Then
        assertTrue(result.getSuccess());
        assertEquals(SchemaOperationResult.SchemaOperationType.DROP_INDEX, result.getOperationType());
        assertNotNull(result.getExecutedDDL());
        assertEquals(1, result.getExecutedDDL().size());
        assertTrue(result.getExecutedDDL().get(0).contains("DROP INDEX"));
        
        verify(dynamicDataMapper, times(1)).alterTable(anyString());
    }

    @Test
    @DisplayName("dropFieldIndex - 没有索引")
    void testDropFieldIndex_NoIndexes() {
        // Given
        when(metaModelService.getModelDefinition("test_model"))
                .thenReturn(Optional.of(testModel));
        when(metaModelService.getFieldDefinition("test_model", "test_field"))
                .thenReturn(testField);
        when(tableMetadataService.tableExists("tb_test")).thenReturn(true);
        when(metaModelService.getFieldIndexes("test_model", "test_field"))
                .thenReturn(new ArrayList<>());

        // When
        SchemaOperationResult result = schemaManagementService.dropFieldIndex("test_model", "test_field");

        // Then
        assertTrue(result.getSuccess());
        assertEquals("No indexes to drop", result.getMessage());
        
        verify(dynamicDataMapper, never()).alterTable(anyString());
    }

    @Test
    @DisplayName("dropFieldIndex - 跳过主键索引")
    void testDropFieldIndex_SkipPrimaryKey() {
        // Given
        IndexInfo pkIndex = IndexInfo.builder()
                .indexName("primary")
                .indexType("primary")
                .unique(true)
                .build();
        
        List<IndexInfo> indexes = List.of(pkIndex);
        
        when(metaModelService.getModelDefinition("test_model"))
                .thenReturn(Optional.of(testModel));
        when(metaModelService.getFieldDefinition("test_model", "test_field"))
                .thenReturn(testField);
        when(tableMetadataService.tableExists("tb_test")).thenReturn(true);
        when(metaModelService.getFieldIndexes("test_model", "test_field"))
                .thenReturn(indexes);

        // When
        SchemaOperationResult result = schemaManagementService.dropFieldIndex("test_model", "test_field");

        // Then
        assertTrue(result.getSuccess());
        assertTrue(result.getMessage().contains("No indexes dropped"));
        
        verify(dynamicDataMapper, never()).alterTable(anyString());
    }

    // ==================== compareModelWithTable 测试 ====================

    @Test
    @DisplayName("compareModelWithTable - 表不存在")
    void testCompareModelWithTable_TableNotExists() {
        // Given
        when(metaModelService.getModelDefinition("test_model"))
                .thenReturn(Optional.of(testModel));
        when(tableMetadataService.tableExists("tb_test")).thenReturn(false);

        // When
        SchemaDiffResult result = schemaManagementService.compareModelWithTable("test_model");

        // Then
        assertTrue(result.getHasDifferences());
        assertEquals("test_model", result.getModelCode());
        assertNotNull(result.getTableDiff());
        assertEquals(SchemaDiffResult.DiffType.REMOVED, result.getTableDiff().getType());
        assertEquals("Table does not exist", result.getTableDiff().getMessage());
    }

    @Test
    @DisplayName("compareModelWithTable - 无差异")
    void testCompareModelWithTable_NoDifferences() {
        // Given
        when(metaModelService.getModelDefinition("test_model"))
                .thenReturn(Optional.of(testModel));
        when(tableMetadataService.tableExists("tb_test")).thenReturn(true);
        when(tableMetadataService.columnExists("tb_test", "test_column")).thenReturn(true);
        when(metaModelService.getModelIndexes("test_model")).thenReturn(new ArrayList<>());

        // When
        SchemaDiffResult result = schemaManagementService.compareModelWithTable("test_model");

        // Then
        assertFalse(result.getHasDifferences());
        assertEquals("test_model", result.getModelCode());
        assertNotNull(result.getTableDiff());
        assertEquals(SchemaDiffResult.DiffType.UNCHANGED, result.getTableDiff().getType());
    }

    @Test
    @DisplayName("compareModelWithTable - 有缺失列")
    void testCompareModelWithTable_MissingColumns() {
        // Given
        when(metaModelService.getModelDefinition("test_model"))
                .thenReturn(Optional.of(testModel));
        when(tableMetadataService.tableExists("tb_test")).thenReturn(true);
        when(tableMetadataService.columnExists("tb_test", "test_column")).thenReturn(false);
        when(metaModelService.getModelIndexes("test_model")).thenReturn(new ArrayList<>());

        // When
        SchemaDiffResult result = schemaManagementService.compareModelWithTable("test_model");

        // Then
        assertTrue(result.getHasDifferences());
        assertNotNull(result.getFieldDiffs());
        assertEquals(1, result.getFieldDiffs().size());
        assertEquals(SchemaDiffResult.DiffType.REMOVED, result.getFieldDiffs().get(0).getType());
        assertEquals("test_column", result.getFieldDiffs().get(0).getColumnName());
    }

    @Test
    @DisplayName("compareModelWithTable - 有缺失索引")
    void testCompareModelWithTable_MissingIndexes() {
        // Given
        IndexDefinition indexDef = IndexDefinition.builder()
                .name("idx_test_column")
                .type(IndexDefinition.IndexType.NORMAL)
                .fields(List.of("test_column"))
                .build();
        
        when(metaModelService.getModelDefinition("test_model"))
                .thenReturn(Optional.of(testModel));
        when(tableMetadataService.tableExists("tb_test")).thenReturn(true);
        when(tableMetadataService.columnExists("tb_test", "test_column")).thenReturn(true);
        when(metaModelService.getModelIndexes("test_model")).thenReturn(List.of(indexDef));
        when(tableMetadataService.indexExists("tb_test", "idx_test_column")).thenReturn(false);

        // When
        SchemaDiffResult result = schemaManagementService.compareModelWithTable("test_model");

        // Then
        assertTrue(result.getHasDifferences());
        assertNotNull(result.getIndexDiffs());
        assertEquals(1, result.getIndexDiffs().size());
        assertEquals(SchemaDiffResult.DiffType.REMOVED, result.getIndexDiffs().get(0).getType());
        assertEquals("idx_test_column", result.getIndexDiffs().get(0).getIndexName());
    }

    @Test
    @DisplayName("compareModelWithTable - 模型不存在")
    void testCompareModelWithTable_ModelNotFound() {
        // Given
        when(metaModelService.getModelDefinition("test_model"))
                .thenReturn(Optional.empty());

        // When
        SchemaDiffResult result = schemaManagementService.compareModelWithTable("test_model");

        // Then
        assertFalse(result.getHasDifferences());
        assertEquals("test_model", result.getModelCode());
    }
}
