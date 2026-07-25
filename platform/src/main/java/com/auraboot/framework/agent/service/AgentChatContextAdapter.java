package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.runtime.context.AgentContextAssembler;
import com.auraboot.framework.agent.runtime.context.AgentContextBlock;
import com.auraboot.framework.agent.runtime.context.AgentContextBundle;
import com.auraboot.framework.agent.dto.ChatRequest;
import com.auraboot.framework.aurabot.service.RagContextProvider;
import com.auraboot.framework.conversation.TurnContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds the context blocks for the named-agent turn.
 *
 * <p>This adapter used to hand the assembler {@code null} for the retrieved-knowledge
 * argument and an empty list for the knowledge base ids, unconditionally. The
 * assembler has always supported a RAG block — {@code AgentContextAssembler.ragBlock}
 * renders one, and the aurabot path passes real values — so a named agent (a
 * "digital employee") could be configured against a knowledge base and still never
 * see a single retrieved chunk. Not a configuration problem: the value was discarded
 * in code, and {@code ChatRequest.knowledgeBaseIds} was a field nothing read.
 *
 * <p><b>Scope of what this resolves.</b> Only ids the caller explicitly asked for.
 * The aurabot panel additionally retrieves whenever the tenant has any active
 * knowledge base, which suits a general assistant; a named agent has a defined job,
 * so quietly feeding it every knowledge base in the tenant would change what it is.
 * Per-agent binding is the right home for "this employee reads that manual" and needs
 * a column on {@code ab_agent_definition} plus UI — until then, no ids means no
 * retrieval and behaviour is unchanged for every existing caller.
 */
@Component
@Slf4j
class AgentChatContextAdapter {

    private final AgentContextAssembler contextAssembler;

    /**
     * Optional RAG context provider from the shared AI runtime.
     *
     * <p>Field injection with {@code required = false}, matching how
     * {@code AuraBotChatService} takes the same SPI. Not a constructor parameter:
     * {@code @Autowired(required = false)} has no effect on one, so a deployment that
     * does not ship the RAG runtime would fail to start rather than run without it.
     */
    @Autowired(required = false)
    private RagContextProvider ragContextProvider;

    AgentChatContextAdapter(AgentContextAssembler contextAssembler) {
        this.contextAssembler = contextAssembler;
    }

    List<AgentContextBlock> assemble(TurnContext ctx, ChatRequest request) {
        if (request == null || request.getPageContext() == null) {
            return List.of();
        }
        Long tenantId = ctx != null ? ctx.tenantId() : null;
        List<String> kbIds = request.getKnowledgeBaseIds();
        AgentContextBundle bundle = contextAssembler.assemble(
                new AgentContextAssembler.Request(
                        tenantId,
                        ctx != null ? ctx.channel() : null,
                        request.getPageContext(),
                        null,
                        resolveRagContext(tenantId, request, kbIds),
                        kbIds));
        return bundle.blocks();
    }

    /**
     * Retrieve for the knowledge bases the caller named, or nothing.
     *
     * <p>A failing knowledge base must not kill the turn — but the operator has to be
     * able to see that it failed, or the user gets a fluent answer built on nothing and
     * no way to tell it from a real one. Same reasoning and same shape as
     * {@code AuraBotChatService.resolveRagContext}; the broad catch is deliberate and
     * scoped to this one optional enrichment.
     */
    private String resolveRagContext(Long tenantId, ChatRequest request, List<String> kbIds) {
        if (ragContextProvider == null || tenantId == null) return null;
        if (kbIds == null || kbIds.isEmpty()) return null;
        try {
            String query = request.getMessage();
            if (query == null || query.isBlank()) return null;
            String context = ragContextProvider.retrieveContext(tenantId, query, kbIds);
            return context != null && !context.isBlank() ? context : null;
        } catch (Exception e) {
            log.warn("RAG retrieval failed for named-agent turn (tenant {}, {} knowledge base(s)) "
                    + "— answering without them: {}", tenantId, kbIds.size(), e.getMessage());
            return null;
        }
    }
}
