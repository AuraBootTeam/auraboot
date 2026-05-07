package com.auraboot.framework.aurabot.skill.builtin;

import com.auraboot.framework.aurabot.skill.AuraBotSkill;
import com.auraboot.framework.aurabot.skill.RiskLevel;
import com.auraboot.framework.aurabot.skill.SkillRequest;
import com.auraboot.framework.aurabot.skill.SkillResult;
import com.auraboot.framework.aurabot.skill.error.SkillErrorCode;
import com.auraboot.framework.aurabot.skill.error.SkillSpiException;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Built-in {@code model:query} skill (Plan §Step 9, production-eligible).
 *
 * <p>Two-mode lookup against {@link MetaModelService}:
 * <ul>
 *     <li>Exact: when {@code modelCode} is supplied — single-row hydrate via
 *     {@link MetaModelService#findByCode(String)}.</li>
 *     <li>Search: when {@code keyword} is supplied — first page (10 rows) via
 *     {@link MetaModelService#searchModels(Integer, Integer, String, String, String, String, String, String, String, String, Boolean)}.</li>
 * </ul>
 *
 * <p>Schema enforces {@code oneOf [modelCode, keyword]} so the SPI validator
 * (Step 4) rejects ambiguous calls before they reach {@link #execute}.
 */
@Slf4j
@Component
public class ModelQuerySkill implements AuraBotSkill {

    private static final String SCHEMA_JSON = "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            + "\"modelCode\":{\"type\":\"string\"},"
            + "\"keyword\":{\"type\":\"string\"}"
            + "},"
            + "\"oneOf\":["
            + "{\"required\":[\"modelCode\"]},"
            + "{\"required\":[\"keyword\"]}"
            + "]"
            + "}";

    /** Hard cap so a misuse keyword like "" doesn't pull the whole catalog. */
    private static final int SEARCH_PAGE_SIZE = 10;

    private final MetaModelService metaModelService;
    private final ObjectMapper objectMapper;

    private JsonNode schema;

    public ModelQuerySkill(MetaModelService metaModelService, ObjectMapper objectMapper) {
        this.metaModelService = metaModelService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() throws Exception {
        this.schema = objectMapper.readTree(SCHEMA_JSON);
    }

    @Override
    public String name() {
        return "model:query";
    }

    @Override
    public String displayName() {
        return "aurabot.skill.model.query.displayName";
    }

    @Override
    public String category() {
        return "model";
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public JsonNode paramsSchema() {
        return schema;
    }

    @Override
    public Set<String> requiredPermissions() {
        return Set.of("MODEL.READ");
    }

    @Override
    public boolean supportsDryRun() {
        return false;
    }

    @Override
    public boolean supportsUndo() {
        return false;
    }

    @Override
    public boolean supportsStreaming() {
        return false;
    }

    @Override
    public SkillResult execute(SkillRequest req) {
        JsonNode params = req.getParams();
        if (params == null) {
            throw new SkillSpiException(SkillErrorCode.PARAMS_INVALID,
                    "params is required", "/");
        }

        ObjectNode payload = objectMapper.createObjectNode();

        if (params.hasNonNull("modelCode")) {
            String code = params.get("modelCode").asText();
            try {
                MetaModelDTO dto = metaModelService.findByCode(code);
                payload.set("model", dto == null ? null : objectMapper.valueToTree(dto));
                payload.put("matched", dto != null);
            } catch (RuntimeException e) {
                log.warn("model:query findByCode failed code={}: {}", code, e.getMessage());
                throw new SkillSpiException(SkillErrorCode.SKILL_INTERNAL_ERROR,
                        "model lookup failed: " + e.getMessage(), null, e);
            }
        } else if (params.hasNonNull("keyword")) {
            String keyword = params.get("keyword").asText();
            try {
                PageResult<MetaModelDTO> page = metaModelService.searchModels(
                        1, SEARCH_PAGE_SIZE, keyword,
                        null, null, null, null, null, null, null, Boolean.TRUE);
                List<MetaModelDTO> records = page == null ? Collections.emptyList() : page.getRecords();
                payload.set("results", objectMapper.valueToTree(records));
                payload.put("total", page == null || page.getTotal() == null ? 0L : page.getTotal());
            } catch (RuntimeException e) {
                log.warn("model:query searchModels failed keyword={}: {}", keyword, e.getMessage());
                throw new SkillSpiException(SkillErrorCode.SKILL_INTERNAL_ERROR,
                        "model search failed: " + e.getMessage(), null, e);
            }
        } else {
            // Schema validator should have caught this — defensive only.
            throw new SkillSpiException(SkillErrorCode.PARAMS_INVALID,
                    "either modelCode or keyword is required",
                    "/");
        }

        return SkillResult.builder()
                .status(SkillResult.Status.SUCCESS)
                .skillName(name())
                .payload(payload)
                .build();
    }
}
