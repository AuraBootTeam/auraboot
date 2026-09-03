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

    private static final String ENTITY_TYPE = "quote_export_it";
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
    @DisplayName("relation insert fills tenant_id and tenant-scoped reads isolate per tenant")
    void relationRoundTripIsTenantScoped() {
        MetaContext.setContext(TENANT_A, USER_A, "relscope-a", "relscope-a");
        try {
            FileRelationRequestDTO request = new FileRelationRequestDTO();
            request.setFileIds(new String[]{filePid});
            request.setEntityType(ENTITY_TYPE);
            request.setEntityId("entity-a-1");
            request.setFieldName(FIELD_NAME);
            assertThat(fileService.createFileRelation(request, USER_A)).isTrue();

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

            List<String> ownerIds = fileRelationMapper.findFileIdsByEntity(ENTITY_TYPE, "entity-a-1");
            assertThat(ownerIds).containsExactly(String.valueOf(fileId));
        } finally {
            MetaContext.clear();
        }

        MetaContext.setContext(TENANT_B, USER_B, "relscope-b", "relscope-b");
        try {
            assertThat(fileService.getFileRelations(filePid)).isEmpty();
            assertThat(fileRelationMapper.findFileIdsByEntity(ENTITY_TYPE, "entity-a-1")).isEmpty();
            assertThat(fileRelationMapper.findFileIdsByEntityAndField(ENTITY_TYPE, "entity-a-1", FIELD_NAME))
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
                ENTITY_TYPE, "entity-legacy-1", FIELD_NAME);

        MetaContext.setContext(TENANT_A, USER_A, "relscope-a", "relscope-a");
        try {
            List<String> ownerIds = fileRelationMapper.findFileIdsByEntity(ENTITY_TYPE, "entity-legacy-1");
            assertThat(ownerIds).containsExactly(String.valueOf(fileId));
        } finally {
            MetaContext.clear();
        }

        MetaContext.setContext(TENANT_B, USER_B, "relscope-b", "relscope-b");
        try {
            assertThat(fileRelationMapper.findFileIdsByEntity(ENTITY_TYPE, "entity-legacy-1")).isEmpty();
        } finally {
            MetaContext.clear();
        }
    }
}
