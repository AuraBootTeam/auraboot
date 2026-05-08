package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.port.ToolDiscoveryPort;
import com.auraboot.framework.aurabot.skill.RiskLevel;
import com.auraboot.framework.aurabot.skill.SkillRequest;
import com.auraboot.framework.aurabot.skill.SkillResult;
import com.auraboot.framework.aurabot.skill.provider.SkillToolExecutor;
import com.auraboot.framework.aurabot.skill.provider.SkillToolExecutor.DispatchOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit-level coverage for the {@code aurabot:} branch added to
 * {@link ChatToolExecutor#execute} (Plan §C-5 Task 4).
 *
 * <p>Two scenarios:
 * <ol>
 *   <li>LOW risk skill → {@code DispatchOutcome.executed} → flat
 *       {@code {success:true, data:<payload>}} envelope.</li>
 *   <li>HIGH risk skill → {@code DispatchOutcome.pending} → marker
 *       {@code _aurabot_skill_pending} + previewToken + riskLevel.</li>
 * </ol>
 *
 * <p>The legacy ToolDiscoveryPort path is not exercised here (covered by
 * existing chat tool tests); these cases assert the SkillToolExecutor branch
 * intercepts before the legacy fallback.
 */
@ExtendWith(MockitoExtension.class)
class ChatToolExecutorAuraBotBranchTest {

    @Mock
    private ToolDiscoveryPort toolDiscoveryPort;

    @Mock
    private ChatToolResolver chatToolResolver;

    @Mock
    private SkillToolExecutor skillToolExecutor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void aurabotPrefix_executed_returnsSuccessData() {
        // Given a LOW-risk skill that runs inline
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("count", 3);
        SkillResult result = SkillResult.builder()
                .status(SkillResult.Status.SUCCESS)
                .skillName("crm.lead.create")
                .payload(payload)
                .build();
        DispatchOutcome outcome = DispatchOutcome.executed(result, RiskLevel.LOW);

        when(skillToolExecutor.dispatch(anyString(), any(SkillRequest.class))).thenReturn(outcome);

        // ChatToolExecutor must hand the manual ObjectMapper field — InjectMocks
        // can't bind it because it's a non-mock dependency. Build an instance
        // directly to keep the mock wiring honest.
        ChatToolExecutor exec = new ChatToolExecutor(toolDiscoveryPort, chatToolResolver,
                skillToolExecutor, objectMapper);

        Map<String, Object> input = Map.of("name", "Acme");
        Map<String, Object> envelope = exec.execute("aurabot:crm.lead.create", input, "crm");

        assertThat(envelope).containsEntry("success", true);
        assertThat(envelope).containsKey("data");
        assertThat(envelope.get("data")).isEqualTo(payload);
        assertThat(envelope).doesNotContainKey("_aurabot_skill_pending");
    }

    @Test
    void aurabotPrefix_highRisk_returnsPendingMarker() {
        // Given a HIGH-risk skill that yields a preview-pending outcome
        Map<String, Object> previewBody = Map.of("delta", "+3 leads");
        SkillResult preview = SkillResult.builder()
                .status(SkillResult.Status.NEEDS_CONFIRM)
                .skillName("crm.lead.bulk_delete")
                .preview(previewBody)
                .build();
        DispatchOutcome outcome = DispatchOutcome.pending(preview, "tok-abc-123", RiskLevel.HIGH);

        when(skillToolExecutor.dispatch(anyString(), any(SkillRequest.class))).thenReturn(outcome);

        ChatToolExecutor exec = new ChatToolExecutor(toolDiscoveryPort, chatToolResolver,
                skillToolExecutor, objectMapper);

        Map<String, Object> input = Map.of("ids", new int[]{1, 2, 3});
        Map<String, Object> envelope = exec.execute("aurabot:crm.lead.bulk_delete", input, "crm");

        assertThat(envelope).containsEntry("_aurabot_skill_pending", true);
        assertThat(envelope).containsEntry("skillName", "crm.lead.bulk_delete");
        assertThat(envelope).containsEntry("previewToken", "tok-abc-123");
        assertThat(envelope).containsEntry("riskLevel", RiskLevel.HIGH.code());
        assertThat(envelope).containsEntry("preview", previewBody);
        assertThat(envelope).doesNotContainKey("success");
    }
}
