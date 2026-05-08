package com.auraboot.framework.aurabot.skill.builtin;

import com.auraboot.framework.aurabot.skill.AuraBotSkill;
import com.auraboot.framework.aurabot.skill.RiskLevel;
import com.auraboot.framework.aurabot.skill.SkillRequest;
import com.auraboot.framework.aurabot.skill.SkillResult;
import com.auraboot.framework.aurabot.skill.SkillRunRepository;
import com.auraboot.framework.aurabot.skill.entity.SkillRun;
import com.auraboot.framework.aurabot.skill.error.SkillErrorCode;
import com.auraboot.framework.aurabot.skill.error.SkillSpiException;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.dto.AddFieldRequest;
import com.auraboot.framework.meta.dto.AddFieldResult;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.RemoveFieldRequest;
import com.auraboot.framework.meta.exception.ColumnHasDataException;
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
            + "\"string\",\"text\",\"int\",\"long\",\"decimal\","
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
    private final SkillRunRepository skillRunRepository;

    private JsonNode schema;

    public FieldAddSkill(MetaFieldService metaFieldService,
                         MetaModelService metaModelService,
                         ObjectMapper objectMapper,
                         SkillRunRepository skillRunRepository) {
        this.metaFieldService = metaFieldService;
        this.metaModelService = metaModelService;
        this.objectMapper = objectMapper;
        this.skillRunRepository = skillRunRepository;
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
        Map<String, Object> params = parseParams(req);

        String modelCode = stringParam(params, "modelCode");
        String code = stringParam(params, "code");
        String dataType = stringParam(params, "dataType");

        Object displayNameObj = params.get("displayName");
        String displayName = displayNameObj instanceof String dn && !dn.isBlank() ? dn : null;
        Boolean required = Boolean.TRUE.equals(params.get("required")) ? Boolean.TRUE : null;
        Object maxLenObj = params.get("maxLength");
        Integer maxLength = maxLenObj instanceof Number ml ? ml.intValue() : null;

        AddFieldRequest serviceReq = AddFieldRequest.builder()
                .modelCode(modelCode)
                .code(code)
                .dataType(dataType)
                .displayName(displayName)
                .required(required)
                .maxLength(maxLength)
                .build();

        AddFieldResult result;
        try {
            result = metaFieldService.addToModel(serviceReq);
        } catch (ValidationException ve) {
            String msg = ve.getMessage() == null ? "" : ve.getMessage();
            String fieldPath = inferFieldPath(msg);
            throw new SkillSpiException(SkillErrorCode.PARAMS_INVALID, msg, fieldPath, ve);
        } catch (RuntimeException re) {
            log.error("field:add execute failed for modelCode={} code={}", modelCode, code, re);
            throw new SkillSpiException(SkillErrorCode.SKILL_INTERNAL_ERROR,
                    "failed to add field: " + re.getMessage(), null, re);
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("fieldPid", result.getFieldPid());
        payload.put("modelCode", modelCode);
        payload.put("fieldCode", code);
        payload.put("storageCode", result.getStorageCode());
        payload.put("columnName", result.getColumnName());
        payload.put("tableName", result.getTableName());
        payload.put("pgColumnType", result.getPgColumnType());
        if (result.getAddedAt() != null) {
            payload.put("addedAt", result.getAddedAt().toString());
        }

        return SkillResult.builder()
                .status(SkillResult.Status.SUCCESS)
                .skillName(name())
                .payload(payload)
                .riskLevel(riskLevel())
                .build();
    }

    /**
     * Map a {@link ValidationException} message coming from
     * {@link MetaFieldService#addToModel} back to a JSON-Pointer fieldPath so
     * the FE can highlight the offending param. Order matters — {@code
     * modelCode} is checked first because the impl message
     * {@code "modelCode not found: ..."} also contains the substring "code".
     */
    private static String inferFieldPath(String msg) {
        if (msg == null) {
            return "/";
        }
        if (msg.contains("modelCode")) {
            return "/modelCode";
        }
        if (msg.contains("dataType")) {
            return "/dataType";
        }
        if (msg.contains("code")) {
            return "/code";
        }
        return "/";
    }

    @Override
    public SkillResult undo(String undoToken) {
        // 1) Resolve the originating run from the undo token (Spec §5.5).
        //    SkillRunRepository looks up by `undo_token` UNIQUE column with a
        //    soft-delete guard; absent / consumed tokens come back empty.
        SkillRun run = skillRunRepository.findByUndoToken(undoToken)
                .orElseThrow(() -> new SkillSpiException(
                        SkillErrorCode.UNDO_EXPIRED,
                        "undo token not found or already consumed",
                        null));

        JsonNode after = run.getAfterSnapshot();
        if (after == null || after.isNull()) {
            throw new SkillSpiException(
                    SkillErrorCode.SKILL_INTERNAL_ERROR,
                    "skill run " + run.getPid() + " has no afterSnapshot — cannot undo",
                    null);
        }
        JsonNode modelCodeNode = after.get("modelCode");
        JsonNode storageCodeNode = after.get("storageCode");
        if (modelCodeNode == null || modelCodeNode.isNull()
                || storageCodeNode == null || storageCodeNode.isNull()) {
            throw new SkillSpiException(
                    SkillErrorCode.SKILL_INTERNAL_ERROR,
                    "afterSnapshot is missing modelCode/storageCode for run " + run.getPid(),
                    null);
        }
        String modelCode = modelCodeNode.asText();
        String storageCode = storageCodeNode.asText();

        // 2) Drop the column via removeFromModel(refuseIfDataExists=true).
        //    ColumnHasDataException is the explicit "row data is at risk"
        //    signal — we re-wrap as SKILL_INTERNAL_ERROR (P3 boundary;
        //    re-wrap not swallow) so the controller layer can render the
        //    user-facing "won't auto-undo, you have data" message. Per spec
        //    §5.5 the user must manually confirm a destructive flow.
        RemoveFieldRequest rmReq = RemoveFieldRequest.builder()
                .modelCode(modelCode)
                .storageCode(storageCode)
                .refuseIfDataExists(true)
                .build();
        try {
            metaFieldService.removeFromModel(rmReq);
        } catch (ColumnHasDataException chd) {
            throw new SkillSpiException(
                    SkillErrorCode.SKILL_INTERNAL_ERROR,
                    "field " + storageCode + " has data; refuse to undo to prevent data loss",
                    null,
                    chd);
        } catch (ValidationException ve) {
            throw new SkillSpiException(
                    SkillErrorCode.SKILL_INTERNAL_ERROR,
                    "undo validation failed: " + ve.getMessage(),
                    null,
                    ve);
        } catch (RuntimeException re) {
            log.error("field:add undo failed for storageCode={} modelCode={}",
                    storageCode, modelCode, re);
            throw new SkillSpiException(
                    SkillErrorCode.SKILL_INTERNAL_ERROR,
                    "undo failed: " + re.getMessage(),
                    null,
                    re);
        }

        // 3) Build payload — the controller flips the SkillRun row to UNDONE
        //    via SkillRunRepository.markUndone; this skill stays focused on
        //    the SPI contract (return what was undone).
        ObjectNode payload = objectMapper.createObjectNode();
        JsonNode fieldPidNode = after.get("fieldPid");
        if (fieldPidNode != null && !fieldPidNode.isNull()) {
            payload.put("undoneFieldPid", fieldPidNode.asText());
        }
        payload.put("droppedColumn", storageCode);
        payload.put("modelCode", modelCode);

        return SkillResult.builder()
                .status(SkillResult.Status.SUCCESS)
                .skillName(name())
                .payload(payload)
                .riskLevel(riskLevel())
                .build();
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
