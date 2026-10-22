package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.dto.FormFillRequest;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.ModelCapabilityProfile;
import com.auraboot.framework.agent.service.AgentSkillService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FormFillSkillServiceTest {
    private final AgentSkillService skills = mock(AgentSkillService.class);
    private final LlmProvider provider = mock(LlmProvider.class);
    private final FormFillSkillService service = new FormFillSkillService(skills, new ObjectMapper());

    private Map<String, Object> definition() {
        when(provider.modelCapabilities("test-model")).thenReturn(ModelCapabilityProfile.conservative(true));
        return new HashMap<>(Map.of("skill_version", "1.0.0", "prompt_template", "Only extract supported facts.",
                "actionability", "read_only", "skill_tools", List.of("platform.fill_form")));
    }

    @Test
    void loadsTenantDefinitionOnceAndPinsItsVersionAndInstructionsForTheTurn() throws Exception {
        var row = definition();
        PGobject tools = new PGobject();
        tools.setType("jsonb");
        tools.setValue("[\"platform.fill_form\"]");
        row.put("skill_tools", tools);
        when(skills.loadSkill(42L, FormFillSkillService.SKILL_CODE)).thenReturn(row);
        var resolved = service.resolve(42L, provider, "test-model");
        row.put("prompt_template", "Changed after request started");
        assertThat(resolved.instructions(new FormFillRequest("f", "customer", "2026-09-14", "UTC", List.of())))
                .contains("Only extract supported facts.", "currentDate=2026-09-14", "timeZone=UTC")
                .doesNotContain("Changed after request started");
        assertThat(resolved.traceMetadata()).containsEntry("code", "form_draft_fill").containsEntry("version", "1.0.0");
        assertThat(resolved.promptHash()).hasSize(64);
        verify(skills, times(1)).loadSkill(42L, "form_draft_fill");
    }

    @Test
    void rejectsUnsupportedModelBeforeReadingSkillOrCallingProvider() {
        when(provider.modelCapabilities("text-only")).thenReturn(ModelCapabilityProfile.conservative(false));
        assertThatThrownBy(() -> service.resolve(42L, provider, "text-only"))
                .hasMessageContaining("tool calling support");
        verifyNoInteractions(skills);
    }

    @Test
    void missingSkillFailsWithoutHardcodedPromptFallback() {
        definition();
        when(skills.loadSkill(42L, FormFillSkillService.SKILL_CODE)).thenReturn(null);
        assertThatThrownBy(() -> service.resolve(42L, provider, "test-model"))
                .hasMessageContaining("not installed or active");
    }

    @Test
    void rejectsExtraToolsMalformedDeclarationsAndWritableSkills() {
        for (Object tools : List.of(List.of("platform.fill_form", "cmd:create_customer"), "invalid-json", "[]")) {
            var row = definition();
            row.put("skill_tools", tools);
            when(skills.loadSkill(42L, FormFillSkillService.SKILL_CODE)).thenReturn(row);
            assertThatThrownBy(() -> service.resolve(42L, provider, "test-model"))
                    .isInstanceOf(IllegalStateException.class);
        }
        var row = definition();
        row.put("actionability", "write");
        when(skills.loadSkill(42L, FormFillSkillService.SKILL_CODE)).thenReturn(row);
        assertThatThrownBy(() -> service.resolve(42L, provider, "test-model")).hasMessageContaining("read_only");
    }

    @Test
    void rejectsUnversionedOrEmptyPromptsAndFingerprintChangesWithContent() {
        var row = definition();
        when(skills.loadSkill(42L, FormFillSkillService.SKILL_CODE)).thenReturn(row);
        var first = service.resolve(42L, provider, "test-model");
        row.put("prompt_template", "A revised playbook");
        assertThat(service.resolve(42L, provider, "test-model").promptHash()).isNotEqualTo(first.promptHash());
        row.put("skill_version", " ");
        assertThatThrownBy(() -> service.resolve(42L, provider, "test-model")).hasMessageContaining("skill_version");
        row.put("skill_version", "1.0.0");
        row.put("prompt_template", "");
        assertThatThrownBy(() -> service.resolve(42L, provider, "test-model")).hasMessageContaining("prompt_template");
    }
}
