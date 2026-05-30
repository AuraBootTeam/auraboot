package com.auraboot.framework.chatbi.v2.dto;

import com.auraboot.framework.chatbi.v2.provider.Disambiguation;
import com.auraboot.framework.chatbi.v2.provider.LlmProviderRouter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * API-facing response for a single ChatBI v2 ask. PRD 17 §6.2 / §11.
 *
 * <p>{@code status} drives the UI:
 * <ul>
 *   <li>{@code SUCCESS} — {@code rows} populated, render via {@code vizType}.</li>
 *   <li>{@code DISAMBIGUATION} — {@code disambiguation} populated, render the
 *       candidate picker; the caller will subsequently POST to
 *       {@code /disambiguate} with the chosen candidate.</li>
 *   <li>{@code FAILED} — {@code errorMessage} carries the user-safe reason.</li>
 * </ul>
 *
 * <p>{@code attempts} reflects the LLM router fallback chain (e.g. Anthropic
 * succeeded, OpenAI not attempted), useful for observability + the operator
 * dashboard.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatBiAnswerResponse {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_DISAMBIGUATION = "DISAMBIGUATION";
    public static final String STATUS_FAILED = "FAILED";

    private String answerPid;
    private String conversationPid;
    private String status;
    private String errorMessage;

    private String nlQuery;
    private List<SearchToken> tokens;
    private double confidence;
    private List<String> suggestedFollowUps;

    /** Populated only when {@code status=DISAMBIGUATION}. */
    private Disambiguation disambiguation;

    /** Populated only when {@code status=SUCCESS}. */
    private List<Map<String, Object>> rows;
    private Integer rowCount;
    private Integer durationMs;
    private String vizType;
    private String sql;

    /** Provider attempts in route order — for observability. */
    private List<LlmProviderRouter.Attempt> attempts;
    private String llmUsed;
}
