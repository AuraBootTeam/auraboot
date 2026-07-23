package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.dto.SchemaOperationResult;
import com.auraboot.framework.plugin.pf4j.BackgroundDataAccessorImpl;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Integration test: JSONB column updates must not throw
 * "column is of type jsonb but expression is of type character varying".
 *
 * <p>Covers the two primary write paths used by plugin background components:
 * <ol>
 *   <li>{@link DynamicDataService#update} — used directly and via
 *       {@link BackgroundDataAccessorImpl#update}.</li>
 *   <li>{@link BackgroundDataAccessorImpl#update} — tenant-scoped wrapper used
 *       by background components such as {@code JobLifecycleWatcher}.</li>
 * </ol>
 *
 * <p>Regression guard for BUG-2 (crawler golden E2E): updating
 * {@code cr_cj_seed_urls} / {@code cr_csp_default_parser_config} (jsonb)
 * via the {@code BackgroundDataAccessor} path previously triggered a
 * {@code PSQLException} because the update path bypassed the
 * {@code ::jsonb} cast required by PostgreSQL.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("JSONB update correctness via DynamicDataService + BackgroundDataAccessor")
class DynamicDataJsonbUpdateIT extends BaseIntegrationTest {

    @Autowired
    private DynamicDataService dynamicDataService;

    @Autowired
    private BackgroundDataAccessorImpl backgroundDataAccessor;

    @Autowired
    private MetaModelMapper metaModelMapper;

    @Autowired
    private MetaFieldMapper metaFieldMapper;

    @Autowired
    private MetaModelFieldBindingMapper fieldBindingMapper;

    @Autowired
    private SchemaManagementService schemaManagementService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Unique per test-run to avoid cross-test contamination
    private String modelCode;
    private String nameField;
    private String configField;
    private String stampField;
    private boolean modelReady = false;
    private final List<String> createdPids = Collections.synchronizedList(new ArrayList<>());

    // ── Setup ────────────────────────────────────────────────────────────────

    @BeforeAll
    void initModelCode() {
        String suffix = Long.toString(System.currentTimeMillis(), 36);
        modelCode = "jb_upd_" + suffix;
        nameField = "jb_name_" + suffix;
        configField = "jb_config_" + suffix;
        stampField = "jb_stamp_" + suffix;
    }

    @BeforeEach
    void ensureModel() {
        setupTenantContext();
        if (!modelReady) {
            dropIfExists();
            createModel();
            createFields();
            createPhysicalTable();
            modelReady = true;
            log.info("[jsonb-it] model={} ready", modelCode);
        }
    }

    @AfterEach
    void cleanRows() {
        for (String pid : new ArrayList<>(createdPids)) {
            try { dynamicDataService.delete(modelCode, pid); } catch (Exception ignored) {}
        }
        createdPids.clear();
    }

    @AfterAll
    void teardown() {
        dropIfExists();
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    /**
     * Inserting a record with a jsonb array field must succeed without cast error.
     * Baseline: if insert is broken the update tests are moot.
     */
    @Test
    @Order(1)
    @DisplayName("create with jsonb array field — no PSQLException")
    void create_with_jsonb_array_field_succeeds() {
        Map<String, Object> data = new HashMap<>();
        data.put(nameField, "create-jsonb-test");
        data.put(configField, List.of("https://a.example.com", "https://b.example.com"));

        Map<String, Object> created = dynamicDataService.create(modelCode, data);

        assertThat(created).isNotNull();
        String pid = Objects.toString(created.get("pid"));
        createdPids.add(pid);
        assertThat(pid).isNotBlank();
        log.info("[jsonb-it] created pid={}", pid);
    }

    /**
     * Updating a record's jsonb column via {@link DynamicDataService#update}
     * must not throw PSQLException and must persist the new value.
     *
     * <p>This is the core regression test for BUG-2. Before the fix, the update
     * path called {@code DynamicDataMapper.update} (no {@code ::jsonb} cast),
     * causing PostgreSQL to reject the query with
     * "column is of type jsonb but expression is of type character varying".
     */
    @Test
    @Order(2)
    @DisplayName("update jsonb column via DynamicDataService — no PSQLException, value persists")
    void update_jsonb_column_via_dynamicDataService_persists() {
        // Create a row with an initial jsonb value
        Map<String, Object> initialData = new HashMap<>();
        initialData.put(nameField, "update-jsonb-test");
        initialData.put(configField, List.of("https://initial.example.com"));
        Map<String, Object> created = dynamicDataService.create(modelCode, initialData);
        String pid = Objects.toString(created.get("pid"));
        createdPids.add(pid);

        // Update: replace the jsonb array — must not throw PSQLException
        List<String> newUrls = List.of("https://new1.example.com", "https://new2.example.com");
        Map<String, Object> patch = new HashMap<>();
        patch.put(configField, newUrls);

        assertThatCode(() -> MetaContext.runWithoutDataPermission(
                () -> { dynamicDataService.update(modelCode, pid, patch); }))
                .as("DynamicDataService.update must not throw PSQLException for jsonb column")
                .doesNotThrowAnyException();

        // Verify the new value is actually persisted (not the old one)
        Map<String, Object> reloaded = MetaContext.runWithoutDataPermission(
                () -> dynamicDataService.getById(modelCode, pid));
        assertThat(reloaded).isNotNull();
        Object rawConfig = reloaded.get(configField);
        assertThat(rawConfig).isNotNull();
        // The persisted value may come back as List<String> or String depending on JSONB projection
        String configStr = rawConfig instanceof String s ? s : rawConfig.toString();
        assertThat(configStr)
                .as("Persisted jsonb value must contain new URL")
                .contains("new1.example.com");
    }

    /**
     * Updating a record's jsonb column via {@link BackgroundDataAccessorImpl#update}
     * (the tenant-scoped accessor used by plugin background components) must not
     * throw PSQLException and must persist the new value.
     *
     * <p>Directly mirrors the {@code JobLifecycleWatcher} call pattern:
     * {@code backgroundDataAccessor.update(tenantId, modelCode, pid, patch)}.
     */
    @Test
    @Order(3)
    @DisplayName("update jsonb column via BackgroundDataAccessor — no PSQLException, value persists")
    void update_jsonb_column_via_backgroundDataAccessor_persists() {
        long tenantId = getTestTenant().getId();

        // Create row
        Map<String, Object> initialData = new HashMap<>();
        initialData.put(nameField, "bg-accessor-jsonb-test");
        initialData.put(configField, Map.of("qps", 2, "concurrency", 4));
        Map<String, Object> created = dynamicDataService.create(modelCode, initialData);
        String pid = Objects.toString(created.get("pid"));
        createdPids.add(pid);

        // Update via BackgroundDataAccessor (the exact path used by JobLifecycleWatcher)
        Map<String, Object> newConfig = Map.of("qps", 5, "concurrency", 8, "burst", 20);
        Map<String, Object> patch = new HashMap<>();
        patch.put(configField, newConfig);

        assertThatCode(() -> MetaContext.runWithoutDataPermission(
                () -> { backgroundDataAccessor.update(tenantId, modelCode, pid, patch); }))
                .as("BackgroundDataAccessor.update must not throw PSQLException for jsonb column")
                .doesNotThrowAnyException();

        // Verify value persisted
        Map<String, Object> reloaded = MetaContext.runWithoutDataPermission(
                () -> dynamicDataService.getById(modelCode, pid));
        assertThat(reloaded).isNotNull();
        Object rawConfig = reloaded.get(configField);
        assertThat(rawConfig).isNotNull();
        String configStr = rawConfig instanceof String s ? s : rawConfig.toString();
        assertThat(configStr)
                .as("Persisted jsonb config must reflect updated qps=5")
                .contains("5");
    }

    /**
     * Updating a non-jsonb field when the model has jsonb fields must also succeed.
     * Guards against overly aggressive jsonb-awareness breaking plain updates.
     */
    @Test
    @Order(4)
    @DisplayName("update non-jsonb column when model has jsonb fields — succeeds")
    void update_non_jsonb_column_on_jsonb_model_succeeds() {
        Map<String, Object> initialData = new HashMap<>();
        initialData.put(nameField, "plain-field-update-test");
        initialData.put(configField, List.of("https://example.com"));
        Map<String, Object> created = dynamicDataService.create(modelCode, initialData);
        String pid = Objects.toString(created.get("pid"));
        createdPids.add(pid);

        Map<String, Object> patch = Map.of(nameField, "updated-name");

        assertThatCode(() -> MetaContext.runWithoutDataPermission(
                () -> { dynamicDataService.update(modelCode, pid, patch); }))
                .as("Non-jsonb field update on model with jsonb fields must not throw")
                .doesNotThrowAnyException();

        Map<String, Object> reloaded = MetaContext.runWithoutDataPermission(
                () -> dynamicDataService.getById(modelCode, pid));
        assertThat(reloaded.get(nameField)).isEqualTo("updated-name");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void dropIfExists() {
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS mt_" + modelCode);
        } catch (Exception e) {
            log.debug("[jsonb-it] dropIfExists table: {}", e.getMessage());
        }
        try {
            Long tenantId = getTestTenant().getId();
            // Remove bindings first (FK), then this test's unique fields, then the model.
            jdbcTemplate.update(
                    "DELETE FROM ab_meta_model_field_binding WHERE model_id IN "
                    + "(SELECT id FROM ab_meta_model WHERE code = ? AND tenant_id = ?)",
                    modelCode, tenantId);
            jdbcTemplate.update(
                    "DELETE FROM ab_meta_field WHERE tenant_id = ? AND code IN (?, ?)",
                    tenantId, nameField, configField);
            jdbcTemplate.update(
                    "DELETE FROM ab_meta_model WHERE code = ? AND tenant_id = ?",
                    modelCode, tenantId);
        } catch (Exception e) {
            log.debug("[jsonb-it] dropIfExists meta: {}", e.getMessage());
        }
    }

    private void createModel() {
        Model m = new Model();
        m.setPid(UniqueIdGenerator.generate());
        m.setTenantId(getTestTenant().getId());
        m.setCode(modelCode);
        m.setVersion(1);
        m.setIsCurrent(true);
        m.setStatus(Status.PUBLISHED.getCode());
        m.setCreatedAt(Instant.now());
        m.setUpdatedAt(Instant.now());
        m.setDeletedFlag(false);
        ExtensionBean ext = new ExtensionBean();
        Map<String, Object> extMap = new HashMap<>();
        extMap.put("displayName", "JSONB Update IT Model");
        extMap.put("modelType", "entity");
        ext.setExtension(extMap);
        m.setExtension(ext);
        metaModelMapper.insert(m);
    }

    /**
     * A caller may hand {@code update} an immutable map — {@code Map.of(...)} is the obvious way
     * to express a two-field patch, and {@code SiteKeyCommandHandler} already does exactly that.
     *
     * <p>It used to work only by luck. {@code payloadTemporalNormalizer.normalize()} rewrites
     * temporal values in the payload <em>in place</em>, so the moment a patch happened to carry a
     * date or time, the immutable map threw {@link UnsupportedOperationException} — with no
     * message, from deep inside the service, and only for that one shape of payload. That is how
     * {@code faq:publish} failed: it patched a status, a document pid, and a review timestamp.
     *
     * <p>update() now copies its argument before touching it. The point of this test is the
     * combination — immutable map AND a temporal field. Either alone passes even without the fix.
     */
    @Test
    @Order(5)
    @DisplayName("update with an immutable payload carrying a datetime — no UnsupportedOperationException")
    void update_with_immutable_payload_containing_temporal_value_succeeds() {
        Map<String, Object> initial = new HashMap<>();
        initial.put(nameField, "immutable-payload-test");
        Map<String, Object> created = dynamicDataService.create(modelCode, initial);
        String pid = Objects.toString(created.get("pid"));
        createdPids.add(pid);

        String stamp = Instant.now().toString();
        // Map.of is deliberate: the caller's map must survive being handed to the service.
        Map<String, Object> immutablePatch = Map.of(nameField, "patched", stampField, stamp);

        assertThatCode(() -> MetaContext.runWithoutDataPermission(
                () -> { dynamicDataService.update(modelCode, pid, immutablePatch); }))
                .as("update must not mutate the caller's map — an immutable patch with a "
                        + "temporal field previously threw a message-less UnsupportedOperationException")
                .doesNotThrowAnyException();

        Map<String, Object> reloaded = MetaContext.runWithoutDataPermission(
                () -> dynamicDataService.getById(modelCode, pid));
        assertThat(reloaded).isNotNull();
        assertThat(Objects.toString(reloaded.get(nameField))).isEqualTo("patched");
        assertThat(reloaded.get(stampField))
                .as("the timestamp must actually persist, not merely fail to throw")
                .isNotNull();

        // And the caller's map is still exactly what it handed over.
        assertThat(immutablePatch.get(stampField))
                .as("the service must not have rewritten the caller's payload")
                .isEqualTo(stamp);
    }

    private void createFields() {
        // Plain string field
        bindField(nameField, "string", false, false, 1);
        // JSONB host column — this is the bug target
        bindField(configField, "jsonb", false, false, 2);
        // Temporal column — the immutable-payload regression above needs one: the temporal
        // normalizer only rewrites the payload when it actually contains a date/time value.
        bindField(stampField, "datetime", false, false, 3);
    }

    private void bindField(String code, String dataType, boolean primaryKey, boolean required, int order) {
        Field f = new Field();
        f.setPid(UniqueIdGenerator.generate());
        f.setTenantId(getTestTenant().getId());
        f.setCode(code);
        f.setDataType(dataType);   // "jsonb" is passed verbatim so getJsonbHostColumns picks it up
        f.setVersion(1);
        f.setIsCurrent(true);
        f.setStatus(Status.PUBLISHED.getCode());
        f.setCreatedAt(Instant.now());
        f.setUpdatedAt(Instant.now());
        f.setDeletedFlag(false);

        FieldFeatureBean feature = new FieldFeatureBean();
        feature.setRequired(required);
        feature.setUnique(primaryKey);
        f.setFeature(feature);

        ExtensionBean ext = new ExtensionBean();
        Map<String, Object> extMap = new HashMap<>();
        extMap.put("displayName", code.toUpperCase());
        if (primaryKey) extMap.put("primaryKey", true);
        ext.setExtension(extMap);
        f.setExtension(ext);

        metaFieldMapper.insert(f);

        Long tenantId = getTestTenant().getId();
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

    private void createPhysicalTable() {
        SchemaOperationResult result = schemaManagementService.createTableByModel(modelCode);
        if (!result.isSuccess()) {
            throw new RuntimeException("Physical table creation failed: " + result.getErrorMessage());
        }
    }
}
