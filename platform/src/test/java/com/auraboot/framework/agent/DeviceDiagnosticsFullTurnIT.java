package com.auraboot.framework.agent;

import com.auraboot.framework.aurabot.dto.ChatRequest;
import com.auraboot.framework.cloudconfig.dto.CloudConfigSaveRequest;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.conversation.ConversationTurnService;
import com.auraboot.framework.conversation.InboundMode;
import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.conversation.TurnOutcome;
import com.auraboot.framework.conversation.TurnRequest;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.plugin.dto.imports.AgentDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.service.impl.PluginResourceImporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R1 — full conversational-turn golden for the device read-first diagnostic agent.
 *
 * <p>Drives a <strong>real</strong> agent turn end-to-end through the
 * {@link ConversationTurnService#runTurn} chokepoint (which creates the agent
 * task/run context) against live DeepSeek: the agent is asked to diagnose device
 * inspections, the real model decides to call {@code list:device_inspection}, the
 * platform <em>executes</em> that tool against the seeded {@code mt_device_inspection}
 * rows, and the model synthesizes a diagnosis citing the failing device. This is the
 * runtime-execution chain (turn → LLM tool call → DslToolProvider execution → DB read
 * → NL synthesis) the tool-selection evals don't exercise.
 *
 * <p>Tools come from the agent definition's explicit {@code tools} list
 * ({@code list:device_inspection}), discovered via {@code AgentChatToolDiscoveryAdapter}.
 * Uses the platform-registered {@code device_inspection} model (tenant =
 * integration-test-tenant) so no model/table creation is needed.
 *
 * <p>Opt-in: gated by {@code DEEPSEEK_API_KEY}, tagged {@code agent-eval-live}.
 * <pre>{@code DEEPSEEK_API_KEY=sk-... ./gradlew :platform:testAgent --tests '*DeviceDiagnosticsFullTurnIT*'}</pre>
 */
@Tag("agent-eval-live")
@DisplayName("R1: device diagnostics full conversational turn — live DeepSeek reads seeded rows")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "agent.anthropic.api-key=",
        "agent.llm.stub-mode=false",
})
class DeviceDiagnosticsFullTurnIT extends BaseIntegrationTest {

    private static final String PROVIDER = "deepseek";
    private static final String MODEL = "device_inspection";
    private static final String AGENT_CODE = "device_fullturn_agent";
    private static final String DELETE_CLOUD =
            "DELETE FROM ab_cloud_config WHERE service_type='llm' AND provider_code='" + PROVIDER
                    + "' AND config_level='tenant' AND tenant_id=?";

    @Autowired private ConversationTurnService conversationTurnService;
    @Autowired private PluginResourceImporter resourceImporter;
    @Autowired private DynamicDataMapper dynamicDataMapper;
    @Autowired private CloudConfigService cloudConfigService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long tenantId;
    private final List<String> seededPids = new ArrayList<>();

    @BeforeEach
    void setUp() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "DEEPSEEK_API_KEY not set — skipping R1 full-turn golden");
        tenantId = getTestTenant().getId();

        jdbcTemplate.update(DELETE_CLOUD, tenantId);
        CloudConfigSaveRequest req = new CloudConfigSaveRequest();
        req.setConfigLevel("tenant");
        req.setServiceType("llm");
        req.setProviderCode(PROVIDER);
        req.setConfig("{\"apiKey\":\"" + apiKey + "\",\"baseUrl\":\"https://api.deepseek.com\","
                + "\"defaultModel\":\"deepseek-chat\",\"apiFormat\":\"chat_completions\","
                + "\"models\":[\"deepseek-chat\"],\"displayName\":\"DeepSeek (R1 full-turn)\"}");
        req.setEnabled(true);
        req.setPriority(0);
        cloudConfigService.saveConfig(req);

        seededPids.clear();
        seedInspection("FT-DEV-001", "fail", "abnormal", "温度过高 overheating, coolant fault suspected");
        seedInspection("FT-DEV-002", "pass", "normal", "all nominal");
        seedInspection("FT-DEV-003", "fail", "abnormal", "振动异常 vibration beyond threshold");

        jdbcTemplate.update("DELETE FROM ab_agent_definition WHERE tenant_id=? AND agent_code=?", tenantId, AGENT_CODE);
        AgentDefinitionDTO dto = AgentDefinitionDTO.builder()
                .agentCode(AGENT_CODE)
                .name("Device Diagnostics Full-Turn Agent")
                .description("Read-first device inspection diagnostics.")
                .agentType("reactive")
                .model("deepseek-chat")
                .systemPrompt("You are a device diagnostics agent. ALWAYS call the list:device_inspection tool "
                        + "to read the inspection records first, then report which devices failed and why, "
                        + "citing each failing device's code and remark. Never fabricate; only use retrieved rows.")
                .tools(List.of("list:" + MODEL))
                .skills(List.of("dsl.query"))
                .guardrails(Map.of("provider", PROVIDER))
                .allowedModels(List.of(MODEL))
                .allowedOperations(List.of("query"))
                .maxTools(8)
                .status("active")
                .visibility("tenant")
                .build();
        resourceImporter.importAgentDefinition(dto, "test-fullturn-pid", "test-import",
                tenantId, ImportRequest.ConflictStrategy.OVERWRITE);
    }

    @AfterEach
    void tearDown() {
        if (tenantId == null) {
            return;
        }
        jdbcTemplate.update(DELETE_CLOUD, tenantId);
        jdbcTemplate.update("DELETE FROM ab_agent_definition WHERE tenant_id=? AND agent_code=?", tenantId, AGENT_CODE);
        for (String pid : seededPids) {
            jdbcTemplate.update("DELETE FROM mt_" + MODEL + " WHERE pid=?", pid);
        }
    }

    @Test
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    @DisplayName("real turn: agent calls list:device_inspection, reads seeded rows, diagnoses the failing device")
    void fullTurnReadsSeededRowsAndDiagnoses() {
        String message = "查一下所有设备巡检记录,哪些设备巡检不合格?逐个说明设备编号和原因。";
        ChatRequest legacy = new ChatRequest();
        legacy.setAgentCode(AGENT_CODE);
        legacy.setMessage(message);
        legacy.setSessionId("ft_sess_" + System.nanoTime());
        legacy.setHistory(List.of());

        TurnRequest req = new TurnRequest(
                tenantId,                       // tenantId
                getTestUser().getId(),          // userId
                getTestTenantMember().getId(),  // humanMemberId
                "web",                          // channel
                AGENT_CODE,                     // agentCode
                null,                           // conversationId
                null,                           // clientMsgId
                message,                        // userMessage
                null,                           // pageContext
                null,                           // options
                InboundMode.NEW_FROM_REQUEST,   // inboundMode
                null,                           // precomputedBucket
                null,                           // inboundMessageId
                null,                           // parentTaskPid
                null,                           // overrides (none — agent's tools list drives discovery)
                legacy);                        // legacyRequest

        CapturingSink sink = new CapturingSink();
        TurnOutcome outcome = conversationTurnService.runTurn(req, sink);

        // The read tool is gated for confirmation by the chokepoint's runtime authorization;
        // approve it (read-only) to drive the full flow: runTurn -> pause -> resumeTurn -> execute.
        if (outcome instanceof TurnOutcome.PendingConfirmation pending) {
            outcome = conversationTurnService.resumeTurn(
                    pending.pendingTurnId(), ConversationTurnService.ConfirmDecision.APPROVED, sink);
        }

        assertTrue(outcome instanceof TurnOutcome.Success,
                "turn must succeed (after approving), got " + outcome + " (sink error=" + sink.error + ")"
                        + "\n  toolInputs=" + sink.toolInputs
                        + "\n  toolResults=" + sink.toolResults);

        assertFalse(sink.toolResults.isEmpty(),
                "agent must have executed the list tool; toolInputs=" + sink.toolInputs);
        boolean readSeededRow = sink.toolResults.stream()
                .anyMatch(r -> r.contains("FT-DEV-001") || r.contains("FT-DEV-003"));
        assertTrue(readSeededRow,
                "the executed tool result must contain a seeded device row.\n  toolInputs=" + sink.toolInputs
                        + "\n  toolResults=" + sink.toolResults);

        String answer = ((TurnOutcome.Success) outcome).finalResponse();
        if (answer == null || answer.isBlank()) {
            answer = sink.finalResponse;
        }
        assertNotNull(answer, "final response must not be null");
        assertFalse(answer.isBlank(), "final response must not be blank");
        assertTrue(answer.contains("FT-DEV-001") || answer.contains("FT-DEV-003"),
                "the diagnosis must cite a failing device from the seeded rows; answer=" + answer);
    }

    private void seedInspection(String deviceCode, String result, String inspectionResult, String remark) {
        String pid = UlidGenerator.generate();
        Map<String, Object> row = new HashMap<>();
        row.put("pid", pid);
        row.put("tenant_id", tenantId);
        row.put("device_code", deviceCode);
        row.put("inspector", "tester");
        row.put("inspection_time", LocalDateTime.now());
        row.put("result", result);
        row.put("inspection_result", inspectionResult);
        row.put("remark", remark);
        row.put("created_at", LocalDateTime.now());
        row.put("updated_at", LocalDateTime.now());
        row.put("created_by", getTestUser().getId());
        row.put("updated_by", getTestUser().getId());
        dynamicDataMapper.insert("mt_" + MODEL, row);
        seededPids.add(pid);
    }

    /** Captures tool execution results and the final answer from the turn. */
    private static final class CapturingSink implements ResponseSink {
        final StringBuilder text = new StringBuilder();
        final List<String> toolInputs = new ArrayList<>();
        final List<String> toolResults = new ArrayList<>();
        String finalResponse;
        String error;

        @Override public void onTextChunk(String t) { if (t != null) text.append(t); }
        @Override public void onToolStart(String toolId, String toolName, Map<String, Object> input) {
            toolInputs.add(toolName + " " + input);
        }
        @Override public void onToolResult(String toolId, Map<String, Object> result, boolean success) {
            toolResults.add(String.valueOf(result));
        }
        @Override public void onConfirmRequired(String toolId, String toolName, String description,
                                                Map<String, Object> input, String pendingTurnId) { }
        @Override public void onError(String message, String traceId) { this.error = message; }
        @Override public void onDone(String fr, String traceId) { this.finalResponse = fr; }
    }
}
