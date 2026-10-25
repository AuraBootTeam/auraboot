package com.auraboot.framework.agentchat.reply;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agentchat.spi.AgentMemberDto;
import com.auraboot.framework.agentchat.spi.ChatMessageDto;
import com.auraboot.framework.agentchat.spi.GroupChatMessagePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * DC.3b unit tests for {@link GroupChatTurnContextAssembler}. Establishes
 * the public contract that future group-chat callers (DC.3c
 * AgentReplyTask, plus future webhook / scheduled-agent callers) can rely
 * on. Renamed from {@code AgentReplyContext} per design v5 §10.8.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GroupChatTurnContextAssembler — DC.3b public contract")
class GroupChatTurnContextAssemblerTest {

    @Mock private GroupChatMessagePort messagePort;
    @Mock @SuppressWarnings("rawtypes") private ObjectProvider messagePortProvider;

    private GroupChatTurnContextAssembler assembler;

    private static final Long CONV_ID = 100L;
    private static final Long TENANT_ID = 7L;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(messagePortProvider.getIfAvailable(any())).thenReturn(messagePort);
        assembler = new GroupChatTurnContextAssembler(messagePortProvider);
    }

    private ChatMessageDto humanMsg(String name, String content) {
        return ChatMessageDto.builder()
                .senderType("human")
                .senderName(name)
                .content(content)
                .build();
    }

    private ChatMessageDto agentMsg(String name, String content) {
        return ChatMessageDto.builder()
                .senderType("agent")
                .senderName(name)
                .content(content)
                .build();
    }

    private AgentMemberDto agent(Long id, String code, String name, String title) {
        return AgentMemberDto.builder()
                .agentId(id)
                .agentCode(code)
                .name(name)
                .employeeTitle(title)
                .systemPrompt("base prompt for " + name)
                .build();
    }

    // =========================================================================
    // buildHistory
    // =========================================================================

    @Test
    @DisplayName("buildHistory — agent messages map to assistant role; human messages map to user role")
    void buildHistory_mapsRolesCorrectly() {
        when(messagePort.getRecentMessages(eq(CONV_ID), eq(TENANT_ID), eq(20))).thenReturn(List.of(
                humanMsg("Alice", "Hello team"),
                agentMsg("Alpha", "Hi Alice"),
                humanMsg("Bob", "Question for Beta"),
                agentMsg("Beta", "Answer from Beta")));

        List<LlmChatRequest.Message> history = assembler.buildHistory(CONV_ID, TENANT_ID, 20);

        assertThat(history).hasSize(4);
        assertThat(history.get(0).getRole()).isEqualTo("user");
        assertThat(history.get(1).getRole()).isEqualTo("assistant");
        assertThat(history.get(2).getRole()).isEqualTo("user");
        assertThat(history.get(3).getRole()).isEqualTo("assistant");
    }

    @Test
    @DisplayName("buildHistory — content is prefixed with sender name in [Name]: prefix format")
    void buildHistory_prefixesSenderName() {
        when(messagePort.getRecentMessages(any(), any(), any(Integer.class))).thenReturn(List.of(
                humanMsg("Alice", "Hello"),
                agentMsg("Alpha", "Hi Alice")));

        List<LlmChatRequest.Message> history = assembler.buildHistory(CONV_ID, TENANT_ID, 20);

        assertThat(history.get(0).getContent()).isEqualTo("[Alice]: Hello");
        assertThat(history.get(1).getContent()).isEqualTo("[Alpha]: Hi Alice");
    }

    @Test
    @DisplayName("buildHistory — empty conversation returns empty list (defensive)")
    void buildHistory_emptyConversationReturnsEmptyList() {
        when(messagePort.getRecentMessages(any(), any(), any(Integer.class))).thenReturn(List.of());

        List<LlmChatRequest.Message> history = assembler.buildHistory(CONV_ID, TENANT_ID, 20);

        assertThat(history).isEmpty();
    }

    // =========================================================================
    // buildSystemPrompt
    // =========================================================================

    @Test
    @DisplayName("buildSystemPrompt — includes agent's own system_prompt + soul profile + multi-agent roster")
    void buildSystemPrompt_includesAllSections() {
        AgentMemberDto alpha = AgentMemberDto.builder()
                .agentId(1L).agentCode("alpha").name("Alpha")
                .systemPrompt("You are Alpha, a procurement specialist.")
                .soulProfile("{\"traits\":[\"detail-oriented\"]}")
                .build();
        when(messagePort.getAgentMembers(any(), any())).thenReturn(List.of(
                alpha,
                agent(2L, "beta", "Beta", "Sales Manager"),
                agent(3L, "gamma", "Gamma", "Engineer")));

        String prompt = assembler.buildSystemPrompt(alpha, CONV_ID, TENANT_ID);

        // Agent's own prompt
        assertThat(prompt).contains("You are Alpha, a procurement specialist.");
        // Soul profile
        assertThat(prompt).contains("Your personality profile:")
                          .contains("detail-oriented");
        // Other agents (NOT itself)
        assertThat(prompt).contains("Other AI agents in this conversation:")
                          .contains("- Beta (Sales Manager)")
                          .contains("- Gamma (Engineer)");
        // Self should NOT be listed
        assertThat(prompt).doesNotContain("- Alpha");
        // Handoff hint
        assertThat(prompt).contains("transfer_to_agent");
    }

    @Test
    @DisplayName("buildSystemPrompt — single agent (no others) skips roster + handoff hint")
    void buildSystemPrompt_singleAgentSkipsRoster() {
        AgentMemberDto alpha = agent(1L, "alpha", "Alpha", "Specialist");
        when(messagePort.getAgentMembers(any(), any())).thenReturn(List.of(alpha));

        String prompt = assembler.buildSystemPrompt(alpha, CONV_ID, TENANT_ID);

        assertThat(prompt).contains("base prompt for Alpha");
        assertThat(prompt).doesNotContain("Other AI agents");
        assertThat(prompt).doesNotContain("transfer_to_agent");
    }

    @Test
    @DisplayName("buildSystemPrompt — null/blank system_prompt + null soul profile → only roster section if present")
    void buildSystemPrompt_minimalAgentDef() {
        AgentMemberDto alpha = AgentMemberDto.builder()
                .agentId(1L).agentCode("alpha").name("Alpha")
                .systemPrompt(null).soulProfile(null).build();
        when(messagePort.getAgentMembers(any(), any())).thenReturn(List.of(
                alpha, agent(2L, "beta", "Beta", null)));

        String prompt = assembler.buildSystemPrompt(alpha, CONV_ID, TENANT_ID);

        assertThat(prompt).doesNotContain("Your personality profile:");
        assertThat(prompt).contains("Other AI agents")
                          .contains("- Beta")  // no title in parens when employeeTitle null
                          .doesNotContain("- Beta (");
        assertThat(prompt).contains("transfer_to_agent");
    }

    @Test
    @DisplayName("buildSystemPrompt — agent with no employeeTitle: no parenthetical")
    void buildSystemPrompt_otherAgentWithoutTitle() {
        AgentMemberDto alpha = agent(1L, "alpha", "Alpha", "Specialist");
        AgentMemberDto beta = AgentMemberDto.builder()
                .agentId(2L).agentCode("beta").name("Beta")
                .employeeTitle(null)  // no title
                .build();
        when(messagePort.getAgentMembers(any(), any())).thenReturn(List.of(alpha, beta));

        String prompt = assembler.buildSystemPrompt(alpha, CONV_ID, TENANT_ID);

        assertThat(prompt).contains("- Beta\n").doesNotContain("- Beta (");
    }
}
