package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.ChatRequest;
import com.auraboot.framework.agent.runtime.context.AgentContextAssembler;
import com.auraboot.framework.agent.runtime.context.AgentContextBlock;
import com.auraboot.framework.aurabot.service.RagContextProvider;
import com.auraboot.framework.conversation.TurnContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The named-agent path must be able to see a knowledge base.
 *
 * It could not: this adapter passed {@code null} for the retrieved context and an
 * empty list for the ids, unconditionally, so a digital employee pointed at a
 * knowledge base got nothing and {@code ChatRequest.knowledgeBaseIds} was a field
 * nothing read. The assembler had supported the block all along.
 *
 * The real assembler is used rather than a mock — the thing under test is whether a
 * retrieved-knowledge block actually reaches the prompt, and a mocked assembler
 * would assert only that this class called a method.
 */
class AgentChatContextAdapterRagTest {

    private static final long TENANT_ID = 42L;
    private static final long USER_ID = 7L;

    private final AgentContextAssembler assembler = new AgentContextAssembler(new ObjectMapper());


    /**
     * The SPI is field-injected with required=false (repo idiom, see the adapter), so a
     * test supplies it the same way the container would rather than through a
     * constructor that only tests would use.
     */
    private AgentChatContextAdapter adapterWith(RagContextProvider rag) {
        var adapter = new AgentChatContextAdapter(assembler);
        ReflectionTestUtils.setField(adapter, "ragContextProvider", rag);
        return adapter;
    }

    private ChatRequest request(List<String> kbIds) {
        ChatRequest req = new ChatRequest();
        req.setMessage("how often is ZQ-7731 calibrated?");
        req.setKnowledgeBaseIds(kbIds);
        ChatRequest.PageContext page = new ChatRequest.PageContext();
        page.setKind("list");
        req.setPageContext(page);
        return req;
    }

    private static boolean hasRagBlock(List<AgentContextBlock> blocks) {
        return blocks.stream().anyMatch((b) -> b.body().contains("<retrieved-data>"));
    }

    @Test
    void named_agent_assembly_exposes_retrieval_diagnostics_for_trace_and_observation() {
        RagContextProvider rag = mock(RagContextProvider.class);
        var diagnostics = new RagContextProvider.RetrievalDiagnostics(
                "hybrid",
                1,
                List.of(new RagContextProvider.RetrievalScore(
                        "chunk-1", 0.82, 0.64, 0.77, 0.91)),
                List.of());
        when(rag.retrieveContextWithDiagnostics(anyLong(), anyString(), any()))
                .thenReturn(new RagContextProvider.RetrievedContext(
                        "Bound handbook evidence.", diagnostics));
        var adapter = adapterWith(rag);

        AgentChatContextAdapter.AssemblyResult result =
                adapter.assembleWithDiagnostics(
                        TurnContext.legacyDefault(TENANT_ID, USER_ID, USER_ID),
                        request(null),
                        List.of("kb-bound"));

        assertThat(hasRagBlock(result.blocks())).isTrue();
        assertThat(result.retrievalDiagnostics()).isEqualTo(diagnostics);
    }

    @Test
    void named_ids_reach_the_prompt_as_a_retrieved_knowledge_block() {
        RagContextProvider rag = mock(RagContextProvider.class);
        when(rag.retrieveContext(anyLong(), anyString(), any()))
                .thenReturn("ZQ-7731 is calibrated every 137 days.");
        var adapter = adapterWith(rag);

        List<AgentContextBlock> blocks =
                adapter.assemble(TurnContext.legacyDefault(TENANT_ID, USER_ID, USER_ID),
                        request(List.of("kb-1")), List.of("kb-bound", "kb-1"));

        assertThat(hasRagBlock(blocks))
                .as("a named agent given a knowledge base id must get a retrieved-data block")
                .isTrue();
        assertThat(blocks.stream().anyMatch((b) -> b.body().contains("137 days")))
                .as("the retrieved text itself must be present, not just the wrapper")
                .isTrue();
        verify(rag).retrieveContext(TENANT_ID,
                "how often is ZQ-7731 calibrated?", List.of("kb-1"));
    }

    @Test
    void group_turn_without_page_context_still_uses_the_agents_bound_knowledge_base() {
        RagContextProvider rag = mock(RagContextProvider.class);
        when(rag.retrieveContext(anyLong(), anyString(), any()))
                .thenReturn("Group-channel handbook evidence.");
        var adapter = adapterWith(rag);
        ChatRequest groupRequest = request(null);
        groupRequest.setPageContext(null);

        List<AgentContextBlock> blocks =
                adapter.assemble(
                        TurnContext.legacyDefault(TENANT_ID, USER_ID, USER_ID),
                        groupRequest,
                        List.of("kb-group-bound"));

        verify(rag).retrieveContext(
                TENANT_ID,
                "how often is ZQ-7731 calibrated?",
                List.of("kb-group-bound"));
        assertThat(hasRagBlock(blocks)).isTrue();
        assertThat(blocks.stream().anyMatch(
                block -> block.body().contains("Group-channel handbook evidence.")))
                .isTrue();
    }

    @Test
    void request_selection_cannot_expand_the_agents_knowledge_perimeter() {
        RagContextProvider rag = mock(RagContextProvider.class);
        var adapter = adapterWith(rag);

        List<AgentContextBlock> blocks =
                adapter.assemble(TurnContext.legacyDefault(TENANT_ID, USER_ID, USER_ID),
                        request(List.of("kb-not-bound")), List.of("kb-bound"));

        verify(rag, never()).retrieveContext(anyLong(), anyString(), any());
        assertThat(hasRagBlock(blocks)).isFalse();
    }

    @Test
    void request_selection_can_narrow_the_agents_knowledge_perimeter() {
        assertThat(AgentChatContextAdapter.effectiveKnowledgeBaseIds(
                List.of("kb-b", "kb-not-bound", "kb-b"),
                List.of("kb-a", "kb-b")))
                .containsExactly("kb-b");
    }

    @Test
    void no_request_ids_fall_back_to_the_agents_explicit_binding() {
        RagContextProvider rag = mock(RagContextProvider.class);
        when(rag.retrieveContext(anyLong(), anyString(), any()))
                .thenReturn("Bound handbook evidence.");
        var adapter = adapterWith(rag);

        List<AgentContextBlock> blocks =
                adapter.assemble(TurnContext.legacyDefault(TENANT_ID, USER_ID, USER_ID),
                        request(null), List.of("kb-bound"));

        verify(rag).retrieveContext(TENANT_ID,
                "how often is ZQ-7731 calibrated?", List.of("kb-bound"));
        verify(rag, never()).hasActiveKnowledgeBases(anyLong());
        assertThat(hasRagBlock(blocks)).isTrue();
    }

    @Test
    void empty_request_ids_also_fall_back_to_the_agents_explicit_binding() {
        RagContextProvider rag = mock(RagContextProvider.class);
        when(rag.retrieveContext(anyLong(), anyString(), any()))
                .thenReturn("Bound handbook evidence.");
        var adapter = adapterWith(rag);

        adapter.assemble(TurnContext.legacyDefault(TENANT_ID, USER_ID, USER_ID),
                request(List.of()), List.of("kb-bound"));

        verify(rag).retrieveContext(TENANT_ID,
                "how often is ZQ-7731 calibrated?", List.of("kb-bound"));
    }

    @Test
    void no_request_ids_and_no_binding_means_no_retrieval_at_all() {
        // A named agent has a defined job. Empty configuration must not broaden to
        // every active tenant knowledge base, even though AuraBot may do that.
        RagContextProvider rag = mock(RagContextProvider.class);
        var adapter = adapterWith(rag);

        List<AgentContextBlock> blocks =
                adapter.assemble(TurnContext.legacyDefault(TENANT_ID, USER_ID, USER_ID),
                        request(null), List.of());

        verify(rag, never()).retrieveContext(anyLong(), anyString(), any());
        verify(rag, never()).hasActiveKnowledgeBases(anyLong());
        assertThat(hasRagBlock(blocks)).isFalse();
    }

    @Test
    void a_broken_knowledge_base_does_not_kill_the_turn() {
        RagContextProvider rag = mock(RagContextProvider.class);
        when(rag.retrieveContext(anyLong(), anyString(), any()))
                .thenThrow(new IllegalStateException("pgvector unreachable"));
        var adapter = adapterWith(rag);

        List<AgentContextBlock> blocks =
                adapter.assemble(TurnContext.legacyDefault(TENANT_ID, USER_ID, USER_ID),
                        request(List.of("kb-1")), List.of("kb-1"));

        // Answering without the knowledge base beats not answering; the operator sees a warn.
        assertThat(hasRagBlock(blocks)).isFalse();
    }

    @Test
    void a_deployment_without_the_rag_spi_still_assembles() {
        var adapter = new AgentChatContextAdapter(assembler);

        List<AgentContextBlock> blocks =
                adapter.assemble(TurnContext.legacyDefault(TENANT_ID, USER_ID, USER_ID),
                        request(List.of("kb-1")), List.of("kb-1"));

        assertThat(hasRagBlock(blocks)).isFalse();
    }

    @Test
    void a_blank_message_does_not_trigger_retrieval() {
        // Nothing to retrieve against; calling out would spend an embedding round trip
        // to search for the empty string.
        RagContextProvider rag = mock(RagContextProvider.class);
        var adapter = adapterWith(rag);
        ChatRequest req = request(List.of("kb-1"));
        req.setMessage("   ");

        adapter.assemble(TurnContext.legacyDefault(TENANT_ID, USER_ID, USER_ID),
                req, List.of("kb-1"));

        verify(rag, never()).retrieveContext(anyLong(), anyString(), any());
    }
}
