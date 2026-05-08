package com.auraboot.framework.aurabot.skill;

import com.auraboot.framework.aurabot.skill.entity.SkillRun;
import com.auraboot.framework.aurabot.skill.error.SkillErrorCode;
import com.auraboot.framework.aurabot.skill.error.SkillSpiException;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Five-step request validator for the AuraBot Skill SPI (Plan Step 6 / SPI
 * Contract §6 §11). Sole entry point used by the controller layer (B5 /
 * Step 7) — composes registry / repo / Redis stores into a single
 * deterministic pipeline so the controller stays a thin HTTP-mapping shell.
 *
 * <p><strong>Pipeline order (strict).</strong>
 * <ol>
 *     <li><strong>Skill exists</strong> — {@link AuraBotSkillRegistry#get}
 *         returns empty → 404 {@link SkillErrorCode#SKILL_NOT_FOUND}.</li>
 *     <li><strong>Permission check</strong> —
 *         {@code skill.requiredPermissions() ⊆ userPerms} → otherwise 403
 *         {@link SkillErrorCode#PERMISSION_DENIED}.</li>
 *     <li><strong>Idempotency replay</strong> ({@link ValidationMode#EXECUTE}
 *         only). Atomically claims the Redis ledger via
 *         {@link SkillIdempotencyStore#tryClaim}; on a duplicate claim looks
 *         up the prior {@link SkillRun} from Postgres
 *         ({@link SkillRunRepository#findByIdempotency}). DB hit →
 *         <em>short-circuit</em> the pipeline with a replay
 *         {@link SkillResult}; orphan claim (no DB row) →
 *         {@link SkillIdempotencyStore#release} and proceed normal path.
 *         <br>
 *         {@link RedisConnectionFailureException} is logged at WARN and
 *         <em>swallowed</em> — fail-open per plan §R2 (the DB unique index
 *         on {@code (tenant_id, skill_name, idempotency_key)} ultimately
 *         blocks duplicate inserts even when Redis is offline). This is the
 *         project's catch-exception-pattern P2 mode (alternative path), the
 *         only place in this validator that swallows an exception.</li>
 *     <li><strong>JSON-Schema validation</strong> — runs the pre-compiled
 *         schema from {@link AuraBotSkillRegistry#getCompiledSchema} against
 *         {@code req.params()}; non-empty
 *         {@link com.networknt.schema.ValidationMessage} set → 400
 *         {@link SkillErrorCode#PARAMS_INVALID}, message includes the JSON
 *         pointer of the first offending field.</li>
 *     <li><strong>Risk gating</strong> ({@link ValidationMode#EXECUTE} only,
 *         {@code riskLevel ≥ MEDIUM}). Missing token → 422
 *         {@link SkillErrorCode#CONFIRM_REQUIRED}; mismatched token → 422
 *         {@link SkillErrorCode#PREVIEW_TOKEN_INVALID}. On a hit the decoded
 *         envelope is attached to the returned {@link ValidatedRequest} so
 *         the controller can pass it to the executor without a second Redis
 *         round-trip.</li>
 * </ol>
 *
 * <p><strong>Modes.</strong> {@link ValidationMode#DRY_RUN} skips Step 3
 * (idempotency) and Step 5 (risk gating) — preview is always non-mutating
 * and idempotency only matters once a SkillRun row is about to be written.
 * {@link ValidationMode#EXECUTE} runs all five steps.
 */
@Slf4j
@Component
public class SkillRequestValidator {

    private final AuraBotSkillRegistry registry;
    private final SkillRunRepository repository;
    // Redis-backed stores are conditional on StringRedisTemplate
    // (RedisOptionalConfig). Single-node deployments without redis still
    // boot; runtime calls that need these stores fail-open (idempotency)
    // or fail-closed (preview token) with a clear error.
    private final ObjectProvider<SkillIdempotencyStore> idempotencyStoreProvider;
    private final ObjectProvider<PreviewTokenStore> previewTokenStoreProvider;
    private final ObjectMapper objectMapper;

    public SkillRequestValidator(AuraBotSkillRegistry registry,
                                 SkillRunRepository repository,
                                 ObjectProvider<SkillIdempotencyStore> idempotencyStoreProvider,
                                 ObjectProvider<PreviewTokenStore> previewTokenStoreProvider,
                                 ObjectMapper objectMapper) {
        this.registry = registry;
        this.repository = repository;
        this.idempotencyStoreProvider = idempotencyStoreProvider;
        this.previewTokenStoreProvider = previewTokenStoreProvider;
        this.objectMapper = objectMapper;
    }

    public enum ValidationMode {
        DRY_RUN,
        EXECUTE
    }

    /**
     * Run the five-step pipeline.
     *
     * @param req       wire request (skillName / params / idempotencyKey / previewToken).
     * @param userPerms permissions held by the caller (may be {@code null} → treated as empty).
     * @param tenantId  resolved tenant id (controller pulls from MetaContext before calling).
     * @param mode      DRY_RUN skips Step 3 + Step 5; EXECUTE runs all five.
     * @return a {@link ValidatedRequest}. When {@link ValidatedRequest#shortCircuit()}
     *         is non-empty the controller MUST return that result verbatim with
     *         HTTP 200 + {@code body.code = IDEMPOTENCY_REPLAY} — the executor
     *         is bypassed.
     * @throws SkillSpiException on any of the five typed validation failures.
     */
    public ValidatedRequest validate(SkillRequest req,
                                     Set<String> userPerms,
                                     long tenantId,
                                     ValidationMode mode) {
        if (req == null) {
            throw new SkillSpiException(SkillErrorCode.PARAMS_INVALID,
                    "request body is required");
        }
        Set<String> perms = userPerms == null ? Collections.emptySet() : userPerms;

        // ── Step 1: skill exists ───────────────────────────────────────────
        AuraBotSkill skill = registry.get(req.getSkillName())
                .orElseThrow(() -> new SkillSpiException(
                        SkillErrorCode.SKILL_NOT_FOUND,
                        "skill not found: " + req.getSkillName()));

        // ── Step 2: permission ─────────────────────────────────────────────
        Set<String> required = skill.requiredPermissions() == null
                ? Collections.emptySet()
                : skill.requiredPermissions();
        if (!perms.containsAll(required)) {
            Set<String> missing = required.stream()
                    .filter(p -> !perms.contains(p))
                    .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
            throw new SkillSpiException(SkillErrorCode.PERMISSION_DENIED,
                    "missing required permissions: " + missing);
        }

        // ── Step 3: idempotency replay (EXECUTE only) ──────────────────────
        String candidatePid = "usr_" + UniqueIdGenerator.generate();
        Optional<SkillResult> shortCircuit = Optional.empty();
        if (mode == ValidationMode.EXECUTE) {
            shortCircuit = checkIdempotencyReplay(req, tenantId, candidatePid);
            if (shortCircuit.isPresent()) {
                // Replay path — skip steps 4 & 5; controller serves prior result.
                return new ValidatedRequest(skill, req, tenantId, candidatePid,
                        shortCircuit, Optional.empty());
            }
        }

        // ── Step 4: JSON-schema validation ─────────────────────────────────
        validateParamsSchema(skill, req);

        // ── Step 5: risk gating (EXECUTE only, ≥ MEDIUM) ───────────────────
        Optional<PreviewTokenStore.PreviewPayload> preview = Optional.empty();
        if (mode == ValidationMode.EXECUTE && skill.riskLevel().atLeast(RiskLevel.MEDIUM)) {
            preview = Optional.of(consumePreviewToken(skill, req));
        }

        return new ValidatedRequest(skill, req, tenantId, candidatePid,
                Optional.empty(), preview);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Step 3 helper

    private Optional<SkillResult> checkIdempotencyReplay(SkillRequest req,
                                                         long tenantId,
                                                         String candidatePid) {
        String idemKey = req.getIdempotencyKey();
        if (idemKey == null || idemKey.isBlank()) {
            // No client-supplied key → no dedup window; proceed normal path.
            return Optional.empty();
        }
        SkillIdempotencyStore idempotencyStore = idempotencyStoreProvider.getIfAvailable();
        if (idempotencyStore == null) {
            // Redis not configured — fail-open per plan §R2 (DB unique
            // index on (tenant, skill, idem) is the ultimate dedup safety net).
            return Optional.empty();
        }
        try {
            Optional<String> prior = idempotencyStore.tryClaim(
                    tenantId, req.getSkillName(), idemKey, candidatePid);
            if (prior.isEmpty()) {
                // Fresh claim — no replay.
                return Optional.empty();
            }
            // Duplicate claim — look up the canonical row from PG.
            Optional<SkillRun> dbRow = repository.findByIdempotency(
                    tenantId, req.getSkillName(), idemKey,
                    SkillRunRepository.DEFAULT_IDEMPOTENCY_WINDOW);
            if (dbRow.isPresent()) {
                return Optional.of(buildReplayResult(dbRow.get()));
            }
            // Stale / orphan claim — Redis has a pid the DB never committed.
            // Release so the next caller can win cleanly; proceed normal path.
            log.warn("Idempotency claim orphan tenant={} skill={} idem={} priorPid={} — releasing",
                    tenantId, req.getSkillName(), idemKey, prior.get());
            idempotencyStore.release(tenantId, req.getSkillName(), idemKey);
            return Optional.empty();
        } catch (DataAccessResourceFailureException e) {
            // P2 catch-exception-pattern: Redis unreachable → fail-open per
            // plan §R2. The DB unique index on (tenant, skill, idem) is the
            // ultimate safety net for actual duplicate inserts.
            log.warn("Redis unavailable, skipping idempotency check for skill={} tenant={}",
                    req.getSkillName(), tenantId, e);
            return Optional.empty();
        }
    }

    /**
     * Build a replay {@link SkillResult} from the canonical {@link SkillRun}
     * row. Status is {@link SkillResult.Status#SUCCESS}; the controller-side
     * envelope additionally tags the response body with
     * {@link SkillErrorCode#IDEMPOTENCY_REPLAY} via HTTP 200.
     */
    private SkillResult buildReplayResult(SkillRun row) {
        return SkillResult.builder()
                .status(SkillResult.Status.SUCCESS)
                .skillName(row.getSkillName())
                .payload(row.getAfterSnapshot())
                .undoToken(row.getUndoToken())
                .batchId(row.getBatchId())
                .riskLevel(row.getRiskLevel() == null
                        ? null
                        : RiskLevel.fromCode(row.getRiskLevel()))
                .build();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Step 4 helper

    private void validateParamsSchema(AuraBotSkill skill, SkillRequest req) {
        JsonSchema schema = registry.getCompiledSchema(skill.name())
                .orElseThrow(() -> new IllegalStateException(
                        "no compiled schema cached for skill " + skill.name()
                                + " — registry bootstrap inconsistency"));

        JsonNode params = req.getParams();
        if (params == null) {
            // networknt validator accepts a null reference for "no input",
            // but the SPI contract requires an explicit object — fail fast
            // so the FE error message is precise.
            params = objectMapper.nullNode();
        }
        Set<ValidationMessage> errors = schema.validate(params);
        if (!errors.isEmpty()) {
            ValidationMessage first = errors.iterator().next();
            // ValidationMessage#getInstanceLocation() returns the JSON pointer
            // ("$.field" or "/field"); embed it in the message and stash it on
            // the exception so the controller can populate fieldPath.
            String pointer = first.getInstanceLocation() == null
                    ? null
                    : first.getInstanceLocation().toString();
            String detail = first.getMessage();
            String composed = pointer == null || pointer.isBlank()
                    ? "params invalid: " + detail
                    : "params invalid at " + pointer + ": " + detail;
            throw new SkillSpiException(SkillErrorCode.PARAMS_INVALID, composed, pointer);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Step 5 helper

    private PreviewTokenStore.PreviewPayload consumePreviewToken(AuraBotSkill skill,
                                                                 SkillRequest req) {
        String token = req.getPreviewToken();
        if (token == null || token.isBlank()) {
            throw new SkillSpiException(SkillErrorCode.CONFIRM_REQUIRED,
                    "preview token required for risk=" + skill.riskLevel().code());
        }
        PreviewTokenStore previewTokenStore = previewTokenStoreProvider.getIfAvailable();
        if (previewTokenStore == null) {
            // Redis not configured — preview-token handoff requires a shared
            // store across nodes. Fail-closed (this is risk MEDIUM+).
            throw new SkillSpiException(SkillErrorCode.PREVIEW_TOKEN_INVALID,
                    "preview token store unavailable (redis not configured)");
        }
        return previewTokenStore.consume(token, skill.name(), req.getParams())
                .orElseThrow(() -> new SkillSpiException(
                        SkillErrorCode.PREVIEW_TOKEN_INVALID,
                        "preview token invalid, expired, or params mismatch"));
    }

    // ────────────────────────────────────────────────────────────────────────
    // Output type

    /**
     * Validator output. The controller (B5) reads:
     * <ul>
     *     <li>{@link #shortCircuit()} — if non-empty, return that
     *         {@link SkillResult} as HTTP 200 with
     *         {@code body.code = IDEMPOTENCY_REPLAY} and DO NOT invoke
     *         {@link AuraBotSkill#execute}.</li>
     *     <li>{@link #preview()} — if non-empty (EXECUTE + ≥ MEDIUM risk),
     *         pass to the executor / persist into the SkillRun snapshot.</li>
     *     <li>{@link #candidatePid()} — pre-allocated pid the executor will
     *         persist on a successful insert (must match the pid claimed in
     *         the Redis ledger so a crash between claim and commit can be
     *         detected via orphan-release).</li>
     * </ul>
     */
    public static final class ValidatedRequest {
        private final AuraBotSkill skill;
        private final SkillRequest request;
        private final long tenantId;
        private final String candidatePid;
        private final Optional<SkillResult> shortCircuit;
        private final Optional<PreviewTokenStore.PreviewPayload> preview;

        ValidatedRequest(AuraBotSkill skill,
                         SkillRequest request,
                         long tenantId,
                         String candidatePid,
                         Optional<SkillResult> shortCircuit,
                         Optional<PreviewTokenStore.PreviewPayload> preview) {
            this.skill = skill;
            this.request = request;
            this.tenantId = tenantId;
            this.candidatePid = candidatePid;
            this.shortCircuit = shortCircuit;
            this.preview = preview;
        }

        public AuraBotSkill skill() { return skill; }
        public SkillRequest request() { return request; }
        public long tenantId() { return tenantId; }
        public String candidatePid() { return candidatePid; }
        public Optional<SkillResult> shortCircuit() { return shortCircuit; }
        public Optional<PreviewTokenStore.PreviewPayload> preview() { return preview; }

        /** Convenience for callers that want a single map for structured logging. */
        public Map<String, Object> toLogContext() {
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("skill", skill.name());
            ctx.put("tenantId", tenantId);
            ctx.put("candidatePid", candidatePid);
            ctx.put("shortCircuit", shortCircuit.isPresent());
            ctx.put("hasPreview", preview.isPresent());
            return ctx;
        }
    }
}
