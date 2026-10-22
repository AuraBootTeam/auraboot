package com.auraboot.framework.aurabot.skill.builtin;

import com.auraboot.framework.aurabot.skill.AuraBotSkill;
import com.auraboot.framework.aurabot.skill.RiskLevel;
import com.auraboot.framework.aurabot.skill.SkillRequest;
import com.auraboot.framework.aurabot.skill.SkillResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;

/**
 * Built-in {@code echo} skill (Plan §Step 9, dev/test profile only).
 *
 * <p>Returns the input {@code text} field verbatim under {@code payload.echo}.
 * Risk level is configurable via {@code aurabot.skill.echo.risk-level}
 * (default {@code low}) so B7 IT cases that need a {@code MEDIUM} risk path
 * (confirm-required + preview-token) can flip the property without writing a
 * second skill — see {@code @TestPropertySource} in the IT.
 *
 * <p>{@code displayName()} returns an i18n key literal; the FE / discovery
 * payload renders it via the platform i18n stack (see
 * {@code SkillMessageSourceConfig}).
 */
@Slf4j
@Component
@Profile({"dev", "test", "integration-test"})
public class EchoSkill implements AuraBotSkill {

    private static final String SCHEMA_JSON = "{"
            + "\"type\":\"object\","
            + "\"properties\":{\"text\":{\"type\":\"string\"}},"
            + "\"required\":[\"text\"]"
            + "}";

    private final ObjectMapper objectMapper;
    private final String riskLevelCode;

    private JsonNode schema;

    public EchoSkill(ObjectMapper objectMapper,
                     @Value("${aurabot.skill.echo.risk-level:low}") String riskLevelCode) {
        this.objectMapper = objectMapper;
        this.riskLevelCode = riskLevelCode;
    }

    @PostConstruct
    void init() throws Exception {
        this.schema = objectMapper.readTree(SCHEMA_JSON);
    }

    @Override
    public String name() {
        return "echo";
    }

    @Override
    public String displayName() {
        return "aurabot.skill.echo.displayName";
    }

    @Override
    public String category() {
        return "diagnostic";
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.fromCode(riskLevelCode);
    }

    @Override
    public JsonNode paramsSchema() {
        return schema;
    }

    @Override
    public Set<String> requiredPermissions() {
        return Collections.emptySet();
    }

    @Override
    public boolean supportsDryRun() {
        // EchoSkill is the built-in dev/test diagnostic — dry-run returns the
        // would-be echo payload without side effects. This also lets the IT
        // suite exercise the MEDIUM-risk preview-token path without inventing
        // a separate skill bean. Real production skills should override this
        // to reflect their own preview semantics.
        return true;
    }

    @Override
    public SkillResult dryRun(SkillRequest req) {
        // Same shape as execute(), but flagged NEEDS_CONFIRM by the controller
        // wrapper. Returning the input verbatim is sufficient for echo: there
        // are no side effects to preview.
        return execute(req);
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
        String text = req.getParams() != null && req.getParams().hasNonNull("text")
                ? req.getParams().get("text").asText()
                : "";
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("echo", text);
        return SkillResult.builder()
                .status(SkillResult.Status.SUCCESS)
                .skillName(name())
                .payload(payload)
                .build();
    }
}
