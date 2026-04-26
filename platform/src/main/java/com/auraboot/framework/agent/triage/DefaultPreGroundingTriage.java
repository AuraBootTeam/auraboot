package com.auraboot.framework.agent.triage;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rule-based default {@link PreGroundingTriage}. Decision priority (first
 * match wins) per contract:
 *
 * <ol>
 *   <li>Channel explicit override (webhook / BPM → ACP_RUN)
 *   <li>Profile default policy (currently only "support_chat" → LIGHT_CHAT)
 *   <li>History hotness (≥5 recent light turns → continue LIGHT_CHAT unless platform keyword)
 *   <li>Keyword match (CRUD verbs → ACP_RUN; explanation verbs → CONTEXTUAL_ANSWER / LIGHT_CHAT)
 *   <li>Default fallback (has any context → CONTEXTUAL_ANSWER else LIGHT_CHAT)
 * </ol>
 *
 * <p>Rule 5 (LLM low-cost classifier fallback) is intentionally NOT in this
 * default. To add: replace this bean with an LLM-aware impl that calls the
 * keyword matcher first, then dispatches uncertain cases to the LLM.
 */
@Service
public class DefaultPreGroundingTriage implements PreGroundingTriage {

    private static final Set<String> ACP_FORCE_CHANNELS = Set.of("webhook", "bpm", "scheduled");
    private static final Set<String> LIGHT_PROFILES = Set.of("support_chat");
    private static final int HISTORY_LIGHT_THRESHOLD = 5;

    /** CJK + English platform action verbs / nouns hinting at CRUD or workflow. */
    private static final Pattern PLATFORM_VERB_PATTERN = Pattern.compile(
            "(创建|新建|新增|添加|编辑|修改|更新|删除|审批|查询|搜索|统计|筛选|分组|导出|" +
                    "create|add|edit|update|delete|approve|query|search|filter|export|count|list|view all)",
            Pattern.CASE_INSENSITIVE);

    /** Pure-explanation verbs: ask about page / record meaning, no platform action. */
    private static final Pattern EXPLAIN_VERB_PATTERN = Pattern.compile(
            "(什么意思|是什么|怎么用|如何使用|为什么|含义|解释|说明|对比|区别|" +
                    "what is|how to use|why|explain|meaning|difference)",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> READONLY_CONTEXT_TOOLS = Set.of("schema.lookup", "record.view");

    @Override
    public TriageVerdict triage(TriageRequest request) {
        // Rule 1: channel-level override
        if (request.channel() != null && ACP_FORCE_CHANNELS.contains(request.channel().toLowerCase())) {
            return new TriageVerdict(
                    TriageBucket.ACP_RUN, 1.0,
                    List.of("rule:channel_force_acp", "channel:" + request.channel()),
                    Set.of());
        }

        // Rule 2: profile default
        if (request.profileId() != null && LIGHT_PROFILES.contains(request.profileId().toLowerCase())) {
            return new TriageVerdict(
                    TriageBucket.LIGHT_CHAT, 0.95,
                    List.of("rule:profile_light_default", "profile:" + request.profileId()),
                    Set.of());
        }

        String message = request.userMessage() == null ? "" : request.userMessage();
        boolean platformKeyword = PLATFORM_VERB_PATTERN.matcher(message).find();
        boolean explainKeyword = EXPLAIN_VERB_PATTERN.matcher(message).find();

        // Rule 3: history hotness — sustained light_chat continues unless platform keyword breaks streak
        if (request.recentLightTurnCount() >= HISTORY_LIGHT_THRESHOLD && !platformKeyword) {
            return new TriageVerdict(
                    TriageBucket.LIGHT_CHAT, 0.85,
                    List.of("rule:history_light_streak", "streak:" + request.recentLightTurnCount()),
                    Set.of());
        }

        // Rule 4: keyword match
        if (platformKeyword) {
            return new TriageVerdict(
                    TriageBucket.ACP_RUN, 0.85,
                    List.of("rule:keyword_platform_verb"),
                    Set.of());
        }
        if (explainKeyword) {
            // Explanation needing context → contextual_answer with readonly tools
            // Explanation without context → light_chat
            if (request.hasPageContext() || request.hasRecordContext()) {
                return new TriageVerdict(
                        TriageBucket.CONTEXTUAL_ANSWER, 0.80,
                        List.of("rule:keyword_explain_with_context"),
                        READONLY_CONTEXT_TOOLS);
            }
            return new TriageVerdict(
                    TriageBucket.LIGHT_CHAT, 0.75,
                    List.of("rule:keyword_explain_no_context"),
                    Set.of());
        }

        // Rule 5 (LLM fallback) intentionally absent in default. Fallback to default-by-context.
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
}
