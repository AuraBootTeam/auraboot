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

import java.util.LinkedHashSet;
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
 * <p><b>Scope of what this resolves.</b> Non-empty ids explicitly supplied by the
 * caller win. Otherwise the adapter uses the named agent's explicit binding. The
 * aurabot panel additionally retrieves whenever the tenant has any active knowledge
 * base, which suits a general assistant; a named agent has a defined job, so quietly
 * feeding it every knowledge base in the tenant would change what it is. If neither
 * source names an id, this adapter performs no retrieval.
 */
@Component
@Slf4j
class AgentChatContextAdapter {

    record AssemblyResult(List<AgentContextBlock> blocks,
                          RagContextProvider.RetrievalDiagnostics retrievalDiagnostics) {
        AssemblyResult {
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
        }
    }

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

    List<AgentContextBlock> assemble(TurnContext ctx,
                                     ChatRequest request,
                                     List<String> boundKnowledgeBaseIds) {
        return assembleWithDiagnostics(ctx, request, boundKnowledgeBaseIds).blocks();
    }

    AssemblyResult assembleWithDiagnostics(TurnContext ctx,
                                           ChatRequest request,
                                           List<String> boundKnowledgeBaseIds) {
        if (request == null || request.getPageContext() == null) {
            return new AssemblyResult(List.of(), null);
        }
        Long tenantId = ctx != null ? ctx.tenantId() : null;
        List<String> kbIds = effectiveKnowledgeBaseIds(
                request.getKnowledgeBaseIds(), boundKnowledgeBaseIds);
        RagContextProvider.RetrievedContext retrieved =
                resolveRagContext(tenantId, request, kbIds);
        AgentContextBundle bundle = contextAssembler.assemble(
                new AgentContextAssembler.Request(
                        tenantId,
                        ctx != null ? ctx.channel() : null,
                        request.getPageContext(),
                        null,
                        retrieved.context(),
                        kbIds));
        return new AssemblyResult(bundle.blocks(), retrieved.diagnostics());
    }

    /**
     * Request-level selection is an intentional one-turn override. An absent or empty
     * request selection falls back to the agent binding; it does not merge the two
     * scopes. Blank ids and duplicates are removed before calling the RAG SPI.
     */
    static List<String> effectiveKnowledgeBaseIds(List<String> requested,
                                                  List<String> bound) {
        List<String> requestedIds = normalizeIds(requested);
        return requestedIds.isEmpty() ? normalizeIds(bound) : requestedIds;
    }

    private static List<String> normalizeIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                normalized.add(id.trim());
            }
        }
        return List.copyOf(normalized);
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
    private RagContextProvider.RetrievedContext resolveRagContext(
            Long tenantId, ChatRequest request, List<String> kbIds) {
        if (ragContextProvider == null || tenantId == null) {
            return new RagContextProvider.RetrievedContext("", null);
        }
        if (kbIds == null || kbIds.isEmpty()) {
            return new RagContextProvider.RetrievedContext("", null);
        }
        try {
            String query = request.getMessage();
            if (query == null || query.isBlank()) {
                return new RagContextProvider.RetrievedContext("", null);
            }
            RagContextProvider.RetrievedContext retrieved =
                    ragContextProvider.retrieveContextWithDiagnostics(tenantId, query, kbIds);
            // Mockito/older optional providers may return null for the new default seam.
            // Preserve their context behavior while marking diagnostics unavailable.
            if (retrieved == null) {
                return new RagContextProvider.RetrievedContext(
                        ragContextProvider.retrieveContext(tenantId, query, kbIds), null);
            }
            return retrieved;
        } catch (Exception e) {
            log.warn("RAG retrieval failed for named-agent turn (tenant {}, {} knowledge base(s)) "
                    + "— answering without them: {}", tenantId, kbIds.size(), e.getMessage());
            return new RagContextProvider.RetrievedContext(
                    "",
                    new RagContextProvider.RetrievalDiagnostics(
                            "error",
                            0,
                            List.of(),
                            List.of(java.util.Objects.toString(
                                    e.getMessage(), e.getClass().getSimpleName()))));
        }
    }
}
