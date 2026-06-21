package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.SchemaOperationResult;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency IT: proves that {@link DynamicDataService#incrementWithinCap} never
 * exceeds the cap under 200 racing threads, and that {@link DynamicDataService#increment}
 * has no lost updates.
 *
 * <p>Uses {@code NOT_SUPPORTED} propagation so every increment commits independently —
 * this makes the concurrency real (no shared transaction brackets the updates).
 *
 * <p>Harness copied verbatim from {@link DynamicDataJsonbUpdateIT} with {@code createFields}
 * changed to bind {@code pid} (string PK), {@code counter} (integer), {@code cap} (integer).
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Atomic increment: no cap overflow, no lost updates under concurrency")
class AtomicIncrementConcurrencyIT extends BaseIntegrationTest {

    @Autowired
    private DynamicDataService dynamicDataService;

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
    private boolean modelReady = false;
    private final List<String> createdPids = Collections.synchronizedList(new ArrayList<>());

    // ── Setup ────────────────────────────────────────────────────────────────

    @BeforeAll
    void initModelCode() {
        modelCode = "ai_ctr_" + System.currentTimeMillis();
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
            log.info("[atomic-it] model={} ready", modelCode);
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
     * 200 threads each attempt incrementWithinCap(+1, cap=100).
     * Final counter must be exactly 100 and exactly 100 threads must have been granted.
     */
    @Test
    @DisplayName("incrementWithinCap never exceeds cap under concurrency (200 threads, cap=100)")
    void incrementWithinCap_never_exceeds_cap_under_concurrency() throws Exception {
        setupTenantContext();

        Map<String, Object> row = new HashMap<>();
        row.put("counter", 0);
        row.put("cap", 100);
        Map<String, Object> created = dynamicDataService.create(modelCode, row);
        String pid = Objects.toString(created.get("pid"));
        createdPids.add(pid);

        int threads = 200;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger granted = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        long tenantId = getTestTenant().getId();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                MetaContext.setContext(tenantId, 0L, null, "system");
                try {
                    if (dynamicDataService.incrementWithinCap(modelCode, pid, "counter", 1L, "cap").isPresent()) {
                        granted.incrementAndGet();
                    }
                } finally {
                    MetaContext.clear();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        MetaContext.setContext(tenantId, 0L, null, "system");
        Map<String, Object> reloaded = dynamicDataService.getById(modelCode, pid);
        long finalCounter = ((Number) reloaded.get("counter")).longValue();
        log.info("[atomic-it] incrementWithinCap: finalCounter={}, granted={}", finalCounter, granted.get());

        assertThat(finalCounter)
                .as("Final counter must be exactly the cap (100)")
                .isEqualTo(100L);
        assertThat(granted.get())
                .as("Exactly 100 threads must have been granted (cap=100, 200 competed)")
                .isEqualTo(100);
    }

    /**
     * 200 threads each call increment(+1) with no cap.
     * Final counter must be exactly 200 — no lost updates.
     */
    @Test
    @DisplayName("increment has no lost updates under concurrency (200 threads, unbounded)")
    void increment_has_no_lost_updates_under_concurrency() throws Exception {
        setupTenantContext();

        Map<String, Object> row = new HashMap<>();
        row.put("counter", 0);
        row.put("cap", 0);   // cap value irrelevant — increment() passes null capCode
        Map<String, Object> created = dynamicDataService.create(modelCode, row);
        String pid = Objects.toString(created.get("pid"));
        createdPids.add(pid);

        int threads = 200;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        long tenantId = getTestTenant().getId();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                MetaContext.setContext(tenantId, 0L, null, "system");
                try {
                    dynamicDataService.increment(modelCode, pid, "counter", 1L);
                } finally {
                    MetaContext.clear();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        MetaContext.setContext(tenantId, 0L, null, "system");
        long finalCounter = ((Number) dynamicDataService.getById(modelCode, pid).get("counter")).longValue();
        log.info("[atomic-it] increment: finalCounter={}", finalCounter);

        assertThat(finalCounter)
                .as("Final counter must be 200 (no lost updates)")
                .isEqualTo(200L);
    }

    /**
     * When the cap column is NULL the increment must be denied (safe-by-default).
     */
    @Test
    @DisplayName("null cap denies increment (safe-by-default)")
    void null_cap_denies() {
        setupTenantContext();

        Map<String, Object> created = dynamicDataService.create(modelCode, Map.of("counter", 0, "cap", 5));
        String pid = Objects.toString(created.get("pid"));
        createdPids.add(pid);

        // Force cap to NULL via raw JDBC (bypasses any application-level NOT NULL defaults)
        jdbcTemplate.update("UPDATE mt_" + modelCode + " SET cap = NULL WHERE pid = ?", pid);

        long tenantId = getTestTenant().getId();
        MetaContext.setContext(tenantId, 0L, null, "system");
        try {
            assertThat(dynamicDataService.incrementWithinCap(modelCode, pid, "counter", 1L, "cap"))
                    .as("NULL cap must deny (safe-by-default)")
                    .isEmpty();
        } finally {
            MetaContext.clear();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void dropIfExists() {
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS mt_" + modelCode);
        } catch (Exception e) {
            log.debug("[atomic-it] dropIfExists table: {}", e.getMessage());
        }
        try {
            Long tenantId = getTestTenant().getId();
            // Remove bindings first (FK), then orphaned fields, then the model
            jdbcTemplate.update(
                    "DELETE FROM ab_meta_model_field_binding WHERE model_id IN "
                    + "(SELECT id FROM ab_meta_model WHERE code = ? AND tenant_id = ?)",
                    modelCode, tenantId);
            jdbcTemplate.update(
                    "DELETE FROM ab_meta_field WHERE tenant_id = ? "
                    + "AND id NOT IN (SELECT field_id FROM ab_meta_model_field_binding)",
                    tenantId);
            jdbcTemplate.update(
                    "DELETE FROM ab_meta_model WHERE code = ? AND tenant_id = ?",
                    modelCode, tenantId);
        } catch (Exception e) {
            log.debug("[atomic-it] dropIfExists meta: {}", e.getMessage());
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
        extMap.put("displayName", "Atomic Increment Concurrency IT Model");
        extMap.put("modelType", "entity");
        ext.setExtension(extMap);
        m.setExtension(ext);
        metaModelMapper.insert(m);
    }

    private void createFields() {
        bindField("pid",     "string",  true,  false, -1);
        bindField("counter", "integer", false, true,   1);
        bindField("cap",     "integer", false, true,   2);
    }

    private void bindField(String code, String dataType, boolean primaryKey, boolean required, int order) {
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
