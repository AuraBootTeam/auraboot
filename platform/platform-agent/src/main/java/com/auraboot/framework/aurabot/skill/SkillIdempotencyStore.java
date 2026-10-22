package com.auraboot.framework.aurabot.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed first-claim ledger for the AuraBot Skill SPI idempotency
 * contract (Plan Step 5 / SPI Contract §6).
 *
 * <p><strong>Storage shape (R1).</strong> Redis stores <em>only</em> the
 * winning {@code pid} of the first request that successfully claimed a given
 * tuple {@code (tenantId, skillName, idempotencyKey)}. The full result payload
 * is <em>never</em> written to Redis — replayers fetch the canonical
 * {@link com.auraboot.framework.aurabot.skill.entity.SkillRun} from Postgres
 * via {@link SkillRunRepository#findByIdempotency} using the prior pid.
 * This avoids a Redis-vs-DB consistency window (see plan §11 R1 decision).
 *
 * <p><strong>Lifecycle.</strong>
 * <ol>
 *     <li>{@link #tryClaim} performs an atomic {@code SETNX} with TTL 5 min.
 *         A new caller gets {@link Optional#empty()} (proceed); a duplicate
 *         caller gets {@link Optional} of the prior pid (replay path — go
 *         look up the SkillRun in Postgres).</li>
 *     <li>{@link #release} is the stale-claim escape hatch: if the DB
 *         contains no matching row for the prior pid (e.g. the original
 *         insertion crashed between SETNX and DB commit), the validator
 *         clears the Redis claim so the next caller can proceed normally.
 *         B4 will wire this in.</li>
 * </ol>
 *
 * <p><strong>Failure mode.</strong> Redis-unreachable handling is the
 * caller's (B4 Validator) responsibility — this class deliberately does not
 * catch {@link org.springframework.dao.DataAccessException} so that
 * {@link org.springframework.data.redis.RedisConnectionFailureException}
 * propagates and the validator can decide whether to fail-open or fail-closed
 * per the {@code SkillMeta} risk level. No internal fallback / retry: that
 * would mask outages and contradict the catch-exception-pattern v1.1 P2 rule.
 */
@Slf4j
@Component
@ConditionalOnBean(StringRedisTemplate.class)
public class SkillIdempotencyStore {

    /**
     * Visible for tests so they can scan / clean up by prefix without
     * duplicating the format string.
     */
    public static final String KEY_PREFIX = "aurabot:idemp:";

    /** SPI contract §6: window during which duplicate calls return the prior pid. */
    public static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;

    public SkillIdempotencyStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Build the Redis key for an idempotency tuple. Public so B4 / tests can
     * audit / clean keys without re-deriving the format.
     */
    public static String buildKey(long tenantId, String skillName, String idempotencyKey) {
        return KEY_PREFIX + tenantId + ":" + skillName + ":" + idempotencyKey;
    }

    /**
     * Attempt to claim the first-write slot for {@code (tenantId, skillName,
     * idempotencyKey)} with the given {@code pid}.
     *
     * @return {@link Optional#empty()} on a successful first-claim (caller
     *         proceeds with execution); a non-empty {@link Optional} carrying
     *         the prior winner's pid on a duplicate claim (caller goes to
     *         the DB to fetch the canonical SkillRun and replay it).
     */
    public Optional<String> tryClaim(long tenantId, String skillName, String idempotencyKey, String pid) {
        String key = buildKey(tenantId, skillName, idempotencyKey);
        Boolean firstWriter = redisTemplate.opsForValue().setIfAbsent(key, pid, TTL);
        if (Boolean.TRUE.equals(firstWriter)) {
            log.debug("Idempotency claim acquired tenant={} skill={} idem={} pid={}",
                    tenantId, skillName, idempotencyKey, pid);
            return Optional.empty();
        }
        // Lost the race or replay within window — read the prior pid and let
        // the caller decide replay vs. release-and-retry.
        String priorPid = redisTemplate.opsForValue().get(key);
        log.debug("Idempotency duplicate tenant={} skill={} idem={} priorPid={}",
                tenantId, skillName, idempotencyKey, priorPid);
        return Optional.ofNullable(priorPid);
    }

    /**
     * Drop a claim — used when the validator determines the prior pid has no
     * matching DB row (orphan claim from a crash between SETNX and the DB
     * commit) or when an explicit retry is allowed.
     *
     * <p>Idempotent: deleting a missing key is fine (Spring's {@code delete}
     * returns false but raises no exception).
     */
    public void release(long tenantId, String skillName, String idempotencyKey) {
        String key = buildKey(tenantId, skillName, idempotencyKey);
        Boolean removed = redisTemplate.delete(key);
        if (Boolean.TRUE.equals(removed)) {
            log.debug("Idempotency claim released tenant={} skill={} idem={}",
                    tenantId, skillName, idempotencyKey);
        }
    }

    /**
     * Intentionally not implemented: the result payload is never persisted in
     * Redis (R1 — see class javadoc). Replay flows must fetch the canonical
     * {@link com.auraboot.framework.aurabot.skill.entity.SkillRun} from
     * Postgres via {@link SkillRunRepository#findByIdempotency}.
     *
     * <p>This method is kept as a documentation anchor so future contributors
     * do not reintroduce a Redis-side payload cache without revisiting R1.
     */
    @SuppressWarnings("unused")
    static void recordResult_documentationOnly() {
        // No-op anchor. Do not add a payload-write path here.
    }
}
