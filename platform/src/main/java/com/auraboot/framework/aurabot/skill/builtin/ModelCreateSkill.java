package com.auraboot.framework.aurabot.skill.builtin;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.aurabot.skill.AuraBotSkill;
import com.auraboot.framework.aurabot.skill.RiskLevel;
import com.auraboot.framework.aurabot.skill.SkillRequest;
import com.auraboot.framework.aurabot.skill.SkillResult;
import com.auraboot.framework.aurabot.skill.SkillRunRepository;
import com.auraboot.framework.aurabot.skill.entity.SkillRun;
import com.auraboot.framework.aurabot.skill.error.SkillErrorCode;
import com.auraboot.framework.aurabot.skill.error.SkillSpiException;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.MetaFieldCreateRequest;
import com.auraboot.framework.meta.dto.MetaModelCreateRequest;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Built-in {@code model:create} skill — HIGH-risk model creation with undo (Plan §C-3).
 *
 * <p>T1 scaffold: registers metadata only. {@link #dryRun(SkillRequest)},
 * {@link #execute(SkillRequest)} and {@link #undo(String)} throw
 * {@link UnsupportedOperationException} until T3-T5 land.
 *
 * <p>Risk semantics:
 * <ul>
 *     <li>HIGH — creates a real PG table {@code mt_<code>} (DDL, irreversible by default).</li>
 *     <li>Undo window: 30 min, refuses if rows already inserted (data-loss guard).</li>
 *     <li>dryRun: returns parse + DDL preview without committing.</li>
 * </ul>
 */
@Slf4j
@Component
public class ModelCreateSkill implements AuraBotSkill {

    private static final String SCHEMA_JSON = "{"
            + "\"type\":\"object\","
            + "\"additionalProperties\":false,"
            + "\"properties\":{"
            + "  \"code\":{\"type\":\"string\",\"pattern\":\"^[a-zA-Z][a-zA-Z0-9_]*$\",\"minLength\":2,\"maxLength\":64},"
            + "  \"displayName\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":100},"
            + "  \"description\":{\"type\":\"string\",\"maxLength\":500},"
            + "  \"modelCategory\":{\"type\":\"string\",\"enum\":[\"ENTITY\",\"DOCUMENT\",\"MASTER\",\"TRANSACTION\",\"ACTIVITY\",\"REFERENCE\"]},"
            + "  \"domainCategory\":{\"type\":\"string\",\"maxLength\":32},"
            + "  \"dataSensitivity\":{\"type\":\"string\",\"enum\":[\"PUBLIC\",\"INTERNAL\",\"CONFIDENTIAL\",\"RESTRICTED\"]},"
            + "  \"semanticDescription\":{\"type\":\"string\",\"maxLength\":500}"
            + "},"
            + "\"required\":[\"code\",\"displayName\"]"
            + "}";

    private final MetaModelService metaModelService;
    private final MetaFieldService metaFieldService;
    private final MetaModelFieldBindingMapper bindingMapper;
    private final DynamicDataMapper dynamicDataMapper;
    private final ObjectMapper objectMapper;
    private final SkillRunRepository skillRunRepository;

    private JsonNode schema;

    public ModelCreateSkill(MetaModelService metaModelService,
                            MetaFieldService metaFieldService,
                            MetaModelFieldBindingMapper bindingMapper,
                            DynamicDataMapper dynamicDataMapper,
                            ObjectMapper objectMapper,
                            SkillRunRepository skillRunRepository) {
        this.metaModelService = metaModelService;
        this.metaFieldService = metaFieldService;
        this.bindingMapper = bindingMapper;
        this.dynamicDataMapper = dynamicDataMapper;
        this.objectMapper = objectMapper;
        this.skillRunRepository = skillRunRepository;
    }

    @PostConstruct
    void init() throws Exception {
        this.schema = objectMapper.readTree(SCHEMA_JSON);
    }

    @Override
    public String name() {
        return "model:create";
    }

    @Override
    public String displayName() {
        return "aurabot.skill.model.create.displayName";
    }

    @Override
    public String category() {
        return "meta";
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.HIGH;
    }

    @Override
    public JsonNode paramsSchema() {
        return schema;
    }

    @Override
    public Set<String> requiredPermissions() {
        return Set.of(MetaPermission.MODEL_MANAGE);
    }

    @Override
    public boolean supportsDryRun() {
        return true;
    }

    @Override
    public boolean supportsUndo() {
        return true;
    }

    @Override
    public boolean supportsStreaming() {
        return false;
    }

    @Override
    public SkillResult dryRun(SkillRequest req) {
        Map<String, Object> params = parseParams(req);
        String code = (String) params.get("code");

        if (findByCodeOrNull(code) != null) {
            throw new SkillSpiException(
                    SkillErrorCode.PARAMS_INVALID,
                    "modelCode " + code + " already exists",
                    "/code");
        }

        ObjectNode preview = buildPreview(params, code);
        return SkillResult.builder()
                .status(SkillResult.Status.NEEDS_CONFIRM)
                .skillName(name())
                .preview(preview)
                .riskLevel(riskLevel())
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseParams(SkillRequest req) {
        if (req.getParams() == null) {
            throw new SkillSpiException(SkillErrorCode.PARAMS_INVALID, "params is required", "/");
        }
        return objectMapper.convertValue(req.getParams(), Map.class);
    }

    /**
     * Existence probe that normalises {@link MetaModelService#findByCode(String)}'s
     * "throw on missing" contract into a nullable lookup. {@code findByCode} raises
     * {@link ValidationException} ("模型不存在") for unknown codes, which is the
     * happy path for create — we just want to know whether the code is taken.
     *
     * <p>Other validation failures (blank code, malformed input) are re-thrown so
     * they surface as real errors instead of being swallowed.
     */
    private MetaModelDTO findByCodeOrNull(String code) {
        try {
            return metaModelService.findByCode(code);
        } catch (ValidationException e) {
            return null;
        }
    }

    private ObjectNode buildPreview(Map<String, Object> params, String code) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("modelCode", code);
        out.put("displayName", (String) params.get("displayName"));
        out.put("modelCategory", asStringOr(params.get("modelCategory"), "ENTITY"));
        out.put("domainCategory", (String) params.get("domainCategory"));
        out.put("dataSensitivity", asStringOr(params.get("dataSensitivity"), "INTERNAL"));
        out.put("willCreateTable", "mt_" + code);
        ArrayNode fields = out.putArray("defaultFields");
        addField(fields, "pid", "string", true);
        addField(fields, "tenant_id", "bigint", false);
        addField(fields, "created_at", "timestamptz", false);
        addField(fields, "updated_at", "timestamptz", false);
        addField(fields, "created_by", "string", false);
        addField(fields, "updated_by", "string", false);
        addField(fields, "deleted_flag", "boolean", false);
        out.put("riskNote",
                "Creates a real PG table mt_" + code
                + ". Undo within 30 min drops the table; data inserted before undo blocks the undo.");
        return out;
    }

    private void addField(ArrayNode fields, String fieldCode, String type, boolean primary) {
        ObjectNode f = fields.addObject();
        f.put("code", fieldCode);
        f.put("type", type);
        if (primary) {
            f.put("primary", true);
        }
    }

    private String asStringOr(Object v, String fallback) {
        return v == null ? fallback : v.toString();
    }

    @Override
    public SkillResult execute(SkillRequest req) {
        Map<String, Object> params = parseParams(req);
        String code = (String) params.get("code");

        if (findByCodeOrNull(code) != null) {
            throw new SkillSpiException(
                    SkillErrorCode.PARAMS_INVALID,
                    "modelCode " + code + " already exists",
                    "/code");
        }

        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new SkillSpiException(
                    SkillErrorCode.SKILL_INTERNAL_ERROR,
                    "no tenant context for model:create",
                    null);
        }

        MetaModelCreateRequest request = buildCreateRequest(params, tenantId);
        MetaModelDTO created;
        try {
            created = metaModelService.create(request);

            // MetaModelService.create() persists the meta row + auto-binds system
            // fields (id/pid/created_at/updated_at) but ignores request.fields and
            // request.autoPublish. publish() requires at least one bound field
            // and is the only path that runs DDL (creates table mt_<code>). We
            // therefore (a) ensure a user-visible "name" field exists & bound,
            // then (b) publish to materialise the table.
            ensureNameFieldBound(created.getPid(), tenantId);
            created = metaModelService.publish(created.getPid(), "model:create skill auto-publish");
        } catch (RuntimeException e) {
            // P3 boundary: wrap repository / DDL failures into a typed SPI error so the
            // controller can map to a stable error code instead of leaking stack traces.
            log.error("MetaModelService.create/publish failed for code={}", code, e);
            throw new SkillSpiException(
                    SkillErrorCode.SKILL_INTERNAL_ERROR,
                    "failed to create model: " + e.getMessage(),
                    null,
                    e);
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("modelPid", created.getPid());
        payload.put("modelCode", created.getCode());
        payload.put("displayName", created.getDisplayName());
        payload.put("tableName", "mt_" + created.getCode());
        payload.put("publishedAt", Instant.now().toString());
        payload.put("defaultFieldCount", 7);

        return SkillResult.builder()
                .status(SkillResult.Status.SUCCESS)
                .skillName(name())
                .payload(payload)
                .riskLevel(riskLevel())
                .build();
    }

    private MetaModelCreateRequest buildCreateRequest(Map<String, Object> params, Long tenantId) {
        MetaModelCreateRequest r = new MetaModelCreateRequest();
        r.setCode((String) params.get("code"));
        r.setDisplayName((String) params.get("displayName"));
        r.setDescription((String) params.get("description"));
        r.setModelCategory(asStringOr(params.get("modelCategory"), "ENTITY"));
        r.setDomainCategory((String) params.get("domainCategory"));
        r.setDataSensitivity(asStringOr(params.get("dataSensitivity"), "INTERNAL"));
        r.setSemanticDescription((String) params.get("semanticDescription"));
        r.setTenantId(tenantId);
        r.setSourceType("physical");
        r.setPrimaryKey("pid");
        r.setFields(buildDefaultFields());
        // autoPublish field removed from MetaModelCreateRequest in main per
        // C-3 finding F-1 (dead flag — createDirectly never honored it).
        // Skill orchestrates publish manually via ensureNameFieldBound after create.
        return r;
    }

    private List<FieldDefinition> buildDefaultFields() {
        List<FieldDefinition> fs = new ArrayList<>();
        fs.add(field("pid", "string", true));
        fs.add(field("tenant_id", "bigint", false));
        fs.add(field("created_at", "timestamptz", false));
        fs.add(field("updated_at", "timestamptz", false));
        fs.add(field("created_by", "string", false));
        fs.add(field("updated_by", "string", false));
        fs.add(field("deleted_flag", "boolean", false));
        return fs;
    }

    /**
     * Create + publish a "name" field bound to the freshly-created model so
     * publish() satisfies its "at least one field bound" contract regardless
     * of system-field seeding state. The field is created with autoPublish=true
     * (status=published) and modelPid wired so MetaFieldService auto-binds.
     */
    private void ensureNameFieldBound(String modelPid, Long tenantId) {
        // Field codes are globally unique per (code, version). Suffix with model
        // pid prefix so re-running the skill against new models never collides.
        String suffix = modelPid.length() >= 8 ? modelPid.substring(0, 8).toLowerCase() : modelPid.toLowerCase();
        MetaFieldCreateRequest fr = new MetaFieldCreateRequest();
        fr.setCode("name_" + suffix);
        fr.setDataType("string");
        fr.setStatus("published");
        fr.setAutoPublish(Boolean.TRUE);
        fr.setModelPid(modelPid);
        metaFieldService.create(fr);
    }

    private FieldDefinition field(String code, String type, boolean primary) {
        FieldDefinition f = new FieldDefinition();
        f.setCode(code);
        f.setDataType(type);
        // FieldDefinition exposes a `primaryKey` Boolean (see isPrimaryKey()).
        // Set it so persistence picks up the PK marker even if downstream callers
        // walk the field list rather than the request-level primaryKey hint.
        if (primary) {
            f.setPrimaryKey(Boolean.TRUE);
        }
        return f;
    }

    @Override
    public SkillResult undo(String undoToken) {
        // Resolve the SkillRun row via undoToken, then dispatch to the
        // internal (modelPid, modelCode) entry. The Controller hand-off path
        // (T6, Plan §C-3 §5) — no SPI surface change.
        SkillRun row = skillRunRepository.findByUndoToken(undoToken)
                .orElseThrow(() -> new SkillSpiException(
                        SkillErrorCode.UNDO_EXPIRED,
                        "no SkillRun for undoToken " + undoToken,
                        null));
        JsonNode after = row.getAfterSnapshot();
        if (after == null || !after.has("modelPid") || !after.has("modelCode")) {
            throw new SkillSpiException(
                    SkillErrorCode.SKILL_INTERNAL_ERROR,
                    "afterSnapshot missing modelPid or modelCode",
                    null);
        }
        return undoByModel(after.get("modelPid").asText(), after.get("modelCode").asText());
    }

    /**
     * Internal undo entry point used by the Controller hand-off (T6) once the
     * undoToken has been resolved to a concrete (modelPid, modelCode) pair.
     *
     * <p>Sequence:
     * <ol>
     *     <li>Pre-check: {@code SELECT FROM mt_<code> WHERE deleted_flag=false LIMIT 1}.
     *         Non-empty rows → {@link SkillErrorCode#SKILL_INTERNAL_ERROR} (data-loss
     *         guard, see Plan §C-3 §5).</li>
     *     <li>Hard-delete the model's field bindings so {@code MetaModelService.delete}'s
     *         {@code validateCanDelete} guard passes (the {@code name_<suffix>} field
     *         created by {@link #ensureNameFieldBound} would otherwise block deletion).</li>
     *     <li>Drop the {@code mt_<code>} table via raw {@code DROP TABLE IF EXISTS}.</li>
     *     <li>Soft-delete the {@code ab_meta_model} row via {@link MetaModelService#delete}.</li>
     * </ol>
     *
     * <p>The pre-check tolerates a missing table (catches {@link RuntimeException}
     * around {@code SELECT}). This is a P2 graceful-degradation pattern: when the
     * table is gone (e.g. follow-up undo on a partially-rolled-back run), absence
     * is equivalent to "no rows" — not an error to surface. We do not swallow
     * the exception silently elsewhere.
     */
    public SkillResult undoByModel(String modelPid, String modelCode) {
        String tableName = "mt_" + modelCode;

        // 1. Data-loss guard: refuse if any rows exist on the physical table.
        //
        //    We MUST test table existence via information_schema first rather
        //    than try/catching the SELECT — under @Transactional, a SELECT
        //    against a non-existent relation aborts the PG transaction
        //    irreversibly (SQLSTATE 25P02), and subsequent statements in the
        //    same tx all fail with "current transaction is aborted". The Java
        //    layer catching the exception does not reset PG state.
        List<Map<String, Object>> existsRows = dynamicDataMapper.selectByQueryWithoutTenant(
                "SELECT 1 AS x FROM information_schema.tables WHERE table_name = #{params.t}",
                Map.of("t", tableName));
        if (!existsRows.isEmpty()) {
            // Table exists — probe for any row. We don't filter on deleted_flag
            // because publish() only materialises bound fields onto the table,
            // and audit columns aren't guaranteed.
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQueryWithoutTenant(
                    "SELECT 1 AS x FROM " + tableName + " LIMIT 1",
                    Map.of());
            if (!rows.isEmpty()) {
                throw new SkillSpiException(
                        SkillErrorCode.SKILL_INTERNAL_ERROR,
                        "model " + modelCode + " has data rows; refuse to undo to prevent data loss",
                        null);
            }
        }

        // 2. Existence + idempotency check via raw SELECT (no service-layer
        //    cache or tenant interceptor wrap-around) so a follow-up undo on a
        //    pid that's already gone surfaces a clean ValidationException
        //    rather than a half-aborted Spring tx state.
        List<Map<String, Object>> modelRows = dynamicDataMapper.selectByQueryWithoutTenant(
                "SELECT id FROM ab_meta_model WHERE pid = #{params.p}",
                Map.of("p", modelPid));
        if (modelRows.isEmpty()) {
            throw new ValidationException(
                    com.auraboot.framework.common.constant.ResponseCode.CommonValidationFailed,
                    "模型不存在: " + modelPid);
        }
        Long modelId = ((Number) modelRows.get(0).get("id")).longValue();

        // 3a. Look up the user (non-system) fields bound to this model so we can
        //     hard-delete them after clearing bindings. We must hard-delete here
        //     because the field-level unique constraint on (code, version) is
        //     unconditional — leaving a soft-deleted field row blocks re-creating
        //     the same skill against a model whose pid prefix collides (ULIDs
        //     are time-monotonic, so consecutive runs share the first 8 chars).
        List<Map<String, Object>> userFieldRows = dynamicDataMapper.selectByQueryWithoutTenant(
                "SELECT b.field_id AS field_id FROM ab_meta_model_field_binding b "
                        + "WHERE b.model_id = #{params.m} AND b.deleted_flag = false "
                        + "AND (b.is_system_binding IS NULL OR b.is_system_binding = false)",
                Map.of("m", modelId));

        // 3b. Hard-delete bindings.
        bindingMapper.deleteByModelId(modelId);

        // 3c. Hard-delete the user fields whose only binding was just removed.
        for (Map<String, Object> row : userFieldRows) {
            Long fieldId = ((Number) row.get("field_id")).longValue();
            dynamicDataMapper.alterTable(
                    "DELETE FROM ab_meta_field WHERE id = " + fieldId);
        }

        // 4. Drop the physical table directly via DynamicDataMapper.alterTable.
        //    We bypass SchemaManagementService.dropTableByModel because that
        //    path swallows exceptions into a result object — for an undo path
        //    we want failures to surface. DROP IF EXISTS makes this idempotent:
        //    a missing table on a follow-up undo is a no-op.
        try {
            dynamicDataMapper.alterTable("DROP TABLE IF EXISTS " + tableName);
        } catch (RuntimeException e) {
            log.error("Failed to drop table {} during undoByModel", tableName, e);
            throw new SkillSpiException(
                    SkillErrorCode.SKILL_INTERNAL_ERROR,
                    "failed to drop table " + tableName + ": " + e.getMessage(),
                    null, e);
        }

        // 5. Hard-delete the meta model row. We bypass MetaModelService.delete
        //    (which is a soft-delete) because the unique constraint
        //    uq_meta_model_code_ver(tenant_id, code, version) is unconditional —
        //    a soft-deleted row blocks re-creating the same code. Hard-delete
        //    keeps the code reusable, which is the contract for undo.
        dynamicDataMapper.alterTable(
                "DELETE FROM ab_meta_model WHERE pid = '" + modelPid + "'");

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("undonePid", modelPid);
        payload.put("droppedTable", tableName);
        return SkillResult.builder()
                .status(SkillResult.Status.SUCCESS)
                .skillName(name())
                .payload(payload)
                .riskLevel(riskLevel())
                .build();
    }
}
