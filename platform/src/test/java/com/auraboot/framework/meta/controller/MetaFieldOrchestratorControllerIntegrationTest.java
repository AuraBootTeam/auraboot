package com.auraboot.framework.meta.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.AddFieldRequest;
import com.auraboot.framework.meta.dto.AddFieldResult;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.Filter;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link MetaFieldOrchestratorController} (Spec §4 / Plan §Task 5).
 *
 * <p>Wire-shape verification only — service-layer behaviour is exhaustively
 * covered by {@code MetaFieldServiceAddToModelIntegrationTest} (T3 + T4).
 *
 * <p>Three cases:
 * <ol>
 *   <li>{@code post_addsField_returns200} — happy path: AddFieldResult round-trips</li>
 *   <li>{@code post_unknownModel_returns4xx} — service rejects → GlobalExceptionHandler → 422</li>
 *   <li>{@code delete_dataExists_returns422} — ColumnHasDataException → 422 / COLUMN_HAS_DATA</li>
 * </ol>
 *
 * <p>Real PostgreSQL ({@code skills-c4-test} profile @ port 35442). Auth is
 * established by installing a {@code MetaContext}-priming filter on the
 * MockMvc chain (mirrors {@code AuraBotSkillControllerIntegrationTest}); the
 * platform's central {@code PermissionInterceptor} handles authorization, so
 * no JWT helper is needed at the test layer.
 *
 * <p>{@code NOT_SUPPORTED} propagation because DDL cannot roll back inside a
 * Spring transaction in PostgreSQL.
 */
@Slf4j
@ActiveProfiles({"integration-test", "skills-c4-test"})
@DisplayName("MetaFieldOrchestratorController IT (Spec §4)")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class MetaFieldOrchestratorControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MetaFieldService metaFieldService;

    @Autowired
    private MetaModelService metaModelService;

    @Autowired
    private MetaModelMapper metaModelMapper;

    @Autowired
    private MetaFieldMapper metaFieldMapper;

    @Autowired
    private MetaModelFieldBindingMapper bindingMapper;

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    private String testModelCode;
    private String tableName;
    private Model model;
    private Field seedField;

    @BeforeEach
    @Override
    public void setupTenantContext() {
        super.setupTenantContext();

        // Seed a published model with one bound field (mirrors T3 IT: publish()
        // emits CREATE TABLE only when at least one field is bound).
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase();
        testModelCode = "it_c4_t5_" + suffix;
        tableName = "mt_" + testModelCode;

        model = buildMetaModel(testModelCode, Status.DRAFT);
        metaModelMapper.insert(model);

        String seedFieldCode = "it_c4_t5_seed_" + suffix;
        seedField = buildField(seedFieldCode, "string");
        metaFieldMapper.insert(seedField);
        bindingMapper.insert(buildBinding(model.getId(), seedField.getId(), 1));

        MetaModelDTO published = metaModelService.publish(model.getPid(), "C-4 T5 IT seed publish");
        assertNotNull(published, "model publish must return non-null");
        assertThat(published.getStatus()).isEqualTo("published");

        // MetaContext-priming filter: re-establishes the tenant ThreadLocal on
        // every dispatch so the platform's tenant-line interceptor + the
        // MetaFieldService can read it. Same pattern as
        // AuraBotSkillControllerIntegrationTest.
        Filter metaContextFilter = (request, response, chain) -> {
            MetaContext.setContext(
                    getTestTenant().getId(),
                    getTestUser().getId(),
                    getTestUser().getPid(),
                    getTestUser().getUserName());
            MetaContext.setMemberId(getTestTenantMember().getId());
            chain.doFilter(request, response);
        };
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .addFilter(metaContextFilter, "/*")
                .build();
    }

    @AfterEach
    public void cleanup() {
        try {
            dynamicDataMapper.alterTable("DROP TABLE IF EXISTS " + tableName);
        } catch (Exception e) {
            log.debug("drop {} skipped: {}", tableName, e.getMessage());
        }
        if (model != null) {
            try {
                bindingMapper.deleteByModelId(model.getId());
            } catch (Exception e) {
                log.debug("binding delete skipped: {}", e.getMessage());
            }
        }
        if (seedField != null) {
            try {
                metaFieldMapper.deleteById(seedField.getId());
            } catch (Exception e) {
                log.debug("seed field delete skipped: {}", e.getMessage());
            }
        }
        if (model != null) {
            try {
                metaModelMapper.deleteById(model.getId());
            } catch (Exception e) {
                log.debug("model delete skipped: {}", e.getMessage());
            }
        }
    }

    // ─── Case 1: POST happy path ────────────────────────────────────────────
    @Test
    @DisplayName("POST /api/meta/orchestrator/models/{modelCode}/fields adds field — returns 200 + AddFieldResult")
    public void post_addsField_returns200() throws Exception {
        String fieldCode = "it_c4_t5_phone_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase();

        ObjectNode body = objectMapper.createObjectNode();
        // modelCode in body should be overridden by the path; pass a wrong
        // value to assert the controller's path-wins behaviour.
        body.put("modelCode", "WILL-BE-OVERRIDDEN");
        body.put("code", fieldCode);
        body.put("dataType", "string");
        body.put("displayName", "Customer Phone");
        body.put("maxLength", 64);
        body.put("comment", "contact phone");

        mockMvc.perform(post("/api/meta/orchestrator/models/{modelCode}/fields", testModelCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.fieldPid").isNotEmpty())
                .andExpect(jsonPath("$.data.storageCode").value(testModelCode + "_" + fieldCode))
                .andExpect(jsonPath("$.data.tableName").value(tableName))
                .andExpect(jsonPath("$.data.columnName").value(fieldCode))
                .andExpect(jsonPath("$.data.pgColumnType").value("varchar(64)"));

        // Verify column actually landed via information_schema.
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                Integer.class, tableName, fieldCode);
        assertThat(count).as("column must exist after POST").isEqualTo(1);
    }

    // ─── Case 2: POST unknown model → 4xx ───────────────────────────────────
    @Test
    @DisplayName("POST with unknown modelCode in path → 4xx (ValidationException → 422)")
    public void post_unknownModel_returns4xx() throws Exception {
        String unknownModel = "it_c4_t5_unknown_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase();

        ObjectNode body = objectMapper.createObjectNode();
        body.put("code", "it_c4_t5_age");
        body.put("dataType", "int");

        mockMvc.perform(post("/api/meta/orchestrator/models/{modelCode}/fields", unknownModel)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().is4xxClientError());
    }

    // ─── Case 3: DELETE on column with data → 422 COLUMN_HAS_DATA ───────────
    @Test
    @DisplayName("DELETE on column with non-null rows + refuseIfDataExists=true → 422 COLUMN_HAS_DATA")
    public void delete_dataExists_returns422() throws Exception {
        String fieldCode = "it_c4_t5_rm_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase();

        // Add via service to set up the column under test.
        AddFieldResult added = metaFieldService.addToModel(AddFieldRequest.builder()
                .modelCode(testModelCode)
                .code(fieldCode)
                .dataType("string")
                .displayName("Has Data")
                .maxLength(32)
                .build());

        // Insert a synthetic row with a non-null value in the new column
        // (mirrors removeFromModel_dataPresent_throwsColumnHasDataException
        // in the T4 service IT).
        Long tenantId = getTestTenant().getId();
        String pid = UniqueIdGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO " + tableName + " (pid, tenant_id, " + fieldCode + ") VALUES (?, ?, ?)",
                pid, tenantId, "non-null-value");

        try {
            mockMvc.perform(delete("/api/meta/orchestrator/models/{modelCode}/fields/{storageCode}",
                            testModelCode, added.getStorageCode())
                            .param("refuseIfDataExists", "true"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("COLUMN_HAS_DATA"))
                    .andExpect(jsonPath("$.message").isNotEmpty());

            // Column must still exist — refusal must be transactional w.r.t. DDL.
            Integer stillThere = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                    Integer.class, tableName, fieldCode);
            assertThat(stillThere).as("column must NOT be dropped on refusal").isEqualTo(1);
        } finally {
            // Allow @AfterEach DROP TABLE to succeed cleanly.
            try {
                jdbcTemplate.update("DELETE FROM " + tableName + " WHERE pid = ?", pid);
            } catch (Exception ignore) {
                // best effort
            }
        }
    }

    // ==================== fixtures (mirror T3/T4 service ITs) ===============

    private Model buildMetaModel(String code, Status status) {
        Model m = new Model();
        m.setPid(UniqueIdGenerator.generate());
        m.setTenantId(getTestTenant().getId());
        m.setCode(code);
        m.setVersion(1);
        m.setIsCurrent(true);
        m.setStatus(status.getCode());
        m.setCreatedAt(Instant.now());
        m.setUpdatedAt(Instant.now());
        m.setDeletedFlag(false);

        ExtensionBean extension = new ExtensionBean();
        Map<String, Object> ext = new HashMap<>();
        ext.put("displayName", "C-4 T5 controller IT model");
        ext.put("description", "transient fixture model for orchestrator-controller ITs");
        ext.put("modelType", "entity");
        extension.setExtension(ext);
        m.setExtension(extension);

        return m;
    }

    private Field buildField(String code, String dataType) {
        Field f = new Field();
        f.setPid(UniqueIdGenerator.generate());
        f.setTenantId(getTestTenant().getId());
        f.setCode(code);
        f.setDataType(dataType);
        f.setVersion(1);
        f.setIsCurrent(true);
        f.setStatus(Status.PUBLISHED.getCode());
        f.setCreatedAt(Instant.now());
        f.setUpdatedAt(Instant.now());
        f.setDeletedFlag(false);

        FieldFeatureBean feature = new FieldFeatureBean();
        feature.setRequired(false);
        feature.setUnique(false);
        f.setFeature(feature);

        ExtensionBean extension = new ExtensionBean();
        Map<String, Object> ext = new HashMap<>();
        ext.put("displayName", code);
        ext.put("description", code + " seed field");
        extension.setExtension(ext);
        f.setExtension(extension);

        return f;
    }

    private ModelFieldBinding buildBinding(Long modelId, Long fieldId, int order) {
        ModelFieldBinding b = new ModelFieldBinding();
        b.setTenantId(getTestTenant().getId());
        b.setModelId(modelId);
        b.setFieldId(fieldId);
        b.setFieldOrder(order);
        return b;
    }
}
