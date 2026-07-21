package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.entity.AgentDefinition;
import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Stop one agent, now — and let it back in later.
 *
 * <p>Until this existed the only lever was {@code aura.agent.enabled}, which
 * silences <em>every</em> agent in the deployment: an agent misbehaving for one
 * tenant meant either living with it or taking the whole runtime down. The
 * status column that both engines already gate on
 * ({@code status = 'active'} in the definition lookup used by
 * {@code AgentRunService} and {@code AgentChatPortImpl}) had no writer above the
 * database, so "disable this agent" was a manual UPDATE.
 *
 * <p>Suspension is deliberately expressed in that same column rather than a new
 * flag: the enforcement points already read it, so there is exactly one thing
 * that decides whether an agent may run, and no way for a second switch to
 * disagree with the first.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentLifecycleService {

    /** Deliberately halted by an operator — distinct from a never-activated draft. */
    public static final String STATUS_SUSPENDED = "suspended";
    public static final String STATUS_ACTIVE = "active";

    private final AgentDefinitionMapper agentDefinitionMapper;

    /** Outcome of a lifecycle transition, for the caller to report back. */
    public record Transition(String agentPid, String agentCode, String previousStatus, String status,
                             boolean changed) {}

    /**
     * Halt an agent. Dispatch, chat and delegation all resolve definitions with
     * {@code status = 'active'}, so a suspended agent stops being reachable on
     * every entry at once. Runs already in flight are not killed here — use the
     * interrupt path for those; this closes the door rather than emptying the room.
     */
    public Transition suspend(String agentPid, Long actorUserId, String reason) {
        return transition(agentPid, STATUS_SUSPENDED, actorUserId, reason);
    }

    /** Let a suspended agent run again. */
    public Transition resume(String agentPid, Long actorUserId) {
        return transition(agentPid, STATUS_ACTIVE, actorUserId, null);
    }

    private Transition transition(String agentPid, String target, Long actorUserId, String reason) {
        AgentDefinition agent = agentDefinitionMapper.findByPid(agentPid);
        if (agent == null) {
            throw new IllegalArgumentException("Agent not found: " + agentPid);
        }
        String previous = agent.getStatus();
        if (target.equals(previous)) {
            return new Transition(agentPid, agent.getAgentCode(), previous, target, false);
        }
        // Scoped by tenant as well as pid: a pid is unique, but a lifecycle write
        // that cannot name its tenant is one refactor away from crossing one.
        int updated = agentDefinitionMapper.update(null, new LambdaUpdateWrapper<AgentDefinition>()
                .eq(AgentDefinition::getPid, agentPid)
                .eq(AgentDefinition::getTenantId, agent.getTenantId())
                .set(AgentDefinition::getStatus, target)
                .set(AgentDefinition::getUpdatedBy, actorUserId)
                .set(AgentDefinition::getUpdatedAt, Instant.now()));
        if (updated == 0) {
            throw new IllegalStateException("Agent lifecycle update matched no row: " + agentPid);
        }
        log.warn("Agent lifecycle: agent={} ({}) {} -> {} by user={} reason={}",
                agent.getAgentCode(), agentPid, previous, target, actorUserId,
                reason == null || reason.isBlank() ? "(none given)" : reason);
        return new Transition(agentPid, agent.getAgentCode(), previous, target, true);
    }
}
