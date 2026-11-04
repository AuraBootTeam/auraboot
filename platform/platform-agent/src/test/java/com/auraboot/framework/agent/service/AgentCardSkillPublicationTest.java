package com.auraboot.framework.agent.service;

import com.auraboot.framework.application.tenant.MetaContext;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentCardSkillPublicationTest {
    @AfterEach void cleanup() { MetaContext.clear(); }
    @Test void cardOnlyAdvertisesAssignedEffectiveSkillsFromTheCurrentTenant() {
        MetaContext.setContext(42L, 7L, "u", "Editor");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AgentSkillService skills = mock(AgentSkillService.class);
        when(jdbc.queryForList(anyString(), eq("delivery"), eq(42L))).thenReturn(List.of(Map.of(
                "agent_code", "delivery", "name", "Delivery", "skills", List.of("review"))));
        when(skills.parseToolCodes(List.of("review"))).thenReturn(List.of("review"));
        when(skills.listSkills(42L, null, null)).thenReturn(List.of(
                Map.of("skill_code", "review", "skill_name", "Published name"),
                Map.of("skill_code", "unbound", "skill_name", "Not assigned")));
        AgentCardService service = new AgentCardService(jdbc, skills);
        ReflectionTestUtils.setField(service, "baseUrl", "https://example.invalid");
        Map<String, Object> card = service.generateAgentCard("delivery");
        assertThat(card.get("skills").toString()).contains("Published name").doesNotContain("Not assigned");
        verify(jdbc, times(1)).queryForList(anyString(), eq("delivery"), eq(42L));
        verify(skills).listSkills(42L, null, null);
    }
}
