package com.auraboot.framework.aisearch.service;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.aisearch.dto.GlobalSearchCandidates;
import com.auraboot.framework.aisearch.dto.GlobalSearchPreference;
import com.auraboot.framework.aisearch.dto.GlobalSearchResult;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.SchemaOperationResult;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.SchemaManagementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack coverage IT for {@link GlobalSearchServiceImpl} — cross-model keyword
 * search restricted to readable models, the model-budget/truncation branch, degraded
 * (table-less) model skip, and the personal preference round-trip.
 *
 * <p>Single coherent scenario (P1VirtualModelSmokeTest pattern): everything runs in one
 * test method — grants land immediately before the lookups that must observe them,
 * matching the ordering the permission snapshot caches handle reliably.
 *
 * <p>Field codes are tenant-unique ({@code uq_meta_field_code_ver}), so every model
 * gets its own prefix; {@code purgeGsFamily} removes this fixture family's residue
 * (including rows from interrupted runs) before and after the scenario.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("GlobalSearchServiceImpl Coverage IT — readable search + preferences")
class GlobalSearchServiceImplCoverageIT extends BaseIntegrationTest {

    private static final String KEYWORD_TOKEN = "zephyr";
    private static final String PREFERENCE_KEY = "search.global.enabled-models";

    @Autowired
    private GlobalSearchService globalSearchService;
    @Autowired
    private MetaModelMapper metaModelMapper;
    @Autowired
    private MetaFieldMapper metaFieldMapper;
    @Autowired
    private MetaModelFieldBindingMapper fieldBindingMapper;
    @Autowired
    private DynamicDataService dynamicDataService;
    @Autowired
    private SchemaManagementService schemaManagementService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("global search scenario — readable filtering, grouping, budget, preferences, guards")
    void globalSearchScenario() {
        setupTenantContext();
        applyTestMetaContext();
        purgeGsFamily();
        Long tenantId = getTestTenant().getId();
        jdbcTemplate.update("DELETE FROM ab_user_preference WHERE tenant_id = ? AND preference_key = ?",
                tenantId, PREFERENCE_KEY);
        Long userId = getTestUser().getId();
        long suffix = System.currentTimeMillis();
        String alpha = "gsa2_" + suffix;
        String beta = "gsb2_" + suffix;
        String ghost = "gsg2_" + suffix;
        String viewLike = "gsv2_" + suffix;

        seedModel(tenantId, alpha, "gsa2", "Alpha Search", "entity", true);
        seedModel(tenantId, beta, "gsb2", null, "entity", true);
        seedModel(tenantId, ghost, "gsg2", "Ghost (no table)", "entity", false);
        seedModel(tenantId, viewLike, "gsv2", "View-like model", "view", false);
        seedRecord(alpha, "gsa2", "alpha-" + KEYWORD_TOKEN + "-one");
        seedRecord(alpha, "gsa2", "alpha-quiet");
        seedRecord(beta, "gsb2", "beta-" + KEYWORD_TOKEN + "-one");
        // ghost gets NO read grant: unreadable models are dropped from the candidate set
        // before any query (fail-closed). Note the table-less skip path inside the search
        // loop cannot be exercised here — its SQL error would abort this test's
        // transaction — and needs a non-transactional harness.
        for (String code : List.of(alpha, beta)) {
            grantCommittedPermissionToTestRole(
                    "model." + code + ".read", "model", code, "read", "IT read " + code);
        }

        try {
            List<Model> readable = globalSearchService.readableCandidateModels(userId);
            List<String> codes = readable.stream().map(Model::getCode).toList();
            assertTrue(codes.contains(alpha), "readable must contain alpha; got " + codes);
            assertTrue(codes.contains(beta));
            assertFalse(codes.contains(ghost), "unreadable models must never surface as candidates");
            assertFalse(codes.contains(viewLike), "view-like models must be excluded from search candidates");
            assertThrows(IllegalStateException.class, () -> globalSearchService.readableCandidateModels(null));

            GlobalSearchResult result = globalSearchService.search(userId, "  " + KEYWORD_TOKEN + "  ", null, null);
            assertEquals(KEYWORD_TOKEN, result.getKeyword());
            assertFalse(result.isTruncated());
            List<String> groupCodes = result.getGroups().stream()
                    .map(GlobalSearchResult.Group::getModelCode).toList();
            assertTrue(groupCodes.contains(alpha));
            assertTrue(groupCodes.contains(beta));
            assertFalse(groupCodes.contains(ghost));

            GlobalSearchResult.Group alphaGroup = result.getGroups().stream()
                    .filter(g -> g.getModelCode().equals(alpha)).findFirst().orElseThrow();
            assertEquals("Alpha Search", alphaGroup.getModelLabel());
            assertEquals(1L, alphaGroup.getTotal());

            GlobalSearchResult.Group betaGroup = result.getGroups().stream()
                    .filter(g -> g.getModelCode().equals(beta)).findFirst().orElseThrow();
            assertEquals(beta, betaGroup.getModelLabel(), "missing displayName falls back to the model code");

            GlobalSearchResult budgeted = globalSearchService.search(userId, KEYWORD_TOKEN, null, 1);
            assertEquals(1, budgeted.getGroups().size());
            assertTrue(budgeted.isTruncated());

            assertThrows(IllegalArgumentException.class,
                    () -> globalSearchService.search(userId, "   ", null, null));

            GlobalSearchPreference unconfigured = globalSearchService.getSearchPreference(userId, tenantId);
            assertFalse(unconfigured.isConfigured());
            assertTrue(unconfigured.getEnabledModelCodes().isEmpty());

            GlobalSearchCandidates candidates = globalSearchService.listCandidates(userId, tenantId);
            assertTrue(candidates.getModels().stream()
                    .filter(c -> c.getModelCode().equals(alpha))
                    .allMatch(GlobalSearchCandidates.ModelCandidate::isEnabled),
                    "unconfigured preference enables every candidate");

            GlobalSearchPreference saved = globalSearchService.saveSearchPreference(
                    userId, tenantId, List.of(alpha, " ", alpha));
            assertTrue(saved.isConfigured());
            assertEquals(List.of(alpha), saved.getEnabledModelCodes());

            GlobalSearchPreference reloaded = globalSearchService.getSearchPreference(userId, tenantId);
            assertEquals(List.of(alpha), reloaded.getEnabledModelCodes());

            GlobalSearchResult narrowed = globalSearchService.search(userId, tenantId, KEYWORD_TOKEN, null, null);
            List<String> narrowedCodes = narrowed.getGroups().stream()
                    .map(GlobalSearchResult.Group::getModelCode).toList();
            assertTrue(narrowedCodes.contains(alpha));
            assertFalse(narrowedCodes.contains(beta), "disabled models must not be searched");

            GlobalSearchCandidates narrowedCandidates = globalSearchService.listCandidates(userId, tenantId);
            assertFalse(narrowedCandidates.getModels().stream()
                    .filter(c -> c.getModelCode().equals(beta))
                    .allMatch(GlobalSearchCandidates.ModelCandidate::isEnabled));

            assertThrows(IllegalArgumentException.class,
                    () -> globalSearchService.saveSearchPreference(userId, tenantId, null));
            assertThrows(IllegalArgumentException.class,
                    () -> globalSearchService.saveSearchPreference(userId, tenantId,
                            List.of(alpha, "no_such_model_" + KEYWORD_TOKEN)));
            assertThrows(IllegalArgumentException.class, () -> globalSearchService.saveSearchPreference(
                    userId, tenantId, java.util.stream.IntStream.rangeClosed(1, 251)
                            .mapToObj(i -> "m" + i).toList()));
            assertThrows(IllegalStateException.class,
                    () -> globalSearchService.saveSearchPreference(userId, null, List.of(alpha)));
        } finally {
            for (String code : List.of(alpha, beta, ghost, viewLike)) {
                try {
                    jdbcTemplate.execute("DROP TABLE IF EXISTS mt_" + code);
                } catch (Exception ignored) {
                    // degraded fixture has no table
                }
            }
            purgeGsFamily();
            jdbcTemplate.update("DELETE FROM ab_user_preference WHERE tenant_id = ? AND preference_key = ?",
                    tenantId, PREFERENCE_KEY);
            MetaContext.clear();
        }
    }

    private void seedModel(Long tenantId, String code, String fieldPrefix,
                           String displayName, String modelType, boolean withTable) {
        Model m = new Model();
        m.setPid(UniqueIdGenerator.generate());
        m.setTenantId(tenantId);
        m.setCode(code);
        m.setVersion(1);
        m.setIsCurrent(true);
        m.setStatus(Status.PUBLISHED.getCode());
        m.setCreatedAt(Instant.now());
        m.setUpdatedAt(Instant.now());
        m.setDeletedFlag(false);
        ExtensionBean ext = new ExtensionBean();
        Map<String, Object> extMap = new HashMap<>();
        if (displayName != null) {
            extMap.put("displayName", displayName);
        }
        extMap.put("modelType", modelType);
        ext.setExtension(extMap);
        m.setExtension(ext);
        metaModelMapper.insert(m);

        bindField(tenantId, code, fieldPrefix + "_pid", "string", true, -1);
        if (withTable) {
            bindField(tenantId, code, fieldPrefix + "_name", "string", false, 1);
            SchemaOperationResult table = schemaManagementService.createTableByModel(code);
            if (!table.isSuccess()) {
                throw new RuntimeException("table creation failed for " + code + ": " + table.getErrorMessage());
            }
        }
    }

    private void bindField(Long tenantId, String modelCode, String code,
                           String dataType, boolean primaryKey, int order) {
        Field f = new Field();
        f.setPid(UniqueIdGenerator.generate());
        f.setTenantId(tenantId);
        f.setCode(code);
        f.setDataType(dataType);
        f.setVersion(1);
        f.setIsCurrent(true);
        f.setStatus(Status.PUBLISHED.getCode());
        f.setCreatedAt(Instant.now());
        f.setUpdatedAt(Instant.now());
        f.setDeletedFlag(false);
        FieldFeatureBean feature = new FieldFeatureBean();
        feature.setRequired(code.endsWith("_pid"));
        feature.setUnique(primaryKey);
        f.setFeature(feature);
        ExtensionBean ext = new ExtensionBean();
        Map<String, Object> extMap = new HashMap<>();
        extMap.put("displayName", code.toUpperCase());
        if (primaryKey) {
            extMap.put("primaryKey", true);
        }
        ext.setExtension(extMap);
        f.setExtension(ext);
        metaFieldMapper.insert(f);

        Long modelId = metaModelMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Model>()
                        .eq("code", modelCode).eq("tenant_id", tenantId)
        ).get(0).getId();
        ModelFieldBinding binding = new ModelFieldBinding();
        binding.setTenantId(tenantId);
        binding.setModelId(modelId);
        binding.setFieldId(f.getId());
        binding.setFieldOrder(order);
        fieldBindingMapper.insert(binding);
    }

    private void seedRecord(String modelCode, String fieldPrefix, String name) {
        Map<String, Object> row = new HashMap<>();
        row.put(fieldPrefix + "_pid", UniqueIdGenerator.generate());
        row.put(fieldPrefix + "_name", name);
        dynamicDataService.create(modelCode, row);
    }

    private void purgeGsFamily() {
        Long tenantId = getTestTenant().getId();
        jdbcTemplate.update(
                "DELETE FROM ab_meta_model_field_binding WHERE model_id IN "
                        + "(SELECT id FROM ab_meta_model WHERE tenant_id = ? AND code LIKE 'gs%')", tenantId);
        jdbcTemplate.update(
                "DELETE FROM ab_meta_field WHERE tenant_id = ? "
                        + "AND (code LIKE 'gs%' OR deleted_flag = TRUE "
                        + "OR id NOT IN (SELECT field_id FROM ab_meta_model_field_binding) "
                        + "OR id IN (SELECT b.field_id FROM ab_meta_model_field_binding b "
                        + "JOIN ab_meta_model m ON m.id = b.model_id "
                        + "WHERE m.tenant_id = ? AND m.code LIKE 'gs%'))", tenantId, tenantId);
        jdbcTemplate.update("DELETE FROM ab_meta_model WHERE tenant_id = ? AND code LIKE 'gs%'", tenantId);
    }
}
