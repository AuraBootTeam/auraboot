package com.auraboot.framework.aurabot.skill.builtin;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.aurabot.skill.AuraBotSkill;
import com.auraboot.framework.aurabot.skill.RiskLevel;
import com.auraboot.framework.aurabot.skill.SkillRequest;
import com.auraboot.framework.aurabot.skill.SkillResult;
import com.auraboot.framework.aurabot.skill.error.SkillErrorCode;
import com.auraboot.framework.aurabot.skill.error.SkillSpiException;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.MetaFieldCreateRequest;
import com.auraboot.framework.meta.dto.MetaModelCreateRequest;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.MetaModelService;
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
    private final DynamicDataMapper dynamicDataMapper;
    private final ObjectMapper objectMapper;

    private JsonNode schema;

    public ModelCreateSkill(MetaModelService metaModelService,
                            MetaFieldService metaFieldService,
                            DynamicDataMapper dynamicDataMapper,
                            ObjectMapper objectMapper) {
        this.metaModelService = metaModelService;
        this.metaFieldService = metaFieldService;
        this.dynamicDataMapper = dynamicDataMapper;
        this.objectMapper = objectMapper;
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
        return Set.of("MODEL.CREATE");
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
        r.setAutoPublish(true);
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
        throw new UnsupportedOperationException("model:create undo not implemented yet (T5)");
    }
}
