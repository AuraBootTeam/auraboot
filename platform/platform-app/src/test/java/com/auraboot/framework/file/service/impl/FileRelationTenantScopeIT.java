package com.auraboot.framework.file.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.file.dao.mapper.FileRelationMapper;
import com.auraboot.framework.file.dto.FileRelationRequestDTO;
import com.auraboot.framework.file.entity.FileRelationEntity;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real PostgreSQL proof that ab_file_relation carries tenant_id so the
 * TenantLineInnerInterceptor can scope relation queries per tenant. Before the
 * V20260903000000 migration the table had no tenant_id column and any relation
 * lookup under a tenant context failed with "column tenant_id does not exist"
 * (observed as quote Excel download authorization failures).
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("File relation tenant scoping IT")
class FileRelationTenantScopeIT {

    private static final long TENANT_A = 991_930_001L;
    private static final long USER_A = 991_930_002L;
    private static final long TENANT_B = 991_930_011L;
    private static final long USER_B = 991_930_012L;

    private String ENTITY_TYPE;
    private String sharedRecord, anotherRecord, entityRecord, legacyRecord;
    @Autowired private com.auraboot.framework.meta.service.MetaModelService models;
    @Autowired private com.auraboot.framework.meta.service.SchemaManagementService schema;
    @Autowired private com.auraboot.framework.meta.service.DynamicDataService data;

    @org.junit.jupiter.api.BeforeAll
    void initializeRealBusinessTarget() {
        MetaContext.setContext(TENANT_A, USER_A, "owner", "owner");
        ENTITY_TYPE = "file_rel_" + Long.toString(System.currentTimeMillis(),36);
        models.saveDefinition(com.auraboot.framework.meta.dto.ModelDefinition.builder().code(ENTITY_TYPE)
                .tableName("mt_"+ENTITY_TYPE).status("published").modelType("entity")
                .fields(List.of(com.auraboot.framework.meta.dto.FieldDefinition.builder()
                        .code(FIELD_NAME).columnName(FIELD_NAME).dataType("file").build())).build());
        assertThat(schema.createTableByModel(ENTITY_TYPE).isSuccess()).isTrue();
        sharedRecord = newParent(); anotherRecord = newParent(); entityRecord = newParent(); legacyRecord = newParent();
        MetaContext.clear();
    }

    private String newParent() {
        return MetaContext.runWithCommandPermitScope("ALL",()->data.create(ENTITY_TYPE, java.util.Map.of()).get("pid").toString());
    }

    private boolean replace(FileRelationRequestDTO request, Long user) {
        return MetaContext.runWithCommandPermitScope("ALL",()->fileService.createFileRelation(request,user));
    }
    private static final String FIELD_NAME = "bom_workbook";

    @Autowired private FileService fileService;
    @Autowired private FileRelationMapper fileRelationMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockBean private StorageProvider storageProvider;

    private long fileId;
    private String filePid;

    @AfterAll
    void cleanup() {
        jdbcTemplate.update("DELETE FROM ab_file_relation WHERE entity_type = ?", ENTITY_TYPE);
        jdbcTemplate.update("DELETE FROM ab_file WHERE tenant_id IN (?, ?)", TENANT_A, TENANT_B);
        MetaContext.clear();
    }

    @BeforeEach
    void seedFileForTenantA() {
        cleanup();
        fileId = 991_931_000L + System.nanoTime() % 1_000_000L;
        filePid = "FILE-RELSCOPE-" + fileId;
        Instant now = Instant.now();
        Timestamp databaseNow = Timestamp.from(now);
        jdbcTemplate.update(
                "INSERT INTO ab_file "
                        + "(id, pid, tenant_id, file_name, original_name, file_size, mime_type, "
                        + "storage_type, upload_time, created_by, status, created_time, updated_time, "
                        + "deleted_flag) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false)",
                fileId, filePid, TENANT_A, "relation-scope.bin", "relation-scope.bin", 10L,
                "application/octet-stream", "local", databaseNow, USER_A, "success",
                databaseNow, databaseNow);
    }

    @Test
    @DisplayName("another editor retains the same attachment but cannot copy it across records or fields")
    void anotherEditorRetainsOnlyTheExistingTarget() {
        FileRelationRequestDTO request = new FileRelationRequestDTO();
        request.setFileIds(new String[]{filePid});
        request.setEntityType(ENTITY_TYPE);
        request.setEntityId(sharedRecord);
        request.setFieldName(FIELD_NAME);
        MetaContext.setContext(TENANT_A, USER_A, "owner", "owner");
        assertThat(replace(request, USER_A)).isTrue();

        long editor = USER_A + 1;
        MetaContext.setContext(TENANT_A, editor, "editor", "editor");
        assertThat(replace(request, editor)).isTrue();
        assertThat(fileRelationMapper.findFileIdsByEntityAndField(
                ENTITY_TYPE, sharedRecord, FIELD_NAME)).containsExactly(fileId);

        request.setEntityId(anotherRecord);
        assertThatThrownBy(() -> replace(request, editor))
                .isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
        request.setEntityId(sharedRecord);
        request.setFieldName("another_field");
        assertThatThrownBy(() -> replace(request, editor))
                .isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
        assertThat(fileRelationMapper.findFileIdsByEntityAndField(
                ENTITY_TYPE, sharedRecord, FIELD_NAME)).containsExactly(fileId);

        MetaContext.setContext(TENANT_B, USER_B, "other-tenant", "other-tenant");
        request.setFieldName(FIELD_NAME);
        assertThatThrownBy(() -> replace(request, USER_B))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(fileRelationMapper.findFileIdsByEntity(ENTITY_TYPE, sharedRecord)).isEmpty();
    }

    @Test
    @DisplayName("relation insert fills tenant_id and tenant-scoped reads isolate per tenant")
    void relationRoundTripIsTenantScoped() {
        MetaContext.setContext(TENANT_A, USER_A, "relscope-a", "relscope-a");
        try {
            FileRelationRequestDTO request = new FileRelationRequestDTO();
            request.setFileIds(new String[]{filePid});
            request.setEntityType(ENTITY_TYPE);
            request.setEntityId(entityRecord);
            request.setFieldName(FIELD_NAME);
            assertThat(replace(request, USER_A)).isTrue();

            Boolean flaggedDeleted = jdbcTemplate.queryForObject(
                    "SELECT deleted_flag FROM ab_file_relation WHERE file_id = ?",
                    Boolean.class, String.valueOf(fileId));
            assertThat(flaggedDeleted).isFalse();
            Long storedTenantId = jdbcTemplate.queryForObject(
                    "SELECT tenant_id FROM ab_file_relation WHERE file_id = ?",
                    Long.class, String.valueOf(fileId));
            assertThat(storedTenantId).as("interceptor fills tenant_id on insert").isEqualTo(TENANT_A);

            List<FileRelationEntity> ownerView = fileService.getFileRelations(filePid);
            assertThat(ownerView).hasSize(1);
            assertThat(ownerView.get(0).getTenantId()).isEqualTo(TENANT_A);

            List<Long> ownerIds = fileRelationMapper.findFileIdsByEntity(ENTITY_TYPE, entityRecord);
            assertThat(ownerIds).containsExactly(fileId);
        } finally {
            MetaContext.clear();
        }

        MetaContext.setContext(TENANT_B, USER_B, "relscope-b", "relscope-b");
        try {
            assertThat(fileService.getFileRelations(filePid)).isEmpty();
            assertThat(fileRelationMapper.findFileIdsByEntity(ENTITY_TYPE, entityRecord)).isEmpty();
            assertThat(fileRelationMapper.findFileIdsByEntityAndField(ENTITY_TYPE, entityRecord, FIELD_NAME))
                    .isEmpty();
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    @DisplayName("backfilled legacy relation row (tenant set, no interceptor insert) reads per tenant")
    void backfilledLegacyRowIsTenantScoped() {
        jdbcTemplate.update(
                "INSERT INTO ab_file_relation (id, file_id, tenant_id, entity_type, entity_id, "
                        + "field_name, relation_type, sort_order, deleted_flag) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'ATTACHMENT', 0, false)",
                "relscope-legacy-" + fileId, String.valueOf(fileId), TENANT_A,
                ENTITY_TYPE, legacyRecord, FIELD_NAME);

        MetaContext.setContext(TENANT_A, USER_A, "relscope-a", "relscope-a");
        try {
            List<Long> ownerIds = fileRelationMapper.findFileIdsByEntity(ENTITY_TYPE, legacyRecord);
            assertThat(ownerIds).containsExactly(fileId);
        } finally {
            MetaContext.clear();
        }

        MetaContext.setContext(TENANT_B, USER_B, "relscope-b", "relscope-b");
        try {
            assertThat(fileRelationMapper.findFileIdsByEntity(ENTITY_TYPE, legacyRecord)).isEmpty();
        } finally {
            MetaContext.clear();
        }
    }
}
