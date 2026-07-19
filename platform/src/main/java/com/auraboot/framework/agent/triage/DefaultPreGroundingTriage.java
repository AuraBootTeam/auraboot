package com.auraboot.framework.agent.triage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Rule-based default {@link PreGroundingTriage}. Decision priority (first
 * match wins) per contract:
 *
 * <ol>
 *   <li>Channel explicit override ({@link PreGroundingTriage#SYSTEM_CHANNELS} → ACP_RUN)
 *   <li>Profile default policy (configured light profiles → LIGHT_CHAT).
 *       <b>Note (review G11, pending owner ruling)</b>: this rule deliberately
 *       precedes all text rules, so a light profile shadows durable/action
 *       keywords ("批量删除" under support_chat stays LIGHT_CHAT). Caller
 *       flags are NOT shadowed — the planner evaluates them independently.
 *   <li>Explain-prefix short-circuit (review G4): a turn that <i>opens</i>
 *       with an interrogative/explain marker is asking ABOUT an operation,
 *       not requesting it ("为什么导出会失败") — routed as explanation even
 *       when durable/action verbs appear later.
 *   <li>Keyword match (durable verbs → ACP_RUN; simple action verbs stay in
 *       chat for tool policy; read verbs → read-only contextual)
 *   <li>Default fallback (has any context → CONTEXTUAL_ANSWER else LIGHT_CHAT)
 * </ol>
 *
 * <p>The LLM low-cost classifier fallback is intentionally NOT in this
 * default. To add: replace this bean with an LLM-aware impl that calls the
 * keyword matcher first, then dispatches uncertain cases to the LLM.
 *
 * <p>2026-07-19 (review G5): the history-hotness rule (≥5 recent light turns)
 * was deleted — its input was hardwired to 0 at the only call site, so it
 * never fired. History-derived routing belongs to the future LLM classifier,
 * informed by turn-observation telemetry.
 */
@Service
public class DefaultPreGroundingTriage implements PreGroundingTriage {

    /** CJK + English platform action verbs that imply a write action but not durable execution by themselves. */
    private static final Pattern PLATFORM_ACTION_PATTERN = Pattern.compile(
            "(创建|新建|新增|添加|编辑|修改|更新|" +
                    "create|add|edit|update)",
            Pattern.CASE_INSENSITIVE);

    /** CJK + English platform action signals that require durable orchestration before model grounding. */
    private static final Pattern PLATFORM_DURABLE_PATTERN = Pattern.compile(
            "(批量|大量|全量|全部|删除|审批|导出|同步|外部|" +
                    "batch|bulk|delete|approve|export|sync|external)",
            Pattern.CASE_INSENSITIVE);

    /** CJK + English platform read verbs that should stay in a policy-gated read-only turn. */
    private static final Pattern PLATFORM_READONLY_PATTERN = Pattern.compile(
            "(查询|搜索|统计|筛选|分组|查看|列出|" +
                    "query|search|filter|count|list|view all|show)",
            Pattern.CASE_INSENSITIVE);

    /** Pure-explanation verbs: ask about page / record meaning, no platform action. */
    private static final Pattern EXPLAIN_VERB_PATTERN = Pattern.compile(
            "(什么意思|是什么|怎么用|如何使用|为什么|含义|解释|说明|对比|区别|" +
                    "what is|how to use|why|explain|meaning|difference)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Review G4: explain-shaped OPENINGS. Prefix-anchored on purpose — a
     * message that begins with an interrogative marker is with high precision
     * asking about an operation ("为什么导出会失败", "是否支持批量导出"),
     * while commands rarely open with these. Politeness-imperative forms
     * (能不能/可不可以/can you/could you) are deliberately absent: "能不能帮我
     * 删除这些" is a request to execute, not a question about deleting. This
     * cuts the durable-keyword misfire without the reverse misroutes a
     * blanket explain-over-durable priority flip would introduce.
     */
    private static final Pattern EXPLAIN_PREFIX_PATTERN = Pattern.compile(
            "^\\s*(为什么|为啥|什么是|什么叫|如何|怎么|怎样|是否|" +
                    "why\\b|what\\b|how\\b|explain\\b)",
            Pattern.CASE_INSENSITIVE);

    /** Default light-profile set; override via {@code aurabot.triage.light-profiles}. */
    static final String DEFAULT_LIGHT_PROFILES = "support_chat";

    private final Set<String> lightProfiles;

    public DefaultPreGroundingTriage(
            @Value("${aurabot.triage.light-profiles:" + DEFAULT_LIGHT_PROFILES + "}") List<String> lightProfiles) {
        this.lightProfiles = lightProfiles == null ? Set.of() : lightProfiles.stream()
                .filter(p -> p != null && !p.isBlank())
                .map(p -> p.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Test/default convenience: light profiles = {@value #DEFAULT_LIGHT_PROFILES}. */
    public DefaultPreGroundingTriage() {
        this(List.of(DEFAULT_LIGHT_PROFILES));
    }

    @Override
    public TriageVerdict triage(TriageRequest request) {
        // Rule 1: channel-level override
        if (request.channel() != null && SYSTEM_CHANNELS.contains(request.channel().toLowerCase())) {
            return new TriageVerdict(
                    TriageBucket.ACP_RUN, 1.0,
                    List.of("rule:channel_force_acp", "channel:" + request.channel()),
                    Set.of());
        }

        // Rule 2: profile default (precedes text rules — see class javadoc / review G11)
        if (request.profileId() != null && lightProfiles.contains(request.profileId().toLowerCase())) {
            return new TriageVerdict(
                    TriageBucket.LIGHT_CHAT, 0.95,
                    List.of("rule:profile_light_default", "profile:" + request.profileId()),
                    Set.of());
        }

        String message = request.userMessage() == null ? "" : request.userMessage();
        boolean platformActionKeyword = PLATFORM_ACTION_PATTERN.matcher(message).find();
        boolean platformDurableKeyword = PLATFORM_DURABLE_PATTERN.matcher(message).find();
        boolean platformReadOnlyKeyword = PLATFORM_READONLY_PATTERN.matcher(message).find();
        boolean explainKeyword = EXPLAIN_VERB_PATTERN.matcher(message).find();

        // Rule 3 (review G4): explain-prefix short-circuit — an explain-shaped
        // opening wins over durable/action verbs that appear later in the message.
        if (EXPLAIN_PREFIX_PATTERN.matcher(message).find()) {
            return explainVerdict(request, "rule:explain_prefix_short_circuit");
        }

        // Rule 4: keyword match
        if (platformDurableKeyword) {
            return new TriageVerdict(
                    TriageBucket.ACP_RUN, 0.85,
                    List.of("rule:keyword_platform_durable"),
                    Set.of());
        }
        if (platformActionKeyword) {
            return new TriageVerdict(
                    TriageBucket.LIGHT_CHAT, 0.80,
                    List.of("rule:keyword_platform_action_sync"),
                    Set.of());
        }
        if (platformReadOnlyKeyword) {
            return new TriageVerdict(
                    TriageBucket.CONTEXTUAL_ANSWER, 0.80,
                    List.of("rule:keyword_readonly_platform"),
                    READONLY_CONTEXT_TOOLS);
        }
        if (explainKeyword) {
            return explainVerdict(request, "rule:keyword_explain");
        }

        // LLM fallback intentionally absent in default. Fallback to default-by-context.
        if (request.hasPageContext() || request.hasRecordContext()) {
            return new TriageVerdict(
                    TriageBucket.CONTEXTUAL_ANSWER, 0.50,
                    List.of("rule:default_has_context"),
                    READONLY_CONTEXT_TOOLS);
        }
        return new TriageVerdict(
                TriageBucket.LIGHT_CHAT, 0.50,
                List.of("rule:default_no_context"),
                Set.of());
    }

    /**
     * Shared explanation routing: with page/record context → contextual answer
     * with read-only tools; without → light chat. {@code reasonTag} keeps the
     * prefix short-circuit distinguishable from mid-sentence explain verbs in
     * telemetry.
     */
    private static TriageVerdict explainVerdict(TriageRequest request, String reasonTag) {
        if (request.hasPageContext() || request.hasRecordContext()) {
            return new TriageVerdict(
                    TriageBucket.CONTEXTUAL_ANSWER, 0.80,
                    List.of(reasonTag, "explain:with_context"),
                    READONLY_CONTEXT_TOOLS);
        }
        return new TriageVerdict(
                TriageBucket.LIGHT_CHAT, 0.75,
                List.of(reasonTag, "explain:no_context"),
                Set.of());
    }
}
