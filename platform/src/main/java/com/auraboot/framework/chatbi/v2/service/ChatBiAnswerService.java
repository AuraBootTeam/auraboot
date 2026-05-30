package com.auraboot.framework.chatbi.v2.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.chatbi.v2.compiler.TokenCompileException;
import com.auraboot.framework.chatbi.v2.compiler.TokenCompiler;
import com.auraboot.framework.chatbi.v2.dto.ChatBiAnswerResponse;
import com.auraboot.framework.chatbi.v2.dto.SearchToken;
import com.auraboot.framework.chatbi.v2.entity.ChatBiAnswer;
import com.auraboot.framework.chatbi.v2.mapper.ChatBiAnswerMapper;
import com.auraboot.framework.chatbi.v2.provider.AnswerCorrelation;
import com.auraboot.framework.chatbi.v2.provider.ConversationContext;
import com.auraboot.framework.chatbi.v2.provider.IntentResult;
import com.auraboot.framework.chatbi.v2.provider.LlmProviderRouter;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.semantic.compiler.SemanticQueryRequest;
import com.auraboot.framework.semantic.compiler.UserContext;
import com.auraboot.framework.semantic.dto.SemanticMetaResponse;
import com.auraboot.framework.semantic.service.SemanticCatalogService;
import com.auraboot.framework.semantic.dto.SemanticQueryResponse;
import com.auraboot.framework.semantic.service.SemanticQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The single-question orchestration entry point for ChatBI v2. PRD 17 §6.
 *
 * <p>Flow:
 * <pre>
 *   1. Allocate answerPid (ULID).
 *   2. Stamp AnswerCorrelation so the LLM provider's audit row links back.
 *   3. Load conversation context (sliding window of last 5 pairs).
 *   4. LlmProviderRouter.translate (3-level fallback).
 *   5. DisambiguationService.evaluate — short-circuit to UI prompt on
 *      low confidence / close-margin top-2.
 *   6. TokenCompiler.compile → SemanticQueryRequest.
 *   7. SemanticQueryService.executeQuery → rows + SQL fingerprint.
 *   8. Persist ChatBiAnswer + append (user, assistant) turn to conversation.
 *   9. Return ChatBiAnswerResponse.
 * </pre>
 *
 * <p>Transactional boundary: the row insert + context append run inside the
 * outer {@link Transactional}. LLM audit rows are written by the providers in
 * REQUIRES_NEW so a failure here does not lose cost data.
 *
 * <p>Failure modes never throw to the controller — all are encoded as
 * {@code status=FAILED} responses with a user-safe {@code errorMessage}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatBiAnswerService {

    private final LlmProviderRouter router;
    private final SemanticCatalogService catalogService;
    private final SemanticQueryService queryService;
    private final TokenCompiler tokenCompiler;
    private final ConversationService conversationService;
    private final DisambiguationService disambiguationService;
    private final ChatBiAnswerMapper answerMapper;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    /**
     * Answer one NL question. {@code conversationPid} may be null for direct
     * one-off asks (PRD §6.1).
     */
    @Transactional
    public ChatBiAnswerResponse ask(String nlQuery,
                                    String conversationPid,
                                    String semanticModelPid) {
        if (nlQuery == null || nlQuery.isBlank()) {
            return failed(null, conversationPid, nlQuery, "Question is empty");
        }
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        String answerPid = UlidGenerator.generate();

        AnswerCorrelation.set(answerPid, conversationPid);
        try {
            return askInternal(answerPid, nlQuery, conversationPid,
                               semanticModelPid, tenantId, userId);
        } finally {
            AnswerCorrelation.clear();
        }
    }

    private ChatBiAnswerResponse askInternal(String answerPid,
                                             String nlQuery,
                                             String conversationPid,
                                             String semanticModelPid,
                                             Long tenantId,
                                             Long userId) {
        // 3. Load context.
        ConversationContext ctx = conversationPid != null
                ? conversationService.loadContext(tenantId, conversationPid)
                : ConversationContext.empty();

        // 4. Translate via router.
        SemanticMetaResponse catalog = catalogService.listCatalog(tenantId);
        LlmProviderRouter.RouteOutcome outcome = router.translate(nlQuery, catalog, ctx);
        IntentResult intent = outcome.result();

        // 5. Disambiguation.
        DisambiguationService.Verdict verdict;
        try {
            verdict = disambiguationService.evaluate(intent, tenantId, answerPid, nlQuery);
        } catch (Exception e) {
            log.warn("Disambiguation eval failed: {}", e.getMessage(), e);
            return failed(answerPid, conversationPid, nlQuery,
                    "Disambiguation evaluation failed");
        }

        if (verdict instanceof DisambiguationService.Verdict.PromptUser pu) {
            ChatBiAnswerResponse resp = ChatBiAnswerResponse.builder()
                    .answerPid(answerPid)
                    .conversationPid(conversationPid)
                    .status(ChatBiAnswerResponse.STATUS_DISAMBIGUATION)
                    .nlQuery(nlQuery)
                    .tokens(intent.tokens())
                    .confidence(intent.confidence())
                    .suggestedFollowUps(intent.suggestedFollowUps())
                    .disambiguation(pu.disambiguation())
                    .attempts(outcome.attempts())
                    .llmUsed(outcome.winner())
                    .build();
            persistAnswer(answerPid, tenantId, userId, conversationPid,
                          semanticModelPid, nlQuery, intent,
                          null, null,
                          ChatBiAnswerResponse.STATUS_DISAMBIGUATION,
                          outcome.winner());
            return resp;
        }

        // 6. Compile.
        SemanticQueryRequest req;
        try {
            req = tokenCompiler.compile(intent.tokens(), semanticModelCode(catalog, semanticModelPid));
        } catch (TokenCompileException e) {
            return failed(answerPid, conversationPid, nlQuery,
                    "Could not compile your question: " + e.getMessage());
        }

        // 7. Execute.
        SemanticQueryResponse exec;
        try {
            exec = queryService.executeQuery(req,
                    new UserContext(userId, tenantId, Collections.emptyMap()));
        } catch (Exception e) {
            log.warn("Semantic query execution failed for answer {}: {}", answerPid, e.getMessage(), e);
            return failed(answerPid, conversationPid, nlQuery,
                    "Query execution failed");
        }

        // 8. Persist.
        persistAnswer(answerPid, tenantId, userId, conversationPid,
                      semanticModelPid, nlQuery, intent,
                      req, exec,
                      ChatBiAnswerResponse.STATUS_SUCCESS, outcome.winner());

        if (conversationPid != null) {
            try {
                conversationService.append(tenantId, conversationPid, "user", nlQuery);
                conversationService.append(tenantId, conversationPid, "assistant",
                        summariseRows(exec));
            } catch (Exception e) {
                log.warn("Failed to append answer to conversation {}: {}",
                        conversationPid, e.getMessage());
            }
        }

        return ChatBiAnswerResponse.builder()
                .answerPid(answerPid)
                .conversationPid(conversationPid)
                .status(ChatBiAnswerResponse.STATUS_SUCCESS)
                .nlQuery(nlQuery)
                .tokens(intent.tokens())
                .confidence(intent.confidence())
                .suggestedFollowUps(intent.suggestedFollowUps())
                .rows(exec.getRows())
                .rowCount(exec.getRowcount())
                .durationMs((int) Math.min(Integer.MAX_VALUE, exec.getDurationMs()))
                .vizType(suggestVizType(intent.tokens(), exec.getRowcount()))
                .sql(exec.getSql())
                .attempts(outcome.attempts())
                .llmUsed(outcome.winner())
                .build();
    }

    private void persistAnswer(String answerPid,
                               Long tenantId,
                               Long userId,
                               String conversationPid,
                               String semanticModelPid,
                               String nlQuery,
                               IntentResult intent,
                               SemanticQueryRequest req,
                               SemanticQueryResponse exec,
                               String status,
                               String llmUsed) {
        try {
            ChatBiAnswer row = new ChatBiAnswer();
            row.setPid(answerPid);
            row.setTenantId(tenantId);
            row.setUserId(userId);
            row.setConversationPid(conversationPid);
            row.setSemanticModelPid(semanticModelPid);
            row.setNlQuery(nlQuery);
            row.setTokensJson(serialise(intent.tokens()));
            if (req != null) row.setSemanticRequestJson(serialise(req));
            if (exec != null) {
                row.setSqlHash(exec.getSqlFingerprint());
                row.setRowCount(exec.getRowcount());
                row.setDurationMs((int) Math.min(Integer.MAX_VALUE, exec.getDurationMs()));
            }
            row.setVizType(suggestVizType(intent.tokens(),
                    exec != null ? exec.getRowcount() : 0));
            row.setLlmUsed(llmUsed);
            row.setLlmCostCents(BigDecimal.valueOf(intent.usage().costCents()));
            row.setStatus(status);
            answerMapper.insert(row);
        } catch (Exception e) {
            log.warn("Failed to persist ChatBiAnswer {}: {}", answerPid, e.getMessage());
        }
    }

    private ChatBiAnswerResponse failed(String answerPid,
                                        String conversationPid,
                                        String nlQuery,
                                        String message) {
        return ChatBiAnswerResponse.builder()
                .answerPid(answerPid)
                .conversationPid(conversationPid)
                .status(ChatBiAnswerResponse.STATUS_FAILED)
                .errorMessage(message)
                .nlQuery(nlQuery)
                .build();
    }

    private String serialise(Object o) {
        try {
            return jsonMapper.writeValueAsString(o);
        } catch (Exception e) {
            log.warn("JSON serialise failed: {}", e.getMessage());
            return "{}";
        }
    }

    private String summariseRows(SemanticQueryResponse r) {
        int n = r != null ? r.getRowcount() : 0;
        if (n == 0) return "(0 rows)";
        return "(" + n + " rows)";
    }

    /**
     * Resolve {@code semanticModelCode} from {@code semanticModelPid} via the
     * catalog. Falls back to null when no mapping found — the compiler then
     * emits bare codes and the downstream catalog lookup fails loudly.
     */
    private String semanticModelCode(SemanticMetaResponse catalog, String pid) {
        if (pid == null || catalog == null || catalog.getModels() == null) return null;
        return catalog.getModels().stream()
                .filter(m -> pid.equals(m.getPid()))
                .map(SemanticMetaResponse.ModelMeta::getCode)
                .findFirst()
                .orElse(null);
    }

    /**
     * Pick a viz type based on the token shape + row count. PRD §11.2 simple
     * rules; UI can override via {@code vizConfigJson} later.
     */
    static String suggestVizType(List<SearchToken> tokens, int rowCount) {
        if (tokens == null || tokens.isEmpty()) return "table";
        boolean hasTimeDim = tokens.stream().anyMatch(t ->
                t.dateBucket() != null && !t.dateBucket().isBlank());
        long dimCount = tokens.stream().filter(t ->
                t.type() == com.auraboot.framework.chatbi.v2.dto.TokenType.DIMENSION).count();
        long metricCount = tokens.stream().filter(t ->
                t.type() == com.auraboot.framework.chatbi.v2.dto.TokenType.METRIC).count();
        if (rowCount == 1 && dimCount == 0 && metricCount >= 1) return "kpi";
        if (hasTimeDim) return "line";
        if (dimCount == 1 && metricCount >= 1) return "bar";
        if (dimCount >= 2 && metricCount >= 1) return "pivot";
        return "table";
    }
}
