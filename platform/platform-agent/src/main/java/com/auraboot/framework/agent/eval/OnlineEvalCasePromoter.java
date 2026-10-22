package com.auraboot.framework.agent.eval;

import com.auraboot.framework.agent.entity.AgentEvalCase;
import com.auraboot.framework.agent.eval.AgentTurnQualityJudge.TurnVerdict;
import com.auraboot.framework.agent.mapper.AgentEvalCaseMapper;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Closes the L4 online-eval flywheel (CAP-02): turns a hard online-eval failure into a
 * deduplicated <em>candidate</em> regression case in {@code ab_agent_eval_case}, so the
 * failure survives past the nightly summary instead of only logging a degradation signal.
 *
 * <p>A candidate is tagged {@link #CANDIDATE_CATEGORY} and carries empty expected
 * behaviour — a real regression case needs a human to specify what the agent <em>should</em>
 * have done, which the online sample cannot know. {@code CapabilityEvalService} therefore
 * <b>excludes</b> this category from the capability gate, so a raw candidate is never
 * replayed as if it were a curated case. The flywheel is: online failure → deduped
 * candidate → human curates expected behaviour → real regression case.
 *
 * <p>Dedup is by the natural {@code (tenant_id, agent_code, case_id)} unique key
 * ({@code case_id = "online-" + runPid}); a run is captured once. Best-effort — a promotion
 * failure never disturbs the eval cycle.
 */
@Slf4j
@Component
public class OnlineEvalCasePromoter {

    /** Category marking an auto-captured, not-yet-curated regression candidate. */
    public static final String CANDIDATE_CATEGORY = "online_regression_candidate";

    private final JdbcTemplate jdbc;
    private final AgentEvalCaseMapper caseMapper;

    public OnlineEvalCasePromoter(JdbcTemplate jdbc, AgentEvalCaseMapper caseMapper) {
        this.jdbc = jdbc;
        this.caseMapper = caseMapper;
    }

    /**
     * Promote the hard failures among {@code verdicts} into candidate cases.
     *
     * @param verdicts       the turn verdicts from an online-eval cycle (the summary's list)
     * @param maxPromotions  cap on new candidates created this cycle (bounds a bad-night flood)
     * @return the number of NEW candidate cases created (existing/deduped are not counted)
     */
    public int promoteHardFailures(long tenantId, List<TurnVerdict> verdicts, int maxPromotions) {
        if (verdicts == null || verdicts.isEmpty() || maxPromotions <= 0) {
            return 0;
        }
        int promoted = 0;
        for (TurnVerdict v : verdicts) {
            if (promoted >= maxPromotions) {
                break;
            }
            // Only HARD failures (unhealthy + score 0) — not merely ambiguous/low turns.
            if (v == null || v.healthy() || v.score() > 0.0) {
                continue;
            }
            String agentCode = resolveAgentCode(tenantId, v.runPid());
            if (agentCode == null || agentCode.isBlank()) {
                // No agent identity for this run — cannot build a well-formed case.
                continue;
            }
            String caseId = "online-" + v.runPid();
            if (caseExists(tenantId, agentCode, caseId)) {
                continue; // already captured this run
            }
            try {
                caseMapper.insert(buildCandidate(tenantId, agentCode, caseId, v));
                promoted++;
            } catch (DuplicateKeyException dup) {
                // Raced with another promotion; the unique index caught it — treat as deduped.
            } catch (Exception e) {
                log.warn("Online-eval candidate promotion failed for run {}: {}", v.runPid(), e.getMessage());
            }
        }
        if (promoted > 0) {
            log.info("Online-eval flywheel: promoted {} hard-failure candidate case(s) for tenant {}",
                    promoted, tenantId);
        }
        return promoted;
    }

    private String resolveAgentCode(long tenantId, String runPid) {
        if (runPid == null) {
            return null;
        }
        List<String> ids = jdbc.queryForList(
                "SELECT obs_agent_id FROM ab_agent_observation "
                        + "WHERE tenant_id = ? AND source_id = ? AND obs_agent_id IS NOT NULL LIMIT 1",
                String.class, tenantId, runPid);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private boolean caseExists(long tenantId, String agentCode, String caseId) {
        Long n = caseMapper.selectCount(new LambdaQueryWrapper<AgentEvalCase>()
                .eq(AgentEvalCase::getTenantId, tenantId)
                .eq(AgentEvalCase::getAgentCode, agentCode)
                .eq(AgentEvalCase::getCaseId, caseId)
                .and(w -> w.eq(AgentEvalCase::getDeletedFlag, false)
                        .or().isNull(AgentEvalCase::getDeletedFlag)));
        return n != null && n > 0;
    }

    private AgentEvalCase buildCandidate(long tenantId, String agentCode, String caseId, TurnVerdict v) {
        String reason = v.reason() == null || v.reason().isBlank()
                ? "unhealthy turn (score " + v.score() + ")"
                : v.reason();
        AgentEvalCase c = new AgentEvalCase();
        c.setPid(UniqueIdGenerator.generate());
        c.setTenantId(tenantId);
        c.setAgentCode(agentCode);
        c.setCaseId(caseId);
        c.setCategory(CANDIDATE_CATEGORY);
        c.setTaskDescription("Auto-captured from an online-eval hard failure (run " + v.runPid()
                + "): " + reason + ". Curate the expected behaviour before promoting to a regression case.");
        c.setExpectedToolCodes(List.of());
        c.setForbiddenToolCodes(List.of());
        c.setExpectedInputKeys(Map.of());
        c.setExpectsConfirmation(false);
        c.setPluginSource("online-eval");
        c.setDeletedFlag(false);
        return c;
    }
}
