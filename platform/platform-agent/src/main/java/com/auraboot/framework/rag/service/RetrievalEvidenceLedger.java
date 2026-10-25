package com.auraboot.framework.rag.service;

import com.auraboot.framework.agent.identity.ExecutionPrincipal;
import com.auraboot.framework.agent.identity.ExecutionPrincipalContext;
import com.auraboot.framework.agent.runtime.context.ContextEnvelopeContext;
import com.auraboot.framework.aurabot.service.RagContextProvider;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.conversation.TurnContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Append-only audit ledger for the exact retrieval evidence supplied to a run.
 */
@Service
@RequiredArgsConstructor
public class RetrievalEvidenceLedger {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public void record(TurnContext ctx, List<RagContextProvider.RetrievalEvidence> evidence) {
        if (ctx == null || evidence == null || evidence.isEmpty()) {
            return;
        }
        ExecutionPrincipal principal = ctx.executionPrincipal();
        insert(
                ctx.tenantId(), ctx.turnId(), ctx.taskPid(), ctx.conversationId(), ctx.traceId(),
                principal, ctx.userId(), ctx.humanMemberId(),
                ctx.contextEnvelope() != null ? ctx.contextEnvelope().envelopeHash() : null,
                evidence);
    }

    public void recordDurable(
            Long tenantId,
            String runPid,
            String taskPid,
            String traceId,
            List<RagContextProvider.RetrievalEvidence> evidence) {
        if (tenantId == null || evidence == null || evidence.isEmpty()) {
            return;
        }
        ExecutionPrincipal principal = ExecutionPrincipalContext.current().orElse(null);
        insert(
                tenantId, runPid, taskPid, null, traceId, principal,
                principal != null ? principal.actorUserId() : null,
                principal != null ? principal.actorMemberId() : null,
                ContextEnvelopeContext.current()
                        .map(context -> context.envelopeHash())
                        .orElse(null),
                evidence);
    }

    private void insert(
            long tenantId,
            String turnPid,
            String taskPid,
            Long conversationId,
            String traceId,
            ExecutionPrincipal principal,
            Long fallbackUserId,
            Long fallbackMemberId,
            String contextEnvelopeHash,
            List<RagContextProvider.RetrievalEvidence> evidence) {
        for (RagContextProvider.RetrievalEvidence item : evidence) {
            jdbc.update(
                    "INSERT INTO ab_agent_retrieval_evidence ("
                            + "pid, tenant_id, turn_pid, task_pid, conversation_id, trace_id, "
                            + "actor_user_id, actor_member_id, initiator_user_id, context_envelope_hash, "
                            + "evidence_id, query_text, kb_pid, index_release_pid, document_pid, "
                            + "document_version_pid, chunk_pid, chunk_index, retrieval_path, "
                            + "vector_score, lexical_score, fused_score, rerank_score, "
                            + "citation_locator, warnings"
                            + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                            + "?, ?, ?, ?, ?, ?::jsonb)",
                    UniqueIdGenerator.generate(),
                    tenantId, turnPid, taskPid, conversationId, traceId,
                    principal != null ? principal.actorUserId() : fallbackUserId,
                    principal != null ? principal.actorMemberId() : fallbackMemberId,
                    principal != null && principal.initiator() != null
                            ? principal.initiator().userId() : fallbackUserId,
                    contextEnvelopeHash,
                    item.evidenceId(), item.query(), item.kbPid(), item.indexReleasePid(),
                    item.documentPid(), item.documentVersionPid(), item.chunkPid(), item.chunkIndex(),
                    item.path(), item.vectorScore(), item.lexicalScore(), item.fusedScore(),
                    item.rerankScore(), item.citationLocator(), json(item.warnings()));
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize retrieval warnings", e);
        }
    }
}
