package com.auraboot.framework.integration.meta;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldRefTargetBean;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.SchemaManagementService;
import com.auraboot.framework.meta.service.RecordRelatedCountsService;
import com.auraboot.framework.integration.TestIdGenerator;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-stack verification for the mobile detail contract's relatedCounts
 * aggregation (20-record-detail-data-api.md): models referencing a record
 * through a reference field are discovered and counted per model.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("RecordRelatedCountsService — reference discovery + counting")
class RecordRelatedCountsIT extends com.auraboot.framework.integration.BaseIntegrationTest {

    @Autowired private RecordRelatedCountsService relatedCountsService;
    @Autowired private SchemaManagementService schemaManagementService;
    @Autowired private MetaModelMapper metaModelMapper;
    @Autowired private MetaFieldMapper metaFieldMapper;
    @Autowired private MetaModelFieldBindingMapper fieldBindingMapper;
    @Autowired private JdbcTemplate jdbc;

    private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(java.util.Locale.ROOT);
    private final String parentModel = "relp_" + suffix;
    private final String childModel = "relc_" + suffix;
    private final String refField = "parent_ref";

    private Long tenantId;
    private Long userId;
    private String parentPid;

    @BeforeAll
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void setUp() {
        tenantId = TestIdGenerator.uniqueTenantId();
        userId = TestIdGenerator.uniqueUserId();
        jdbc.update("INSERT INTO ab_tenant (id, pid, name, status, deleted_flag) "
                        + "VALUES (?, ?, ?, 'active', FALSE) ON CONFLICT (id) DO NOTHING",
                tenantId, "tn_" + tenantId, "related_counts_" + tenantId);

        long memberId = System.nanoTime() & 0x7fffffffffffffffL;
        jdbc.update("INSERT INTO ab_tenant_member (id, pid, tenant_id, user_id, status, deleted_flag) "
                + "VALUES (?, ?, ?, ?, 'active', FALSE)",
                memberId, UniqueIdGenerator.generate(), tenantId, userId);

        createModelWithReference(parentModel, childModel);

        parentPid = "relp_parent_" + suffix;
        MetaContext.setContext(tenantId, userId, "u-" + userId, "related-counts");
        try {
            jdbc.update("INSERT INTO mt_" + parentModel + " (pid, tenant_id, name, row_version, created_by, updated_by) "
                    + "VALUES (?, ?, 'parent row', 1, ?, ?)", parentPid, tenantId, userId, userId);
            jdbc.update("INSERT INTO mt_" + childModel + " (pid, tenant_id, name, " + refField + ", row_version, created_by, updated_by) "
                    + "VALUES (?, ?, 'child a', ?, 1, ?, ?), (?, ?, 'child b', ?, 1, ?, ?)",
                    "relc_a_" + suffix, tenantId, parentPid, userId, userId,
                    "relc_b_" + suffix, tenantId, parentPid, userId, userId);
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    @DisplayName("counts referencing models for a record")
    void countsReferencingModels() {
        MetaContext.setContext(tenantId, userId, "u-" + userId, "related-counts");
        try {
            Map<String, Long> counts = relatedCountsService.relatedCounts(parentModel, parentPid);
            assertThat(counts).containsEntry(childModel, 2L);
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    @DisplayName("record without references yields empty counts")
    void emptyCountsForUnreferencedRecord() {
        MetaContext.setContext(tenantId, userId, "u-" + userId, "related-counts");
        try {
            Map<String, Long> counts = relatedCountsService.relatedCounts(childModel, "no_such_ref_" + suffix);
            assertThat(counts.values()).allMatch(v -> v == 0L);
        } finally {
            MetaContext.clear();
        }
    }

    private void createModelWithReference(String parentCode, String childCode) {
        MetaContext.setContext(tenantId, userId, "u-" + userId, "related-counts-model");
        try {
            Model parent = insertModel(parentCode);
            bindField(parent.getId(), "pid", "string", true, 0);
            bindField(parent.getId(), "name", "string", false, 1);
            assertThat(schemaManagementService.createTableByModel(parentCode).isSuccess()).isTrue();

            Model child = insertModel(childCode);
            bindField(child.getId(), "pid", "string", true, 0);
            bindField(child.getId(), "name", "string", false, 1);
            bindReferenceField(child.getId(), refField, parentCode, 2);
            assertThat(schemaManagementService.createTableByModel(childCode).isSuccess()).isTrue();
        } finally {
            MetaContext.clear();
        }
    }

    private Model insertModel(String modelCode) {
        Model entity = new Model();
        entity.setPid(UniqueIdGenerator.generate());
        entity.setTenantId(tenantId);
        entity.setCode(modelCode);
        entity.setVersion(1);
        entity.setIsCurrent(true);
        entity.setStatus(Status.PUBLISHED.getCode());
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        entity.setDeletedFlag(false);
        ExtensionBean extension = new ExtensionBean();
        extension.setExtension(Map.of(
                "displayName", modelCode,
                "modelType", "entity"));
        entity.setExtension(extension);
        metaModelMapper.insert(entity);
        return entity;
    }

    private final java.util.Map<String, Long> fieldIdsByCode = new java.util.HashMap<>();

    private void bindField(Long modelId, String code, String dataType, boolean primaryKey, int order) {
        Long existing = fieldIdsByCode.get(code);
        if (existing != null) {
            insertBinding(modelId, existing, order);
            return;
        }
        Field field = new Field();
        field.setPid(UniqueIdGenerator.generate());
        field.setTenantId(tenantId);
        field.setCode(code);
        field.setDataType(dataType);
        field.setVersion(1);
        field.setIsCurrent(true);
        field.setStatus(Status.PUBLISHED.getCode());
        field.setCreatedAt(Instant.now());
        field.setUpdatedAt(Instant.now());
        field.setDeletedFlag(false);
        ExtensionBean extension = new ExtensionBean();
        Map<String, Object> extensionValues = new java.util.HashMap<>();
        extensionValues.put("displayName", code);
        if (primaryKey) {
            extensionValues.put("primaryKey", true);
        }
        extension.setExtension(extensionValues);
        field.setExtension(extension);
        metaFieldMapper.insert(field);
        fieldIdsByCode.put(code, field.getId());
        insertBinding(modelId, field.getId(), order);
    }

    private void bindReferenceField(Long modelId, String code, String targetModel, int order) {
        Field field = new Field();
        field.setPid(UniqueIdGenerator.generate());
        field.setTenantId(tenantId);
        field.setCode(code);
        field.setDataType("reference");
        field.setVersion(1);
        field.setIsCurrent(true);
        field.setStatus(Status.PUBLISHED.getCode());
        field.setCreatedAt(Instant.now());
        field.setUpdatedAt(Instant.now());
        field.setDeletedFlag(false);
        FieldRefTargetBean refTarget = new FieldRefTargetBean();
        refTarget.setRefType("reference");
        refTarget.setTargetEntity(targetModel);
        refTarget.setTargetField("name");
        field.setRefTarget(refTarget);
        metaFieldMapper.insert(field);
        fieldIdsByCode.put(code, field.getId());
        insertBinding(modelId, field.getId(), order);
    }

    private void insertBinding(Long modelId, Long fieldId, int order) {
        ModelFieldBinding binding = new ModelFieldBinding();
        binding.setTenantId(tenantId);
        binding.setModelId(modelId);
        binding.setFieldId(fieldId);
        binding.setFieldOrder(order);
        fieldBindingMapper.insert(binding);
    }
}
