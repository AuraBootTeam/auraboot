package com.auraboot.framework.chatbi.v2.service;

import com.auraboot.framework.chatbi.v2.entity.ChatBiDisambiguationLog;
import com.auraboot.framework.chatbi.v2.mapper.ChatBiDisambiguationLogMapper;
import com.auraboot.framework.chatbi.v2.provider.Disambiguation;
import com.auraboot.framework.chatbi.v2.provider.IntentResult;
import com.auraboot.framework.common.util.UlidGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides whether a freshly translated {@link IntentResult} carries enough
 * confidence to compile + execute, or whether it must surface a disambiguation
 * prompt back to the user.
 *
 * <p>Trigger rules — verbatim from PRD 17 §7.3:
 *
 * <pre>
 *   if top1_score &lt; 0.5
 *       → return INTENT_LOW_CONFIDENCE
 *   if top1_score - top2_score &lt; 0.15 AND top2_score &gt; 0.5
 *       → return DISAMBIGUATION with [top1, top2, top3?]
 *   else
 *       → use top1
 * </pre>
 *
 * <p>When the verdict surfaces a prompt, the candidates plus the trigger
 * reason are persisted to {@code chatbi_disambiguation_log} so that:
 * <ul>
 *   <li>{@code POST /api/chatbi/disambiguate} can read the candidate set
 *       back and validate the user's choice without re-querying the LLM.</li>
 *   <li>The prompt-quality dashboard reports hourly disambiguation rate per
 *       PRD 17 §12 (alert above 30%/hour for one hour).</li>
 *   <li>Hot ambiguous terms (frequency &gt;= N in 7 days) feed back into
 *       the {@code chatbi_token_dict} so the LLM resolves them directly
 *       next time.</li>
 * </ul>
 *
 * <p>The user's choice is recorded by {@link #recordChoice(Long, String, String)}
 * which the disambiguate controller calls once the user picks a candidate.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DisambiguationService {

    /** Below this top1 score the LLM is clearly guessing — fail fast. */
    static final double LOW_CONFIDENCE_THRESHOLD = 0.5d;

    /** Tie window: top1 and top2 within this margin are considered ambiguous. */
    static final double AMBIGUITY_MARGIN = 0.15d;

    /** Only treat top2 as a real contender when it cleared this floor. */
    static final double VIABLE_RUNNER_UP_THRESHOLD = 0.5d;

    /** Verdict reason codes — stable identifiers for analytics + API contracts. */
    public static final String REASON_LOW_CONFIDENCE = "LOW_CONFIDENCE";
    public static final String REASON_AMBIGUOUS = "AMBIGUOUS";

    private final ChatBiDisambiguationLogMapper mapper;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    /**
     * Evaluate an LLM-produced intent and decide whether to short-circuit
     * into a clarification round-trip. When the verdict is
     * {@link Verdict#prompt() prompt()}, the prompt is also persisted so the
     * matching {@code disambiguate} controller can read it back without
     * re-querying the LLM.
     *
     * @param intent        translator output (never null)
     * @param tenantId      tenant scope for log persistence
     * @param answerPid     pid of the in-flight answer (foreign key)
     * @param ambiguousTerm the raw user-question phrase that triggered ambiguity
     */
    @Transactional
    public Verdict evaluate(IntentResult intent, Long tenantId, String answerPid,
                             String ambiguousTerm) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(answerPid, "answerPid");

        Disambiguation existing = intent.disambiguation();

        // Rule 1 — top1 below the floor: always surface a prompt
        if (intent.confidence() < LOW_CONFIDENCE_THRESHOLD) {
            Disambiguation prompt = existing != null
                    ? existing
                    : new Disambiguation(ambiguousTerm, List.of());
            String logPid = persistPrompt(tenantId, answerPid, prompt, REASON_LOW_CONFIDENCE);
            return Verdict.prompt(prompt, REASON_LOW_CONFIDENCE, logPid);
        }

        // Rule 2 — only an existing Disambiguation can tell us about top2
        if (existing != null && !existing.candidates().isEmpty()) {
            List<Disambiguation.Candidate> sorted = existing.candidates().stream()
                    .sorted((a, b) -> Double.compare(b.score(), a.score()))
                    .toList();
            double top1 = sorted.get(0).score();
            double top2 = sorted.size() > 1 ? sorted.get(1).score() : 0.0d;
            if ((top1 - top2) < AMBIGUITY_MARGIN && top2 > VIABLE_RUNNER_UP_THRESHOLD) {
                String logPid = persistPrompt(tenantId, answerPid, existing, REASON_AMBIGUOUS);
                return Verdict.prompt(existing, REASON_AMBIGUOUS, logPid);
            }
        }

        // Rule 3 — use the top1 token sequence directly
        return Verdict.useTop1();
    }

    /**
     * Records the user's response to a previously persisted prompt. Idempotent:
     * a second call for the same pid is a no-op so retries from the frontend
     * never overwrite the original choice.
     *
     * @return true when the row was updated (i.e. the user choice was new), false otherwise
     */
    @Transactional
    public boolean recordChoice(Long tenantId, String disambiguationPid, String userChoice) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(disambiguationPid, "disambiguationPid");
        Objects.requireNonNull(userChoice, "userChoice");
        int rows = mapper.recordChoice(tenantId, disambiguationPid, userChoice);
        if (rows == 0) {
            log.info("Disambiguation choice already recorded (or pid missing): pid={}",
                    disambiguationPid);
        }
        return rows > 0;
    }

    public Optional<ChatBiDisambiguationLog> findByPid(Long tenantId, String pid) {
        return Optional.ofNullable(mapper.findByPid(tenantId, pid));
    }

    // ---------------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------------

    private String persistPrompt(Long tenantId, String answerPid,
                                  Disambiguation prompt, String reason) {
        ChatBiDisambiguationLog row = new ChatBiDisambiguationLog();
        row.setPid(UlidGenerator.generate());
        row.setTenantId(tenantId);
        row.setAnswerPid(answerPid);
        row.setAmbiguousTerm(truncate(prompt.ambiguousTerm()));
        row.setCandidatesJson(toJson(prompt.candidates()));
        row.setTriggerReason(reason);
        mapper.insert(row);
        return row.getPid();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 256 ? s.substring(0, 256) : s;
    }

    private String toJson(Object o) {
        try {
            return jsonMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise disambiguation candidates: {}", e.getMessage());
            return "[]";
        }
    }

    // ---------------------------------------------------------------------
    // verdict
    // ---------------------------------------------------------------------

    /**
     * Outcome of {@link #evaluate}. Either {@code useTop1()} (compile + execute
     * immediately) or {@code prompt(disambiguation, reason, pid)} (surface a
     * clarification to the user and wait for their response).
     */
    public sealed interface Verdict {
        static Verdict useTop1() {
            return UseTop1.INSTANCE;
        }

        static Verdict prompt(Disambiguation disambiguation, String reason, String logPid) {
            return new PromptUser(disambiguation, reason.toUpperCase(Locale.ROOT), logPid);
        }

        record UseTop1() implements Verdict {
            static final UseTop1 INSTANCE = new UseTop1();
        }

        record PromptUser(
                Disambiguation disambiguation,
                String reason,
                String logPid) implements Verdict {
        }
    }
}
