package com.auraboot.framework.chatbi.v2.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.chatbi.v2.compiler.TokenCompileException;
import com.auraboot.framework.chatbi.v2.compiler.TokenCompiler;
import com.auraboot.framework.chatbi.v2.dto.ChatBiAnswerResponse;
import com.auraboot.framework.chatbi.v2.dto.SearchToken;
import com.auraboot.framework.chatbi.v2.entity.ChatBiAnswer;
import com.auraboot.framework.chatbi.v2.lexer.TokenLexer;
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
import java.util.ArrayList;
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
 *   5. TokenLexer validates provider tokens against catalog or performs the
 *      deterministic catalog fallback when no provider is configured.
 *   6. DisambiguationService.evaluate — short-circuit to UI prompt on
 *      low confidence / close-margin top-2.
 *   7. TokenCompiler.compile → SemanticQueryRequest.
 *   8. SemanticQueryService.executeQuery → rows + SQL fingerprint.
 *   9. Persist ChatBiAnswer + append (user, assistant) turn to conversation.
 *   10. Return ChatBiAnswerResponse.
 * </pre>
 *
 * <p>Transactional boundary: the answer-row insert + conversation append are
 * best-effort and delegated to {@link ChatBiAnswerPersistence} in
 * {@code REQUIRES_NEW} (TX-003), so a persistence failure runs in its own
 * transaction and cannot abort — nor be rolled back with — the answer. LLM audit
 * rows are likewise written by the providers in REQUIRES_NEW so a failure there
 * does not lose cost data.
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
    private final TokenLexer tokenLexer;
    private final TokenCompiler tokenCompiler;
    private final ConversationService conversationService;
    private final DisambiguationService disambiguationService;
    private final ChatBiAnswerPersistence persistence;
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
        SemanticMetaResponse scopedCatalog = scopeCatalog(catalog, semanticModelPid);
        LlmProviderRouter.RouteOutcome outcome = router.translate(
                nlQuery, scopedCatalog, ctx);
        IntentResult routedIntent = outcome.result() == null
                ? IntentResult.empty() : outcome.result();

        // 5. Validate provider tokens or apply the catalog-only fallback.
        List<SearchToken> lexedTokens =
                tokenLexer.lex(nlQuery, scopedCatalog, routedIntent);
        IntentResult intent = withLexedTokens(routedIntent, lexedTokens);
        boolean explicitClarification = routedIntent.needsClarification()
                && routedIntent.disambiguation() != null;
        if (lexedTokens.isEmpty() && !explicitClarification) {
            return failed(answerPid, conversationPid, nlQuery,
                    "No semantic catalog metric matched. Use an exact metric or dimension name.");
        }
        ResolvedSemanticModel resolvedModel =
                resolveSemanticModel(catalog, semanticModelPid, lexedTokens);

        // 6. Disambiguation.
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
                          resolvedModel != null ? resolvedModel.pid() : null,
                          nlQuery, intent,
                          null, null,
                          ChatBiAnswerResponse.STATUS_DISAMBIGUATION,
                          outcome.winner());
            return resp;
        }

        // 7. Compile.
        if (resolvedModel == null || resolvedModel.code() == null) {
            return failed(answerPid, conversationPid, nlQuery,
                    "No single semantic model matched the question");
        }
        SemanticQueryRequest req;
        try {
            req = tokenCompiler.compile(intent.tokens(), resolvedModel.code());
        } catch (TokenCompileException e) {
            return failed(answerPid, conversationPid, nlQuery,
                    "Could not compile your question: " + e.getMessage());
        }

        // 8. Execute.
        SemanticQueryResponse exec;
        try {
            exec = queryService.executeQuery(req,
                    new UserContext(userId, tenantId, Collections.emptyMap()));
        } catch (Exception e) {
            log.warn("Semantic query execution failed for answer {}: {}", answerPid, e.getMessage(), e);
            return failed(answerPid, conversationPid, nlQuery,
                    "Query execution failed");
        }
        String executionFailure = executionFailure(exec);
        if (executionFailure != null) {
            log.warn("Semantic query returned execution failure for answer {}: {}",
                    answerPid, executionFailure);
            return failed(answerPid, conversationPid, nlQuery,
                    "Query execution failed");
        }

        // 9. Persist.
        persistAnswer(answerPid, tenantId, userId, conversationPid,
                      resolvedModel.pid(), nlQuery, intent,
                      req, exec,
                      ChatBiAnswerResponse.STATUS_SUCCESS, outcome.winner());

        if (conversationPid != null) {
            // Best-effort: isolated in REQUIRES_NEW (TX-003) so an append failure
            // cannot abort this answer transaction and turn a success into a 500.
            persistence.appendTurn(tenantId, conversationPid, nlQuery, summariseRows(exec));
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
        if (semanticModelPid == null || semanticModelPid.isBlank()) {
            log.debug("Skipping ChatBiAnswer persistence without a resolved semantic model: {}",
                    answerPid);
            return;
        }
        ChatBiAnswer row = new ChatBiAnswer();
        row.setPid(answerPid);
        row.setTenantId(tenantId);
        row.setUserId(userId);
        row.setConversationPid(conversationPid);
        row.setSemanticModelPid(semanticModelPid);
        row.setNlQuery(nlQuery);
        row.setTokensJson(serialise(intent.tokens() == null ? List.of() : intent.tokens()));
        row.setSemanticRequestJson(serialise(req == null ? Map.of() : req));
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
        // Best-effort: isolated in REQUIRES_NEW (TX-003) so an insert failure cannot
        // abort this answer transaction and turn a success into a 500.
        persistence.persistAnswer(row);
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

    private String executionFailure(SemanticQueryResponse response) {
        if (response == null || response.getWarnings() == null) {
            return null;
        }
        return response.getWarnings().stream()
                .filter(warning -> warning != null
                        && warning.startsWith("execution_failed:"))
                .findFirst()
                .orElse(null);
    }

    /**
     * Resolve the model selected by either the explicit request pid or the
     * catalog-qualified tokens emitted by {@link TokenLexer}. The offline
     * catalog fallback always emits {@code <model>.<element>} codes, so a
     * cross-model conversation can still persist the concrete model chosen for
     * this answer.
     */
    private ResolvedSemanticModel resolveSemanticModel(
            SemanticMetaResponse catalog,
            String requestedPid,
            List<SearchToken> tokens) {
        String tokenModelCode = tokenModelCode(tokens);
        if (requestedPid != null && !requestedPid.isBlank()) {
            String catalogCode = catalog == null || catalog.getModels() == null
                    ? null
                    : catalog.getModels().stream()
                            .filter(model -> requestedPid.equals(model.getPid()))
                            .map(SemanticMetaResponse.ModelMeta::getCode)
                            .findFirst()
                            .orElse(null);
            String code = catalogCode != null ? catalogCode : tokenModelCode;
            return new ResolvedSemanticModel(requestedPid, code);
        }
        if (tokenModelCode == null || catalog == null || catalog.getModels() == null) {
            return null;
        }
        return catalog.getModels().stream()
                .filter(model -> tokenModelCode.equals(model.getCode()))
                .filter(model -> model.getPid() != null && !model.getPid().isBlank())
                .map(model -> new ResolvedSemanticModel(model.getPid(), model.getCode()))
                .findFirst()
                .orElse(null);
    }

    private String tokenModelCode(List<SearchToken> tokens) {
        if (tokens == null) {
            return null;
        }
        List<String> modelCodes = tokens.stream()
                .filter(token -> token != null
                        && (token.type() == com.auraboot.framework.chatbi.v2.dto.TokenType.METRIC
                            || token.type() == com.auraboot.framework.chatbi.v2.dto.TokenType.DIMENSION))
                .map(SearchToken::resolvedCode)
                .filter(code -> code != null && code.contains("."))
                .map(code -> code.substring(0, code.indexOf('.')))
                .distinct()
                .limit(2)
                .toList();
        return modelCodes.size() == 1 ? modelCodes.get(0) : null;
    }

    private SemanticMetaResponse scopeCatalog(SemanticMetaResponse catalog, String pid) {
        if (pid == null || pid.isBlank() || catalog == null
                || catalog.getModels() == null) {
            return catalog;
        }
        SemanticMetaResponse scoped = new SemanticMetaResponse();
        scoped.setModels(new ArrayList<>(catalog.getModels().stream()
                .filter(model -> pid.equals(model.getPid()))
                .toList()));
        return scoped;
    }

    private IntentResult withLexedTokens(IntentResult routed,
                                         List<SearchToken> lexedTokens) {
        List<SearchToken> safeTokens = lexedTokens == null
                ? List.of() : List.copyOf(lexedTokens);
        if (safeTokens.equals(routed.tokens())) {
            return routed;
        }
        boolean offlineFallback = routed.tokens() == null || routed.tokens().isEmpty();
        return new IntentResult(
                safeTokens,
                offlineFallback && !safeTokens.isEmpty() ? 0.80d : routed.confidence(),
                offlineFallback ? false : routed.needsClarification(),
                offlineFallback ? null : routed.disambiguation(),
                routed.suggestedFollowUps() == null
                        ? List.of() : routed.suggestedFollowUps(),
                routed.usage());
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

    private record ResolvedSemanticModel(String pid, String code) {}
}
