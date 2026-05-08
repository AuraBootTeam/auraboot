package com.auraboot.framework.aurabot.skill.builtin;

import com.auraboot.framework.aurabot.skill.AuraBotSkill;
import com.auraboot.framework.aurabot.skill.RiskLevel;
import com.auraboot.framework.aurabot.skill.SkillRequest;
import com.auraboot.framework.aurabot.skill.SkillResult;
import com.auraboot.framework.aurabot.skill.error.SkillErrorCode;
import com.auraboot.framework.aurabot.skill.error.SkillSpiException;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Built-in {@code field:add} skill (Spec §5, hybrid action).
 *
 * <p>Wraps {@link MetaFieldService#addToModel} so AuraBot can add a column to
 * an existing dynamic model on behalf of the user. Risk MEDIUM →
 * {@link #supportsDryRun()} returns {@code true} so the validator pipeline
 * issues a preview token before {@link #execute} is allowed.
 *
 * <p>{@link #supportsUndo()} is {@code true}: undo (T9) drops the column via
 * {@link MetaFieldService#removeFromModel} with {@code refuseIfDataExists =
 * true} so a column that has already collected user data cannot be silently
 * destroyed.
 *
 * <p><strong>Stub status:</strong> at C-4 T6 only metadata + schema are wired
 * up. {@link #dryRun} arrives in T7, {@link #execute} in T8, {@link #undo} in
 * T9 — each remains {@code UnsupportedOperationException} until then so any
 * accidental wiring fails loud.
 */
@Slf4j
@Component
public class FieldAddSkill implements AuraBotSkill {

    /**
     * JSON Schema for the {@code field:add} params envelope.
     *
     * <p>Spec §5.2 — {@code modelCode}/{@code code}/{@code dataType} required;
     * {@code displayName}/{@code required}/{@code maxLength} optional.
     * {@code additionalProperties:false} so callers cannot smuggle untyped
     * keys through. {@code dataType} is a closed enum of the 9 abstract types
     * accepted by {@link MetaFieldService#addToModel}.
     */
    private static final String SCHEMA_JSON = "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            + "\"modelCode\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9_]{0,62}$\"},"
            + "\"code\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9_]{0,62}$\"},"
            + "\"dataType\":{\"type\":\"string\",\"enum\":["
            + "\"string\",\"text\",\"integer\",\"long\",\"decimal\","
            + "\"boolean\",\"date\",\"datetime\",\"json\""
            + "]},"
            + "\"displayName\":{\"type\":\"string\",\"maxLength\":128},"
            + "\"required\":{\"type\":\"boolean\"},"
            + "\"maxLength\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":4096}"
            + "},"
            + "\"required\":[\"modelCode\",\"code\",\"dataType\"],"
            + "\"additionalProperties\":false"
            + "}";

    private final MetaFieldService metaFieldService;
    private final MetaModelService metaModelService;
    private final ObjectMapper objectMapper;

    private JsonNode schema;

    public FieldAddSkill(MetaFieldService metaFieldService,
                         MetaModelService metaModelService,
                         ObjectMapper objectMapper) {
        this.metaFieldService = metaFieldService;
        this.metaModelService = metaModelService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() throws Exception {
        this.schema = objectMapper.readTree(SCHEMA_JSON);
    }

    @Override
    public String name() {
        return "field:add";
    }

    @Override
    public String displayName() {
        return "aurabot.skill.field.add.displayName";
    }

    @Override
    public String category() {
        return "meta";
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.MEDIUM;
    }

    @Override
    public JsonNode paramsSchema() {
        return schema;
    }

    @Override
    public Set<String> requiredPermissions() {
        // Registry uses containsAll — caller must hold BOTH.
        return Set.of("MODEL.UPDATE", "FIELD.CREATE");
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

        String modelCode = stringParam(params, "modelCode");
        String code = stringParam(params, "code");
        String dataType = stringParam(params, "dataType");

        // 1) Resolve target model — F-2 throws ValidationException when
        //    missing. We translate to PARAMS_INVALID at /modelCode so the FE
        //    can highlight the offending field instead of treating this as
        //    an internal error.
        MetaModelDTO model;
        try {
            model = metaModelService.findByCode(modelCode);
        } catch (ValidationException ve) {
            throw new SkillSpiException(SkillErrorCode.PARAMS_INVALID,
                    "modelCode " + modelCode + " not found",
                    "/modelCode", ve);
        }
        if (model == null) {
            // Defend against future contract relaxation (returning null).
            throw new SkillSpiException(SkillErrorCode.PARAMS_INVALID,
                    "modelCode " + modelCode + " not found",
                    "/modelCode");
        }

        String storageCode = modelCode + "_" + code;

        // 2) Reject duplicate field — getFieldDefinition THROWS when the
        //    field is absent, so a successful return is the conflict signal.
        FieldDefinition existing = null;
        try {
            existing = metaModelService.getFieldDefinition(modelCode, code);
        } catch (RuntimeException re) {
            // Expected path: field absent. Impl throws MetaServiceException
            // with "Field not found" — swallow and continue.
            log.debug("field:add dryRun — field {} not yet on model {} ({})",
                    code, modelCode, re.getMessage());
        }
        if (existing != null) {
            throw new SkillSpiException(SkillErrorCode.PARAMS_INVALID,
                    "field code " + code + " already exists on model " + modelCode,
                    "/code");
        }

        // 3) Compose preview payload. Spec §5.3: server intentionally does
        //    NOT predict pgColumnType / DDL string here — the actual storage
        //    type is derived only at execute time by addToModel, so the
        //    preview commits only to the abstract dataType.
        ObjectNode preview = objectMapper.createObjectNode();
        preview.put("modelCode", modelCode);
        preview.put("fieldCode", code);
        preview.put("storageCode", storageCode);
        preview.put("dataType", dataType);
        Object displayName = params.get("displayName");
        if (displayName instanceof String dn && !dn.isBlank()) {
            preview.put("displayName", dn);
        }
        Object reqFlag = params.get("required");
        preview.put("required", reqFlag instanceof Boolean b ? b : Boolean.FALSE);
        Object maxLen = params.get("maxLength");
        if (maxLen instanceof Number ml) {
            preview.put("maxLength", ml.intValue());
        }
        preview.put("summary",
                "Add field '" + code + "' (" + dataType + ") to model '" + modelCode + "'");
        preview.put("riskNote", "aurabot.skill.field.add.preview.riskNote");

        return SkillResult.builder()
                .status(SkillResult.Status.NEEDS_CONFIRM)
                .skillName(name())
                .preview(preview)
                .riskLevel(riskLevel())
                .build();
    }

    @Override
    public SkillResult execute(SkillRequest req) {
        // T8 lands real impl. Stub fails loud so accidental wiring is caught.
        throw new UnsupportedOperationException(name() + " execute not implemented yet");
    }

    @Override
    public SkillResult undo(String undoToken) {
        // T9 lands real impl.
        throw new UnsupportedOperationException(name() + " undo not implemented yet");
    }

    /**
     * Convert {@link SkillRequest#getParams()} ({@link JsonNode}) to a typed
     * {@link Map}. Null-guards so a malformed request surfaces as
     * {@code PARAMS_INVALID} at {@code "/"} rather than NPE inside the impl.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseParams(SkillRequest req) {
        if (req == null || req.getParams() == null || req.getParams().isNull()) {
            throw new SkillSpiException(SkillErrorCode.PARAMS_INVALID,
                    "params is required", "/");
        }
        try {
            return (Map<String, Object>) objectMapper.convertValue(req.getParams(), Map.class);
        } catch (IllegalArgumentException iae) {
            throw new SkillSpiException(SkillErrorCode.PARAMS_INVALID,
                    "params is not a JSON object: " + iae.getMessage(), "/", iae);
        }
    }

    private static String stringParam(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null) {
            // Schema validation guarantees presence; defensive fallback.
            throw new SkillSpiException(SkillErrorCode.PARAMS_INVALID,
                    key + " is required", "/" + key);
        }
        return v.toString();
    }
}
