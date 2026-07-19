package com.auraboot.framework.decision;

import com.auraboot.framework.automation.dto.AutomationCreateRequest;
import com.auraboot.framework.automation.dto.AutomationDTO;
import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.entity.TriggerConfig;
import com.auraboot.framework.automation.service.AutomationService;
import com.auraboot.framework.automation.trigger.AutomationTriggerService;
import com.auraboot.framework.decision.dto.DrtDefinitionCreateRequest;
import com.auraboot.framework.decision.dto.DrtDefinitionDTO;
import com.auraboot.framework.decision.dto.DrtVersionCreateRequest;
import com.auraboot.framework.decision.dto.DrtVersionDTO;
import com.auraboot.framework.plugin.dto.imports.AutomationDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.DecisionDefinitionSeedDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.auraboot.framework.plugin.service.impl.PluginDirectoryLoader;
import com.auraboot.framework.decision.service.DecisionVersionService;
import com.auraboot.framework.decision.service.DrtDefinitionService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4 consumer integration end-to-end over the real stack: an automation whose trigger_config
 * references a DecisionRuntime decision and whose condition reads the injected {@code #decision}
 * variable. Fires the real automation engine (onRecordCreate) and asserts it triggered — which can
 * only happen if {@code withDecision} evaluated the published decision and injected
 * {@code #decision.matched=true} so the SpEL condition passed (docs/2.md M4). Proves the additive
 * decisionRef wiring works through the live trigger pipeline, not just the unit-level injection.
 *
 * <p>@Transactional(NOT_SUPPORTED) so the automation + decision commit (the @Async trigger runs on a
 * separate connection); @AfterEach cleans up.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AutomationDecisionE2EIntegrationTest extends BaseIntegrationTest {

    @Autowired private DrtDefinitionService drtDefinitionService;
    @Autowired private DecisionVersionService drtVersionService;
    @Autowired private AutomationService automationService;
    @Autowired private AutomationTriggerService triggerService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper mapper = new ObjectMapper();
    private Long automationId;
    private String automationPid; // ab_automation_log.automation_id stores the automation PID (VARCHAR)
    private String decisionCode;

    @AfterEach
    public void cleanup() {
        try {
            if (automationPid != null) {
                jdbcTemplate.update("DELETE FROM ab_automation_log WHERE automation_id=?", automationPid);
            }
            if (automationId != null) {
                jdbcTemplate.update("DELETE FROM ab_automation WHERE id=?", automationId);
            }
        } catch (Exception ignore) { }
    }

    private void publishDecision(String code) throws Exception {
        DrtDefinitionCreateRequest def = new DrtDefinitionCreateRequest();
        def.setDecisionCode(code);
        def.setDecisionName("M4 e2e decision");
        def.setScopeType("AUTOMATION");
        def.setOwnerModule("decision");
        drtDefinitionService.create(def);

        JsonNode ast = mapper.readTree("""
            { "type":"compare",
              "left":{"type":"path","scope":"record","path":"data.priority","dataType":"enum"},
              "operator":"EQ","right":{"type":"literal","value":"HIGH","dataType":"enum"} }""");
        DrtVersionCreateRequest ver = new DrtVersionCreateRequest();
        ver.setKind("SIMPLE_CONDITION");
        ver.setRuntimeAdapter("AST_EVALUATOR");
        ver.setContentJson(ast);
        DrtVersionDTO draft = drtVersionService.createDraft(code, ver);
        drtVersionService.validate(draft.getPid());
        drtVersionService.publish(draft.getPid());
    }

    @Test
    void automationTriggers_whenReferencedDecisionMatches_viaInjectedDecisionVar() throws Exception {
        String modelCode = "drt_m4_" + System.nanoTime();
        decisionCode = "drt_m4_dec_" + System.nanoTime();
        publishDecision(decisionCode);

        AutomationCreateRequest req = new AutomationCreateRequest();
        req.setName("M4 E2E " + System.nanoTime());
        req.setModelCode(modelCode);
        req.setTriggerType("on_record_create");
        TriggerConfig cfg = new TriggerConfig();
        cfg.setModelCode(modelCode);
        cfg.setDecisionRef(decisionCode);
        req.setTriggerConfig(cfg);
        // triggers only if the injected decision matched
        req.setTriggerCondition("#decision['matched'] == true");
        req.setActions(List.of(AutomationAction.builder()
                .type("update_record")
                .config(Map.of("modelCode", modelCode, "recordPid", "${recordPid}",
                        "fields", Map.of("note", "auto")))
                .build()));
        req.setEnabled(true);
        AutomationDTO dto = automationService.create(req);
        automationId = dto.getId();
        automationPid = dto.getPid();
        if (dto.getEnabled() == null || !dto.getEnabled()) {
            automationService.enable(dto.getPid());
        }

        // fire the real trigger pipeline with priority=HIGH (so the referenced decision matches)
        triggerService.onRecordCreate(modelCode, "REC-M4-1", Map.of("priority", "HIGH"));

        // @Async — poll ab_automation_log for a trigger record (only written if the condition passed,
        // which requires #decision to have been injected + matched)
        Integer count = 0;
        for (int i = 0; i < 40 && count == 0; i++) {
            count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM ab_automation_log WHERE automation_id=?", Integer.class, automationPid);
            if (count == 0) {
                Thread.sleep(300);
            }
        }
        assertThat(count).as("automation triggered via injected #decision match").isGreaterThanOrEqualTo(1);
    }

    @Test
    void workflowDemoAutomationSeedUsesRuleCenterBindingAndLogsDecisionTrace() throws Exception {
        PluginManifestExtended manifest = new PluginDirectoryLoader().loadFromDirectory(workflowDemoDir());
        DecisionDefinitionSeedDTO decisionSeed = manifest.getDecisionDefinitions().stream()
                .filter(seed -> "leave_request_automation".equals(seed.getDecisionCode()))
                .findFirst()
                .orElseThrow();
        publishSeedDecision(decisionSeed);

        AutomationDefinitionDTO automationSeed = manifest.getAutomations().stream()
                .filter(seed -> "wd_leave_high_value_notify".equals(seed.getAutomationKey()))
                .findFirst()
                .orElseThrow();
        AutomationCreateRequest req = new AutomationCreateRequest();
        req.setName(automationSeed.getName() + " " + System.nanoTime());
        req.setDescription(automationSeed.getDescription());
        req.setModelCode(automationSeed.getModelCode());
        req.setTriggerType(automationSeed.getTriggerType());
        req.setTriggerConfig(automationSeed.getTriggerConfig());
        req.setTriggerCondition(automationSeed.getTriggerCondition());
        req.setActions(automationSeed.getActions());
        req.setFlowConfig(automationSeed.getFlowConfig());
        req.setEnabled(true);
        AutomationDTO dto = automationService.create(req);
        automationId = dto.getId();
        automationPid = dto.getPid();

        triggerService.onRecordCreate("wd_leave_request", "REC-AUTO-LOW", Map.of("wd_req_days", 1));
        Thread.sleep(1200);
        Integer lowCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ab_automation_log WHERE automation_id=?", Integer.class, automationPid);
        assertThat(lowCount).as("non-matching decision should skip automation").isZero();

        triggerService.onRecordCreate("wd_leave_request", "REC-AUTO-HIGH", Map.of(
                "wd_req_days", 5,
                "wd_req_applicant", "u-sales-001"));

        Integer highCount = 0;
        for (int i = 0; i < 40 && highCount == 0; i++) {
            highCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM ab_automation_log WHERE automation_id=?", Integer.class, automationPid);
            if (highCount == 0) {
                Thread.sleep(300);
            }
        }
        assertThat(highCount).as("matching Rule Center decision should trigger automation").isEqualTo(1);

        String payloadJson = jdbcTemplate.queryForObject("""
                SELECT trigger_payload::text
                FROM ab_automation_log
                WHERE automation_id=?
                ORDER BY id DESC
                LIMIT 1
                """, String.class, automationPid);
        JsonNode payload = mapper.readTree(payloadJson);
        assertThat(payload.at("/decision/matched").asBoolean()).isTrue();
        assertThat(payload.at("/decision/outputs/severity").asText()).isEqualTo("warning");
        assertThat(payload.at("/decision/traceId").asText()).isNotBlank();

        Integer completedNodes = 0;
        for (int i = 0; i < 40 && completedNodes == 0; i++) {
            completedNodes = jdbcTemplate.queryForObject("""
                    SELECT count(*)
                    FROM ab_automation_node_execution n
                    JOIN ab_automation_log l ON l.id = n.automation_log_id
                    WHERE l.automation_id = ? AND n.status = 'completed'
                    """, Integer.class, automationPid);
            if (completedNodes == 0) {
                Thread.sleep(300);
            }
        }
        assertThat(completedNodes).as("automation action node should execute and be auditable")
                .isGreaterThanOrEqualTo(1);
    }

    private void publishSeedDecision(DecisionDefinitionSeedDTO seed) {
        DrtDefinitionCreateRequest def = new DrtDefinitionCreateRequest();
        def.setDecisionCode(seed.getDecisionCode());
        def.setDecisionName(seed.getDecisionName());
        def.setDescription(seed.getDescription());
        def.setScopeType(seed.getScopeType());
        def.setScopeRef(seed.getScopeRef());
        def.setOwnerModule(seed.getOwnerModule());
        def.setEnabled(seed.getEnabled());

        DrtDefinitionDTO existing = drtDefinitionService.findByCode(seed.getDecisionCode());
        if (existing == null) {
            drtDefinitionService.create(def);
        } else {
            drtDefinitionService.update(existing.getPid(), def);
        }

        DrtVersionCreateRequest ver = new DrtVersionCreateRequest();
        ver.setKind(seed.getKind());
        ver.setRuntimeAdapter(seed.getRuntimeAdapter());
        ver.setVersionTag(seed.getVersionTag());
        ver.setContentJson(seed.getContentJson());
        ver.setInputSchemaJson(seed.getInputSchemaJson());
        ver.setOutputSchemaJson(seed.getOutputSchemaJson());
        ver.setContextSchemaJson(seed.getContextSchemaJson());
        DrtVersionDTO draft = drtVersionService.createDraft(seed.getDecisionCode(), ver);
        drtVersionService.validate(draft.getPid());
        drtVersionService.publish(draft.getPid(), true);
    }

    private Path workflowDemoDir() {
        Path userDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        List<Path> candidates = List.of(
                userDir.resolve("../plugins/workflow-demo").normalize(),
                userDir.resolve("plugins/workflow-demo"),
                userDir.resolve("../../plugins/workflow-demo").normalize());
        return candidates.stream()
                .filter(path -> Files.exists(path.resolve("plugin.json")))
                .findFirst()
                .orElseThrow();
    }
}
