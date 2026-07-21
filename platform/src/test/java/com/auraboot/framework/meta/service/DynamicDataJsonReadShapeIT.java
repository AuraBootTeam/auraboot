package com.auraboot.framework.meta.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.dto.SchemaOperationResult;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
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

/**
 * Read-shape contract for {@code json}/{@code jsonb} dynamic-model fields.
 *
 * <p>Root-cause guard for the 2026-07-21 numnan quick-lane outage: the read
 * paths ({@link DynamicDataService#getById}, {@link DynamicDataService#list},
 * plugin {@code DataAccessor.query}) leaked whatever the MyBatis/JDBC layer
 * produced for a jsonb column. Depending on build/runtime that was either a
 * JSON string or a raw {@code org.postgresql.util.PGobject} — and consumers
 * written against one shape silently broke on the other (quote quick lane
 * filtered every BOM row and priced nothing, with no error anywhere).
 *
 * <p>The contract pinned here: a field declared {@code json}/{@code jsonb}
 * comes back from every read path as a plain <b>JSON string</b> — never a
 * {@code PGobject}. JSON string (not parsed List/Map) is the deliberate
 * target: the 2026-07-21 consumer survey found the dominant in-process idiom
 * is {@code String.valueOf(value)} + Jackson parse (which breaks on parsed
 * structures), {@code (String)} casts that CCE on PGobject, and a frontend
 * that accepts strings everywhere. It also matches the platform's own
 * entity-level {@code JsonbStringTypeHandler} convention. Assertion failures
 * print the actual runtime class, so a regression is diagnosable from the
 * test output alone.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("json/jsonb dynamic field read-shape contract (getById / list / DataAccessor)")
class DynamicDataJsonReadShapeIT extends BaseIntegrationTest {

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

    private String modelCode;
    private String nameField;
    private String jsonArrayField;   // declared dataType "json"  — the quote/BOM spelling
    private String jsonbObjectField; // declared dataType "jsonb" — the crawler spelling
    private boolean modelReady = false;
    private final List<String> createdPids = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    void initModelCode() {
        String suffix = Long.toString(System.currentTimeMillis(), 36);
        modelCode = "js_shape_" + suffix;
        nameField = "js_name_" + suffix;
        jsonArrayField = "js_cells_" + suffix;
        jsonbObjectField = "js_conf_" + suffix;
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
            log.info("[json-shape-it] model={} ready", modelCode);
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

    private String createRow() {
        Map<String, Object> data = new HashMap<>();
        data.put(nameField, "shape-probe");
        data.put(jsonArrayField, List.of("R1,R2", "240Ω ±1% 0201", "3"));
        data.put(jsonbObjectField, Map.of("qps", 2, "tags", List.of("a", "b")));
        Map<String, Object> created = dynamicDataService.create(modelCode, data);
        String pid = Objects.toString(created.get("pid"));
        createdPids.add(pid);
        return pid;
    }

    @Test
    @DisplayName("getById returns JSON strings for json and jsonb fields — never PGobject")
    void getById_returns_json_strings() {
        String pid = createRow();
        // Shape isolation: this test pins the read SHAPE, not permissions. The
        // legacy single-record row gate currently denies the shared IT identity
        // on main (pre-existing red, tracked by fix/create-readback-permission-
        // regression); bypassing data permission keeps the shape contract
        // testable independently of that regression.
        Map<String, Object> row = com.auraboot.framework.application.tenant.MetaContext
                .runWithoutDataPermission(() -> dynamicDataService.getById(modelCode, pid));
        assertThat(row).isNotNull();
        assertParsedArray("getById", row.get(jsonArrayField));
        assertParsedObject("getById", row.get(jsonbObjectField));
    }

    @Test
    @DisplayName("list returns JSON strings for json and jsonb fields — never PGobject")
    void list_returns_json_strings() {
        String pid = createRow();
        DynamicQueryRequest request = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(50)
                .conditions(List.of(QueryCondition.builder()
                        .fieldName("pid")
                        .operator(QueryCondition.Operator.EQ)
                        .value(pid)
                        .build()))
                .build();
        List<Map<String, Object>> records = dynamicDataService.list(modelCode, request).getRecords();
        assertThat(records).hasSize(1);
        assertParsedArray("list", records.get(0).get(jsonArrayField));
        assertParsedObject("list", records.get(0).get(jsonbObjectField));
    }

    @Test
    @DisplayName("plugin DataAccessor.query returns JSON strings — the quote quick-lane read path")
    void dataAccessor_query_returns_json_strings() {
        createRow();
        long tenantId = getTestTenant().getId();
        List<Map<String, Object>> rows = backgroundDataAccessor.query(
                tenantId, modelCode, Map.of(nameField, "shape-probe"));
        assertThat(rows).isNotEmpty();
        assertParsedArray("DataAccessor.query", rows.get(0).get(jsonArrayField));
        assertParsedObject("DataAccessor.query", rows.get(0).get(jsonbObjectField));
    }

    // ── Shape assertions ─────────────────────────────────────────────────────

    private static final com.fasterxml.jackson.databind.ObjectMapper SHAPE_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private void assertParsedArray(String path, Object value) {
        assertThat(value)
                .as("%s: json array field must not be null", path)
                .isNotNull();
        assertThat(value)
                .as("%s: json array field must be a JSON string, was %s (value: %.120s)",
                        path, value.getClass().getName(), String.valueOf(value))
                .isInstanceOf(String.class);
        com.fasterxml.jackson.databind.JsonNode node = parse(path, (String) value);
        assertThat(node.isArray())
                .as("%s: the string must parse to a JSON array, was: %.120s", path, value)
                .isTrue();
        assertThat(node.size())
                .as("%s: parsed array must retain its elements", path)
                .isEqualTo(3);
        assertThat(node.get(1).asText())
                .as("%s: unicode payload must survive the round trip", path)
                .contains("240Ω");
    }

    private void assertParsedObject(String path, Object value) {
        assertThat(value)
                .as("%s: jsonb object field must not be null", path)
                .isNotNull();
        assertThat(value)
                .as("%s: jsonb object field must be a JSON string, was %s (value: %.120s)",
                        path, value.getClass().getName(), String.valueOf(value))
                .isInstanceOf(String.class);
        com.fasterxml.jackson.databind.JsonNode node = parse(path, (String) value);
        assertThat(node.isObject())
                .as("%s: the string must parse to a JSON object, was: %.120s", path, value)
                .isTrue();
        assertThat(node.get("tags") != null && node.get("tags").isArray())
                .as("%s: nested array inside the object must survive the round trip", path)
                .isTrue();
    }

    private com.fasterxml.jackson.databind.JsonNode parse(String path, String json) {
        try {
            return SHAPE_MAPPER.readTree(json);
        } catch (Exception e) {
            throw new AssertionError(path + ": value is a string but not valid JSON: " + json, e);
        }
    }

    // ── Fixture helpers (mirrors DynamicDataJsonbUpdateIT) ───────────────────

    private void dropIfExists() {
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS mt_" + modelCode);
        } catch (Exception e) {
            log.debug("[json-shape-it] dropIfExists table: {}", e.getMessage());
        }
        try {
            Long tenantId = getTestTenant().getId();
            jdbcTemplate.update(
                    "DELETE FROM ab_meta_model_field_binding WHERE model_id IN "
                    + "(SELECT id FROM ab_meta_model WHERE code = ? AND tenant_id = ?)",
                    modelCode, tenantId);
            jdbcTemplate.update(
                    "DELETE FROM ab_meta_field WHERE tenant_id = ? AND code IN (?, ?, ?)",
                    tenantId, nameField, jsonArrayField, jsonbObjectField);
            jdbcTemplate.update(
                    "DELETE FROM ab_meta_model WHERE code = ? AND tenant_id = ?",
                    modelCode, tenantId);
        } catch (Exception e) {
            log.debug("[json-shape-it] dropIfExists meta: {}", e.getMessage());
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
        extMap.put("displayName", "JSON Read Shape IT Model");
        extMap.put("modelType", "entity");
        ext.setExtension(extMap);
        m.setExtension(ext);
        metaModelMapper.insert(m);
    }

    private void createFields() {
        bindField(nameField, "string", 1);
        bindField(jsonArrayField, "json", 2);
        bindField(jsonbObjectField, "jsonb", 3);
    }

    private void bindField(String code, String dataType, int order) {
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

        ExtensionBean ext = new ExtensionBean();
        Map<String, Object> extMap = new HashMap<>();
        extMap.put("displayName", code.toUpperCase(Locale.ROOT));
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
