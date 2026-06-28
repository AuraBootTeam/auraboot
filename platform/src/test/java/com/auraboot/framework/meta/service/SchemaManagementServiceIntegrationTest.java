package com.auraboot.framework.meta.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.dto.SchemaOperationResult;
import com.auraboot.framework.meta.service.SchemaManagementService;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for SchemaManagementService.
 * 
 * Tests the physical table creation flow:
 * 1. Create DRAFT model
 * 2. Create PUBLISHED fields
 * 3. Bind fields to model
 * 4. Call metaModelService.publish() to create physical table
 * 
 * Note: Uses NOT_SUPPORTED propagation because DDL operations (CREATE TABLE)
 * cannot be rolled back in PostgreSQL within a transaction.
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("SchemaManagementService Integration Test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchemaManagementServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MetaModelService metaModelService;
    @Autowired
    private SchemaManagementService schemaManagementService;

    @Autowired
    private MetaModelMapper metaModelMapper;

    @Autowired
    private MetaFieldMapper metaFieldMapper;

    @Autowired
    private MetaModelFieldBindingMapper fieldBindingMapper;

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    @Autowired
    private DataSource dataSource;

    private String modelCode;
    private String modelPid;
    private String tableName;
    private String testSuffix;
    private Model model;
    private Field nameField;
    private Field statusField;

    @BeforeEach
    void setUp() {
        setupTenantContext();
    }

    @AfterEach
    void tearDown() {
        // Clean up in reverse order
        if (tableName != null) {
            try {
                dynamicDataMapper.alterTable("DROP TABLE IF EXISTS " + tableName);
                log.info("Dropped table: {}", tableName);
            } catch (Exception e) {
                log.debug("Table {} does not exist or already dropped", tableName);
            }
        }
        if (model != null) {
            try {
                fieldBindingMapper.deleteByModelId(model.getId());
            } catch (Exception e) {
                log.debug("Failed to delete bindings: {}", e.getMessage());
            }
        }
        if (nameField != null) {
            try {
                metaFieldMapper.deleteById(nameField.getId());
            } catch (Exception e) {
                log.debug("Failed to delete nameField: {}", e.getMessage());
            }
        }
        if (statusField != null) {
            try {
                metaFieldMapper.deleteById(statusField.getId());
            } catch (Exception e) {
                log.debug("Failed to delete statusField: {}", e.getMessage());
            }
        }
        if (model != null) {
            try {
                metaModelMapper.deleteById(model.getId());
            } catch (Exception e) {
                log.debug("Failed to delete model: {}", e.getMessage());
            }
        }
        
        // Reset for next test
        model = null;
        nameField = null;
        statusField = null;
        modelCode = null;
        modelPid = null;
        tableName = null;
    }

    @Test
    @Order(1)
    @DisplayName("Publish model creates physical table")
    void createTableByModelCreatesPhysicalTable() throws Exception {
        // Use unique suffix to avoid conflicts between test runs
        testSuffix = "_" + System.currentTimeMillis();
        modelCode = "schema_test" + testSuffix;
        tableName = "mt_" + modelCode.toLowerCase();

        // Step 1: Create DRAFT model
        model = buildMetaModel(modelCode, Status.DRAFT);
        metaModelMapper.insert(model);
        modelPid = model.getPid();
        log.info("Created DRAFT model: code={}, pid={}", modelCode, modelPid);

        // Step 2: Create PUBLISHED fields (fields must be PUBLISHED to be used)
        // Use unique field codes to avoid conflicts with existing fields
        String nameFieldCode = "name" + testSuffix;
        String statusFieldCode = "status" + testSuffix;
        nameField = buildField(nameFieldCode, "string", false, true);
        statusField = buildField(statusFieldCode, "string", false, false);
        metaFieldMapper.insert(nameField);
        metaFieldMapper.insert(statusField);
        log.info("Created fields: name={}, status={}", nameField.getCode(), statusField.getCode());

        // Step 3: Bind fields to model
        fieldBindingMapper.insert(buildBinding(model.getId(), nameField.getId(), 1));
        fieldBindingMapper.insert(buildBinding(model.getId(), statusField.getId(), 2));
        log.info("Bound fields to model");

        // Step 4: Publish model - this creates the physical table
        MetaModelDTO result = metaModelService.publish(modelPid, "Initial publish for test");
        
        assertNotNull(result, "Publish result should not be null");
        assertEquals("published", result.getStatus(), "Model status should be PUBLISHED");
        log.info("Published model: code={}, status={}", result.getCode(), result.getStatus());

        // Step 5: Verify physical table exists
        assertTrue(tableExists(tableName), "Table " + tableName + " should exist");
        log.info("Verified table exists: {}", tableName);

        // Step 6: Verify columns exist (system columns are auto-created)
        // Field columns use the unique field codes
        assertTrue(columnExists(tableName, "id"), "Column 'id' should exist");
        assertTrue(columnExists(tableName, "pid"), "Column 'pid' should exist");
        assertTrue(columnExists(tableName, nameFieldCode), "Column '" + nameFieldCode + "' should exist");
        assertTrue(columnExists(tableName, statusFieldCode), "Column '" + statusFieldCode + "' should exist");
        assertTrue(columnExists(tableName, "tenant_id"), "Column 'tenant_id' should exist");
        assertTrue(columnExists(tableName, "created_at"), "Column 'created_at' should exist");
        assertTrue(columnExists(tableName, "updated_at"), "Column 'updated_at' should exist");
        log.info("Verified all columns exist");
    }

    @Test
    @Order(2)
    @DisplayName("Transient field is physicalized nullable even when binding is required (#1107)")
    void transientFieldColumnIsNullableEvenWhenRequired() throws Exception {
        testSuffix = "_t" + System.currentTimeMillis();
        modelCode = "schema_transient" + testSuffix;
        tableName = "mt_" + modelCode.toLowerCase();

        model = buildMetaModel(modelCode, Status.DRAFT);
        metaModelMapper.insert(model);
        modelPid = model.getPid();

        // Reproduce the quote-core corrected_bom_file shape: virtualType=transient (so it is
        // excluded from INSERT) + the binding marks it required (form-required UX). feature.required
        // stays false (GAP-259: required is per-binding), matching the real import.
        String transientCode = "uploadslot" + testSuffix;
        String normalCode = "name" + testSuffix;
        nameField = buildField(transientCode, "string", false, false);
        nameField.getFeature().setVirtualType("transient");
        statusField = buildField(normalCode, "string", false, false);
        metaFieldMapper.insert(nameField);
        metaFieldMapper.insert(statusField);

        ModelFieldBinding transientBinding = buildBinding(model.getId(), nameField.getId(), 1);
        transientBinding.setRequired(true);   // form-required, but column must stay nullable
        ModelFieldBinding normalBinding = buildBinding(model.getId(), statusField.getId(), 2);
        normalBinding.setRequired(true);      // control: a normal required field stays NOT NULL
        fieldBindingMapper.insert(transientBinding);
        fieldBindingMapper.insert(normalBinding);

        metaModelService.publish(modelPid, "Publish transient-field test");
        assertTrue(tableExists(tableName), "Table should exist");

        Boolean transientNullable = columnIsNullable(tableName, transientCode);
        Boolean normalNullable = columnIsNullable(tableName, normalCode);
        log.info("transient column {} nullable={}, normal column {} nullable={}",
                transientCode, transientNullable, normalCode, normalNullable);

        assertEquals(Boolean.TRUE, transientNullable,
                "transient field column must be NULLABLE (it is excluded from INSERT) even when the "
                + "binding is required — otherwise create fails with a not-null violation (#1107)");
        assertEquals(Boolean.FALSE, normalNullable,
                "a normal required field column must stay NOT NULL (control)");
    }

    @Test
    @Order(3)
    @DisplayName("Transient field added via updateTableByModel sync path stays nullable (#1107)")
    void transientFieldViaSyncPathIsNullable() throws Exception {
        testSuffix = "_s" + System.currentTimeMillis();
        modelCode = "schema_sync" + testSuffix;
        tableName = "mt_" + modelCode.toLowerCase();

        // Publish a model with one normal field → table created.
        model = buildMetaModel(modelCode, Status.DRAFT);
        metaModelMapper.insert(model);
        modelPid = model.getPid();
        String normalCode = "name" + testSuffix;
        statusField = buildField(normalCode, "string", false, false);
        metaFieldMapper.insert(statusField);
        ModelFieldBinding b1 = buildBinding(model.getId(), statusField.getId(), 1);
        b1.setRequired(false);
        fieldBindingMapper.insert(b1);
        metaModelService.publish(modelPid, "publish sync-path base");
        assertTrue(tableExists(tableName), "Table should exist after publish");

        // Now add a transient+required(binding) field and run the import sync path
        // (updateTableByModel → syncModelToTable → buildSyncDdls ADD COLUMN) — the path the real
        // plugin reimport uses (PluginResourceImporterImpl.syncPublishedModelsForUpdatedField).
        String transientCode = "uploadslot" + testSuffix;
        nameField = buildField(transientCode, "string", false, false);
        nameField.getFeature().setVirtualType("transient");
        metaFieldMapper.insert(nameField);
        ModelFieldBinding b2 = buildBinding(model.getId(), nameField.getId(), 2);
        b2.setRequired(true);
        fieldBindingMapper.insert(b2);

        SchemaOperationResult sync = schemaManagementService.updateTableByModel(modelCode);
        log.info("sync result success={} ddl={}", sync.isSuccess(), sync.getExecutedDDL());
        assertTrue(sync.isSuccess(), "updateTableByModel should succeed: " + sync.getErrorMessage());

        Boolean transientNullable = columnIsNullable(tableName, transientCode);
        log.info("sync-path transient column {} nullable={}", transientCode, transientNullable);
        assertEquals(Boolean.TRUE, transientNullable,
                "transient field column added via the import sync path must be NULLABLE (#1107)");
    }

    private boolean tableExists(String name) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            // Try different schema/case combinations for PostgreSQL
            String[] schemas = {connection.getSchema(), "public"};
            String[] names = {name, name.toLowerCase(), name.toUpperCase()};
            
            for (String schema : schemas) {
                for (String tableName : names) {
                    try (ResultSet tables = metaData.getTables(
                            connection.getCatalog(), schema, tableName, new String[]{"TABLE"})) {
                        if (tables.next()) {
                            log.debug("Found table: schema={}, name={}", schema, tableName);
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }

    /** Returns true if the column is nullable (is_nullable='YES'), false if NOT NULL, null if absent. */
    private Boolean columnIsNullable(String table, String column) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            for (String schema : new String[]{connection.getSchema(), "public"}) {
                try (ResultSet columns = metaData.getColumns(
                        connection.getCatalog(), schema, table.toLowerCase(), column.toLowerCase())) {
                    if (columns.next()) {
                        return "YES".equalsIgnoreCase(columns.getString("IS_NULLABLE"));
                    }
                }
            }
            return null;
        }
    }

    private boolean columnExists(String table, String column) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            // Try different schema/case combinations for PostgreSQL
            String[] schemas = {connection.getSchema(), "public"};
            String[] tableNames = {table, table.toLowerCase(), table.toUpperCase()};
            String[] columnNames = {column, column.toLowerCase(), column.toUpperCase()};
            
            for (String schema : schemas) {
                for (String tableName : tableNames) {
                    for (String columnName : columnNames) {
                        try (ResultSet columns = metaData.getColumns(
                                connection.getCatalog(), schema, tableName, columnName)) {
                            if (columns.next()) {
                                log.debug("Found column: schema={}, table={}, column={}", 
                                        schema, tableName, columnName);
                                return true;
                            }
                        }
                    }
                }
            }
            return false;
        }
    }

    private Model buildMetaModel(String code, Status status) {
        Model model = new Model();
        model.setPid(UniqueIdGenerator.generate());
        model.setTenantId(getTestTenant().getId());
        model.setCode(code);
        model.setVersion(1);
        model.setIsCurrent(true);
        model.setStatus(status.getCode());
        model.setCreatedAt(Instant.now());
        model.setUpdatedAt(Instant.now());
        model.setDeletedFlag(false);

        ExtensionBean extension = new ExtensionBean();
        Map<String, Object> extensionMap = new HashMap<>();
        extensionMap.put("displayName", "Schema Test Model");
        extensionMap.put("description", "Integration test model for schema management");
        extensionMap.put("modelType", "entity");
        extension.setExtension(extensionMap);
        model.setExtension(extension);

        return model;
    }

    private Field buildField(String code, String dataType, boolean primaryKey, boolean required) {
        Field field = new Field();
        field.setPid(UniqueIdGenerator.generate());
        field.setTenantId(getTestTenant().getId());
        field.setCode(code);
        field.setDataType(dataType);
        field.setVersion(1);
        field.setIsCurrent(true);
        field.setStatus(Status.PUBLISHED.getCode()); // Fields must be PUBLISHED
        field.setCreatedAt(Instant.now());
        field.setUpdatedAt(Instant.now());
        field.setDeletedFlag(false);

        FieldFeatureBean feature = new FieldFeatureBean();
        feature.setRequired(required);
        feature.setUnique(primaryKey);
        field.setFeature(feature);

        ExtensionBean extension = new ExtensionBean();
        Map<String, Object> extensionMap = new HashMap<>();
        extensionMap.put("displayName", code.toUpperCase());
        extensionMap.put("description", code + " field for testing");
        if (primaryKey) {
            extensionMap.put("primaryKey", true);
        }
        extension.setExtension(extensionMap);
        field.setExtension(extension);

        return field;
    }

    private ModelFieldBinding buildBinding(Long modelId, Long fieldId, int order) {
        ModelFieldBinding binding = new ModelFieldBinding();
        binding.setTenantId(getTestTenant().getId());
        binding.setModelId(modelId);
        binding.setFieldId(fieldId);
        binding.setFieldOrder(order);
        return binding;
    }
}
