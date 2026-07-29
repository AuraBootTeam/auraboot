package com.auraboot.framework.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Time;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * Single policy gate for non-human schedule and event triggers.
 *
 * <p>The gate is evaluated before a task is created. It verifies that the
 * target is an active, enrolled proactive digital employee, applies quiet
 * hours/manager/release/concurrency rules, and atomically consumes the shared
 * daily run budget. The later AgentRunService entry still resolves the
 * employee principal and re-applies tool/risk/approval policy.
 */
@Service
@RequiredArgsConstructor
public class ProactiveTriggerPolicyService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {
            };
    private static final int DEFAULT_DAILY_BUDGET = 24;
    private static final int DEFAULT_CONCURRENCY_LIMIT = 1;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public Decision evaluateAndClaimSchedule(
            long tenantId,
            Map<String, Object> schedule,
            Instant now) {
        if (schedule == null) {
            return Decision.denied("SCHEDULE_MISSING", null);
        }
        String agentCode = text(schedule.get("agent_code"));
        if (agentCode == null) {
            return Decision.denied("AGENT_REQUIRED", null);
        }
        return evaluateAndClaim(
                tenantId,
                agentCode,
                "schedule",
                text(schedule.get("timezone")),
                localTime(schedule.get("quiet_hours_start")),
                localTime(schedule.get("quiet_hours_end")),
                positiveInt(schedule.get("daily_run_budget"), DEFAULT_DAILY_BUDGET),
                positiveInt(schedule.get("concurrency_limit"), DEFAULT_CONCURRENCY_LIMIT),
                longValue(schedule.get("manager_member_id")),
                text(schedule.get("agent_release_pid")),
                now);
    }

    public Decision evaluateAndClaimEvent(
            long tenantId,
            String agentCode,
            Instant now) {
        List<Map<String, Object>> agents = jdbc.queryForList(
                """
                SELECT proactive_policy
                FROM ab_agent_definition
                WHERE tenant_id = ?
                  AND agent_code = ?
                  AND status = 'active'
                  AND agent_type = 'proactive'
                  AND employee_id IS NOT NULL
                  AND system_user_id IS NOT NULL
                  AND (deleted_flag IS NULL OR deleted_flag = FALSE)
                LIMIT 1
                """,
                tenantId,
                agentCode);
        if (agents.size() != 1) {
            return Decision.denied("AGENT_NOT_PROACTIVE", agentCode);
        }
        Map<String, Object> policy = jsonMap(agents.get(0).get("proactive_policy"));
        if (!allowedChannel(policy, "event")) {
            return Decision.denied("CHANNEL_NOT_ALLOWED", agentCode);
        }
        return evaluateAndClaim(
                tenantId,
                agentCode,
                "event",
                text(policy.get("timezone")),
                localTime(policy.get("quietHoursStart")),
                localTime(policy.get("quietHoursEnd")),
                positiveInt(policy.get("dailyRunBudget"), DEFAULT_DAILY_BUDGET),
                positiveInt(policy.get("concurrencyLimit"), DEFAULT_CONCURRENCY_LIMIT),
                longValue(policy.get("managerMemberId")),
                text(policy.get("agentReleasePid")),
                now);
    }

    private Decision evaluateAndClaim(
            long tenantId,
            String agentCode,
            String channel,
            String timezone,
            LocalTime quietStart,
            LocalTime quietEnd,
            int dailyBudget,
            int concurrencyLimit,
            Long managerMemberId,
            String fixedReleasePid,
            Instant now) {
        List<Map<String, Object>> agents = jdbc.queryForList(
                """
                SELECT proactive_policy
                FROM ab_agent_definition
                WHERE tenant_id = ?
                  AND agent_code = ?
                  AND status = 'active'
                  AND agent_type = 'proactive'
                  AND employee_id IS NOT NULL
                  AND system_user_id IS NOT NULL
                  AND (deleted_flag IS NULL OR deleted_flag = FALSE)
                LIMIT 1
                """,
                tenantId,
                agentCode);
        if (agents.size() != 1) {
            return Decision.denied("AGENT_NOT_PROACTIVE", agentCode);
        }
        Map<String, Object> agentPolicy =
                jsonMap(agents.get(0).get("proactive_policy"));
        if (!allowedChannel(agentPolicy, channel)) {
            return Decision.denied("CHANNEL_NOT_ALLOWED", agentCode);
        }

        String effectiveTimezone = timezone != null
                ? timezone
                : defaultText(agentPolicy.get("timezone"), "UTC");
        ZoneId zone;
        try {
            zone = ZoneId.of(effectiveTimezone);
        } catch (RuntimeException e) {
            return Decision.denied("TIMEZONE_INVALID", agentCode);
        }
        ZonedDateTime localNow = (now == null ? Instant.now() : now).atZone(zone);
        LocalTime effectiveQuietStart = quietStart != null
                ? quietStart
                : localTime(agentPolicy.get("quietHoursStart"));
        LocalTime effectiveQuietEnd = quietEnd != null
                ? quietEnd
                : localTime(agentPolicy.get("quietHoursEnd"));
        if (insideQuietHours(localNow.toLocalTime(), effectiveQuietStart, effectiveQuietEnd)) {
            return Decision.denied("QUIET_HOURS", agentCode);
        }

        Long effectiveManager = managerMemberId != null
                ? managerMemberId
                : longValue(agentPolicy.get("managerMemberId"));
        if (effectiveManager != null && !activeManager(tenantId, effectiveManager)) {
            return Decision.denied("MANAGER_SCOPE_INVALID", agentCode);
        }

        String effectiveRelease = fixedReleasePid != null
                ? fixedReleasePid
                : text(agentPolicy.get("agentReleasePid"));
        if (effectiveRelease != null && !releaseIsDeployed(
                tenantId, agentCode, effectiveRelease)) {
            return Decision.denied("RELEASE_NOT_DEPLOYED", agentCode);
        }

        int effectiveConcurrency = positiveInt(
                agentPolicy.get("concurrencyLimit"), concurrencyLimit);
        if (activeRunCount(tenantId, agentCode) >= effectiveConcurrency) {
            return Decision.denied("CONCURRENCY_LIMIT", agentCode);
        }

        int effectiveBudget = positiveInt(
                agentPolicy.get("dailyRunBudget"), dailyBudget);
        if (!claimDailyBudget(
                tenantId, agentCode, localNow.toLocalDate(), effectiveBudget)) {
            return Decision.denied("DAILY_BUDGET_EXHAUSTED", agentCode);
        }
        return new Decision(
                true,
                "ALLOWED",
                agentCode,
                zone.getId(),
                effectiveManager,
                effectiveRelease);
    }

    private boolean activeManager(long tenantId, long memberId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_tenant_member "
                        + "WHERE tenant_id = ? AND id = ? AND status = 'active' "
                        + "AND (deleted_flag IS NULL OR deleted_flag = FALSE)",
                Integer.class,
                tenantId,
                memberId);
        return count != null && count == 1;
    }

    private boolean releaseIsDeployed(
            long tenantId,
            String agentCode,
            String releasePid) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_deployment "
                        + "WHERE tenant_id = ? AND agent_code = ? "
                        + "AND agent_release_pid = ? AND status = 'active'",
                Integer.class,
                tenantId,
                agentCode,
                releasePid);
        return count != null && count == 1;
    }

    private int activeRunCount(long tenantId, String agentCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_run "
                        + "WHERE tenant_id = ? AND agent_id = ? "
                        + "AND run_status IN ('pending', 'running', 'waiting_input', "
                        + "'waiting_approval')",
                Integer.class,
                tenantId,
                agentCode);
        return count == null ? 0 : count;
    }

    private boolean claimDailyBudget(
            long tenantId,
            String agentCode,
            LocalDate usageDate,
            int budget) {
        List<Map<String, Object>> claimed = jdbc.queryForList(
                """
                INSERT INTO ab_agent_proactive_usage
                    (tenant_id, agent_code, usage_date, run_count, updated_at)
                VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP)
                ON CONFLICT (tenant_id, agent_code, usage_date)
                DO UPDATE SET
                    run_count = ab_agent_proactive_usage.run_count + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE ab_agent_proactive_usage.run_count < ?
                RETURNING run_count
                """,
                tenantId,
                agentCode,
                usageDate,
                budget);
        return !claimed.isEmpty();
    }

    private boolean allowedChannel(Map<String, Object> policy, String channel) {
        Object value = policy.get("allowedChannels");
        if (!(value instanceof List<?> channels) || channels.isEmpty()) {
            return true;
        }
        return channels.stream()
                .map(String::valueOf)
                .anyMatch(channel::equalsIgnoreCase);
    }

    public static boolean insideQuietHours(
            LocalTime now,
            LocalTime start,
            LocalTime end) {
        if (now == null || start == null || end == null || start.equals(end)) {
            return false;
        }
        if (start.isBefore(end)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        return !now.isBefore(start) || now.isBefore(end);
    }

    private Map<String, Object> jsonMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            java.util.LinkedHashMap<String, Object> normalized =
                    new java.util.LinkedHashMap<>();
            map.forEach((key, item) -> normalized.put(String.valueOf(key), item));
            return Map.copyOf(normalized);
        }
        try {
            return objectMapper.readValue(String.valueOf(value), MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String text(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return String.valueOf(value).trim();
    }

    private String defaultText(Object value, String fallback) {
        String parsed = text(value);
        return parsed == null ? fallback : parsed;
    }

    private Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return value instanceof Number number
                    ? number.longValue()
                    : Long.parseLong(String.valueOf(value));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private int positiveInt(Object value, int fallback) {
        Long parsed = longValue(value);
        return parsed == null || parsed <= 0L || parsed > Integer.MAX_VALUE
                ? fallback
                : parsed.intValue();
    }

    private LocalTime localTime(Object value) {
        if (value instanceof LocalTime localTime) {
            return localTime;
        }
        if (value instanceof Time time) {
            return time.toLocalTime();
        }
        String parsed = text(value);
        if (parsed == null) {
            return null;
        }
        try {
            return LocalTime.parse(parsed);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public record Decision(
            boolean allowed,
            String reason,
            String agentCode,
            String timezone,
            Long managerMemberId,
            String agentReleasePid) {

        static Decision denied(String reason, String agentCode) {
            return new Decision(false, reason, agentCode, null, null, null);
        }
    }
}
