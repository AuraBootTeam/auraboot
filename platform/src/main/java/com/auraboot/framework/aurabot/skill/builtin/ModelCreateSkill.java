package com.auraboot.framework.aurabot.skill.builtin;

import com.auraboot.framework.aurabot.skill.AuraBotSkill;
import com.auraboot.framework.aurabot.skill.RiskLevel;
import com.auraboot.framework.aurabot.skill.SkillRequest;
import com.auraboot.framework.aurabot.skill.SkillResult;
import com.auraboot.framework.aurabot.skill.error.SkillErrorCode;
import com.auraboot.framework.aurabot.skill.error.SkillSpiException;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
    private final DynamicDataMapper dynamicDataMapper;
    private final ObjectMapper objectMapper;

    private JsonNode schema;

    public ModelCreateSkill(MetaModelService metaModelService,
                            DynamicDataMapper dynamicDataMapper,
                            ObjectMapper objectMapper) {
        this.metaModelService = metaModelService;
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

        MetaModelDTO existing = metaModelService.findByCode(code);
        if (existing != null) {
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
        throw new UnsupportedOperationException("model:create execute not implemented yet (T4)");
    }

    @Override
    public SkillResult undo(String undoToken) {
        throw new UnsupportedOperationException("model:create undo not implemented yet (T5)");
    }
}
