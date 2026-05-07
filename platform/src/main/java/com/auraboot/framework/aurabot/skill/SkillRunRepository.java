package com.auraboot.framework.aurabot.skill;

import com.auraboot.framework.aurabot.skill.entity.SkillRun;
import com.auraboot.framework.aurabot.skill.mapper.SkillRunMapper;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Service-layer wrapper for {@code ab_aurabot_skill_run}.
 *
 * <p>Centralises enum &lt;-&gt; column-string conversion (always
 * {@code .code()} / {@code fromCode()}; {@code enum.name()} is forbidden by
 * the project red-line) so callers never see raw VARCHAR values.
 *
 * <p>This wrapper is intentionally thin: no caching, no transaction
 * decorators. The validator pipeline (Step 6) and controller (Step 7) will
 * compose this with Redis idempotency + audit-log writes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillRunRepository {

    /** Default idempotency window — 5 minutes per Contract §3. */
    public static final Duration DEFAULT_IDEMPOTENCY_WINDOW = Duration.ofMinutes(5);

    private final SkillRunMapper mapper;

    /**
     * Insert a new run. {@code pid} is auto-populated when blank;
     * {@code status} / {@code riskLevel} are normalised through their
     * respective {@code .code()} converters.
     */
    public SkillRun insert(SkillRun run) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(run.getTenantId(), "tenantId");
        Objects.requireNonNull(run.getSkillName(), "skillName");
        Objects.requireNonNull(run.getStatus(), "status");
        Objects.requireNonNull(run.getRiskLevel(), "riskLevel");

        if (run.getPid() == null || run.getPid().isBlank()) {
            run.setPid("usr_" + UniqueIdGenerator.generate());
        }
        if (run.getCreatedAt() == null) {
            run.setCreatedAt(Instant.now());
        }
        if (run.getDeletedFlag() == null) {
            run.setDeletedFlag(Boolean.FALSE);
        }
        // Validate code-only invariant up front — fail loud on enum.name() leaks.
        SkillRunStatus.fromCode(run.getStatus());
        RiskLevel.fromCode(run.getRiskLevel());

        int affected = mapper.insert(run);
        if (affected != 1) {
            throw new IllegalStateException(
                    "SkillRun insert affected " + affected + " rows for pid=" + run.getPid());
        }
        return run;
    }

    /**
     * Convenience overload taking the typed enums directly. Always prefer
     * this signature in service-layer callers; the {@code String} columns on
     * the entity exist purely for MyBatis round-tripping.
     */
    public SkillRun insert(SkillRun run, SkillRunStatus status, RiskLevel riskLevel) {
        run.setStatus(status.code());
        run.setRiskLevel(riskLevel.code());
        return insert(run);
    }

    public Optional<SkillRun> findByIdempotency(Long tenantId,
                                                String skillName,
                                                String idempotencyKey,
                                                Duration window) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        Duration w = window != null ? window : DEFAULT_IDEMPOTENCY_WINDOW;
        Instant since = Instant.now().minus(w);
        return Optional.ofNullable(
                mapper.findByIdempotency(tenantId, skillName, idempotencyKey, since));
    }

    public Optional<SkillRun> findByUndoToken(String undoToken) {
        if (undoToken == null || undoToken.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.findByUndoToken(undoToken));
    }

    public List<SkillRun> findByBatchId(Long tenantId, String batchId) {
        return mapper.findByBatchId(tenantId, batchId);
    }

    /**
     * Flip a run to {@link SkillRunStatus#UNDONE} and stamp {@code undone_at = now()}.
     *
     * @return whether exactly one row was updated.
     */
    public boolean markUndone(String pid) {
        Objects.requireNonNull(pid, "pid");
        int affected = mapper.markUndone(pid, SkillRunStatus.UNDONE.code(), Instant.now());
        if (affected > 1) {
            // Defensive: PK must be unique; >1 is data corruption, not a soft error.
            throw new IllegalStateException("markUndone affected " + affected + " rows for pid=" + pid);
        }
        return affected == 1;
    }

    public long countByTenantSinceTs(Long tenantId, Instant sinceTs) {
        return mapper.countByTenantSinceTs(tenantId, sinceTs);
    }
}
