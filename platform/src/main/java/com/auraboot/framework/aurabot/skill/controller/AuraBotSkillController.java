package com.auraboot.framework.aurabot.skill.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.aurabot.skill.AuraBotSkill;
import com.auraboot.framework.aurabot.skill.AuraBotSkillRegistry;
import com.auraboot.framework.aurabot.skill.PreviewTokenStore;
import com.auraboot.framework.aurabot.skill.RiskLevel;
import com.auraboot.framework.aurabot.skill.SkillIdempotencyStore;
import com.auraboot.framework.aurabot.skill.SkillMeta;
import com.auraboot.framework.aurabot.skill.SkillRequest;
import com.auraboot.framework.aurabot.skill.SkillRequestValidator;
import com.auraboot.framework.aurabot.skill.SkillRequestValidator.ValidatedRequest;
import com.auraboot.framework.aurabot.skill.SkillRequestValidator.ValidationMode;
import com.auraboot.framework.aurabot.skill.SkillResult;
import com.auraboot.framework.aurabot.skill.SkillRunRepository;
import com.auraboot.framework.aurabot.skill.SkillRunStatus;
import com.auraboot.framework.aurabot.skill.entity.SkillRun;
import com.auraboot.framework.aurabot.skill.error.SkillErrorCode;
import com.auraboot.framework.aurabot.skill.error.SkillSpiException;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.permission.util.PermissionCodeAliasResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * REST surface for the AuraBot Skill SPI (Plan §Step 7 / SPI Contract §2).
 *
 * <p><strong>Endpoint table.</strong>
 * <ul>
 *     <li>{@code GET  /api/aurabot/v2/skills} — discovery, with ETag.</li>
 *     <li>{@code POST /api/aurabot/v2/skill/dry-run} — non-mutating preview, mints preview token.</li>
 *     <li>{@code POST /api/aurabot/v2/skill/execute} — commit, idempotent via Redis + DB unique index.</li>
 *     <li>{@code POST /api/aurabot/v2/skill/undo} — single-row reversal.</li>
 *     <li>{@code POST /api/aurabot/v2/skill/batch-undo} — best-effort batch reversal.</li>
 *     <li>{@code GET  /api/aurabot/v2/stream/{traceId}} — placeholder; B7 will wire real SSE.</li>
 * </ul>
 *
 * <p><strong>Validator hand-off.</strong> Every mutating endpoint funnels through
 * {@link SkillRequestValidator#validate}; a non-empty
 * {@link ValidatedRequest#shortCircuit()} short-circuits the executor and yields
 * an HTTP 200 envelope tagged with {@link SkillErrorCode#IDEMPOTENCY_REPLAY}.
 *
 * <p><strong>Audit.</strong> No domain-level audit service is wired yet (B5
 * scope intentionally does not introduce one); the controller logs structured
 * {@code AUDIT skill=... status=...} lines so the operations channel is in
 * place for B6/B7 to upgrade to a service.
 */
@Slf4j
@RestController
@RequestMapping("/api/aurabot/v2")
public class AuraBotSkillController {

    /** Plan §11 contract: undo tokens expire 30 minutes after creation. */
    static final Duration UNDO_WINDOW = Duration.ofMinutes(30);

    private final AuraBotSkillRegistry registry;
    private final SkillRequestValidator validator;
    private final SkillRunRepository repository;
    private final SkillIdempotencyStore idempotencyStore;
    private final PreviewTokenStore previewTokenStore;
    private final UserPermissionService userPermissionService;
    private final PermissionMapper permissionMapper;
    private final ObjectMapper objectMapper;

    public AuraBotSkillController(AuraBotSkillRegistry registry,
                                  SkillRequestValidator validator,
                                  SkillRunRepository repository,
                                  SkillIdempotencyStore idempotencyStore,
                                  PreviewTokenStore previewTokenStore,
                                  UserPermissionService userPermissionService,
                                  PermissionMapper permissionMapper,
                                  ObjectMapper objectMapper) {
        this.registry = registry;
        this.validator = validator;
        this.repository = repository;
        this.idempotencyStore = idempotencyStore;
        this.previewTokenStore = previewTokenStore;
        this.userPermissionService = userPermissionService;
        this.permissionMapper = permissionMapper;
        this.objectMapper = objectMapper;
    }

    // ────────────────────────────────────────────────────────────────────────
    // 1. GET /skills (discovery, ETag)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Discovery endpoint. Returns the subset of skills visible to the calling
     * user (permission subset check). ETag is computed over the SHA-1 of the
     * serialised payload so an unchanged catalogue replies 304 cheaply.
     */
    @GetMapping("/skills")
    public ResponseEntity<ApiResponse<List<SkillMeta>>> listSkills(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        Set<String> perms = resolveCurrentUserPermissions();
        List<SkillMeta> metas = registry.list(perms);

        String etag = computeEtag(metas);
        if (ifNoneMatch != null && ifNoneMatch.equals(etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).body(ApiResponse.success(metas));
    }

    // ────────────────────────────────────────────────────────────────────────
    // 2. POST /skill/dry-run
    // ────────────────────────────────────────────────────────────────────────

    @PostMapping("/skill/dry-run")
    public ApiResponse<SkillResult> dryRun(@RequestBody SkillRequest req) {
        Long tenantIdBoxed = MetaContext.getCurrentTenantId();
        long tenantId = tenantIdBoxed == null ? 0L : tenantIdBoxed;
        Set<String> perms = resolveCurrentUserPermissions();

        ValidatedRequest validated = validator.validate(req, perms, tenantId, ValidationMode.DRY_RUN);
        AuraBotSkill skill = validated.skill();

        SkillResult preview = skill.dryRun(req);
        // Mint preview token regardless of risk level — execute path will only
        // require it when risk ≥ MEDIUM (validator enforces). LOW skills get a
        // token for free; that's harmless and keeps wire shape uniform.
        String token = previewTokenStore.save(tenantId, skill.name(), req.getParams(),
                preview == null ? null : objectMapper.valueToTree(preview.getPayload()));

        SkillResult envelope = SkillResult.builder()
                .status(SkillResult.Status.NEEDS_CONFIRM)
                .skillName(skill.name())
                .preview(preview == null ? null : preview.getPayload())
                .previewToken(token)
                .riskLevel(skill.riskLevel())
                .build();

        log.info("AUDIT skill={} action=dry-run tenantId={} userId={} riskLevel={}",
                skill.name(), tenantId, MetaContext.getCurrentUserId(), skill.riskLevel().code());
        return ApiResponse.success(envelope);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 3. POST /skill/execute
    // ────────────────────────────────────────────────────────────────────────

    @PostMapping("/skill/execute")
    public ApiResponse<SkillResult> execute(@RequestBody SkillRequest req) {
        Long tenantIdBoxed = MetaContext.getCurrentTenantId();
        long tenantId = tenantIdBoxed == null ? 0L : tenantIdBoxed;
        Set<String> perms = resolveCurrentUserPermissions();

        ValidatedRequest validated = validator.validate(req, perms, tenantId, ValidationMode.EXECUTE);

        // Replay path — DB row already exists; serve the prior result verbatim.
        if (validated.shortCircuit().isPresent()) {
            SkillResult prior = validated.shortCircuit().get();
            log.info("AUDIT skill={} action=execute tenantId={} userId={} status=replay",
                    validated.skill().name(), tenantId, MetaContext.getCurrentUserId());
            return wrapReplay(prior);
        }

        AuraBotSkill skill = validated.skill();
        // Hand the decoded preview envelope to the executor via a separate
        // contract: skills don't see this directly today (B6/B7 may pass it
        // through), so we persist it into the SkillRun.beforeSnapshot only.
        JsonNode beforeSnapshot = validated.preview()
                .map(p -> p.payload())
                .orElse(null);

        SkillResult result;
        try {
            result = skill.execute(req);
        } catch (SkillSpiException e) {
            // Typed validation/execution failures bubble up to B6 handler.
            throw e;
        } catch (RuntimeException e) {
            log.error("Skill execute failed skill={} tenantId={}", skill.name(), tenantId, e);
            throw new SkillSpiException(SkillErrorCode.SKILL_INTERNAL_ERROR,
                    "skill execution failed: " + e.getMessage(), null, e);
        }

        // Persist the canonical row anchored to the validator's pre-allocated
        // pid so the Redis idempotency claim and the DB row agree.
        SkillRun row = buildSkillRun(validated, req, result, beforeSnapshot, tenantId);
        try {
            repository.insert(row);
        } catch (DuplicateKeyException dup) {
            // Lost the (tenant, skill, idem) race against a peer node — fall
            // back to the canonical row that the winner committed. R2 path:
            // Redis fail-open + DB unique index is the ultimate guard.
            log.warn("Skill execute hit DB unique constraint — replaying winning row skill={} tenantId={} idem={}",
                    skill.name(), tenantId, req.getIdempotencyKey());
            SkillRun winner = repository.findByIdempotency(tenantId, skill.name(),
                            req.getIdempotencyKey(), SkillRunRepository.DEFAULT_IDEMPOTENCY_WINDOW)
                    .orElseThrow(() -> new SkillSpiException(SkillErrorCode.SKILL_INTERNAL_ERROR,
                            "duplicate insert with no winner row", null, dup));
            return wrapReplay(replayResultOf(winner));
        }

        log.info("AUDIT skill={} action=execute tenantId={} userId={} pid={} status={}",
                skill.name(), tenantId, MetaContext.getCurrentUserId(), row.getPid(), row.getStatus());
        return ApiResponse.success(result);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 4. POST /skill/undo
    // ────────────────────────────────────────────────────────────────────────

    @PostMapping("/skill/undo")
    public ApiResponse<SkillResult> undo(@RequestBody UndoRequest body) {
        if (body == null || body.getUndoToken() == null || body.getUndoToken().isBlank()) {
            throw new SkillSpiException(SkillErrorCode.PARAMS_INVALID,
                    "undoToken is required", "/undoToken");
        }
        SkillRun row = repository.findByUndoToken(body.getUndoToken())
                .orElseThrow(() -> new SkillSpiException(SkillErrorCode.UNDO_EXPIRED,
                        "undo token not found"));
        if (row.getCreatedAt() == null
                || row.getCreatedAt().plus(UNDO_WINDOW).isBefore(Instant.now())) {
            throw new SkillSpiException(SkillErrorCode.UNDO_EXPIRED,
                    "undo token expired (>30min)");
        }
        if (SkillRunStatus.UNDONE.code().equals(row.getStatus())) {
            // Already undone — surface as expired so the FE doesn't double-undo.
            throw new SkillSpiException(SkillErrorCode.UNDO_EXPIRED,
                    "skill run already undone");
        }

        AuraBotSkill skill = registry.get(row.getSkillName())
                .orElseThrow(() -> new SkillSpiException(SkillErrorCode.SKILL_NOT_FOUND,
                        "skill not found: " + row.getSkillName()));

        SkillResult result;
        try {
            result = skill.undo(body.getUndoToken());
        } catch (UnsupportedOperationException e) {
            // Skill doesn't override undo() — surface as a typed 500 so the FE
            // gets a clear "this skill is not reversible" envelope. Preferred
            // over silently returning a SkillResult.error so B6's central
            // handler also catches it.
            throw new SkillSpiException(SkillErrorCode.SKILL_INTERNAL_ERROR,
                    "skill " + skill.name() + " is not reversible", null, e);
        } catch (RuntimeException e) {
            log.error("Skill undo failed skill={} pid={}", skill.name(), row.getPid(), e);
            throw new SkillSpiException(SkillErrorCode.SKILL_INTERNAL_ERROR,
                    "skill undo failed: " + e.getMessage(), null, e);
        }

        repository.markUndone(row.getPid());

        log.info("AUDIT skill={} action=undo tenantId={} userId={} pid={}",
                skill.name(), row.getTenantId(), MetaContext.getCurrentUserId(), row.getPid());
        return ApiResponse.success(result);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 5. POST /skill/batch-undo
    // ────────────────────────────────────────────────────────────────────────

    @PostMapping("/skill/batch-undo")
    public ApiResponse<SkillResult> batchUndo(@RequestBody BatchUndoRequest body) {
        if (body == null || body.getBatchId() == null || body.getBatchId().isBlank()) {
            throw new SkillSpiException(SkillErrorCode.PARAMS_INVALID,
                    "batchId is required", "/batchId");
        }
        Long tenantIdBoxed = MetaContext.getCurrentTenantId();
        Long tenantId = tenantIdBoxed == null ? Long.valueOf(0L) : tenantIdBoxed;

        List<SkillRun> rows = repository.findByBatchId(tenantId, body.getBatchId());
        // Reverse so newest-first: undo in opposite order of original execution.
        Collections.reverse(rows);

        List<String> undonePids = new ArrayList<>();
        List<Map<String, String>> failed = new ArrayList<>();

        for (SkillRun row : rows) {
            if (SkillRunStatus.UNDONE.code().equals(row.getStatus())) {
                continue;
            }
            try {
                AuraBotSkill skill = registry.get(row.getSkillName())
                        .orElseThrow(() -> new SkillSpiException(SkillErrorCode.SKILL_NOT_FOUND,
                                "skill not found: " + row.getSkillName()));
                if (row.getCreatedAt() == null
                        || row.getCreatedAt().plus(UNDO_WINDOW).isBefore(Instant.now())) {
                    throw new SkillSpiException(SkillErrorCode.UNDO_EXPIRED,
                            "undo expired for pid=" + row.getPid());
                }
                skill.undo(row.getUndoToken());
                repository.markUndone(row.getPid());
                undonePids.add(row.getPid());
            } catch (RuntimeException e) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("pid", row.getPid());
                entry.put("reason", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                failed.add(entry);
                log.warn("Batch-undo entry failed pid={} skill={}: {}",
                        row.getPid(), row.getSkillName(), e.getMessage());
            }
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("undone", objectMapper.valueToTree(undonePids));
        payload.set("failed", objectMapper.valueToTree(failed));

        SkillResult result = SkillResult.builder()
                .status(failed.isEmpty() ? SkillResult.Status.SUCCESS : SkillResult.Status.ERROR)
                .skillName(null)
                .batchId(body.getBatchId())
                .payload(payload)
                .build();

        log.info("AUDIT action=batch-undo tenantId={} userId={} batchId={} undone={} failed={}",
                tenantId, MetaContext.getCurrentUserId(), body.getBatchId(),
                undonePids.size(), failed.size());
        return ApiResponse.success(result);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 6. GET /stream/{traceId} — placeholder
    // ────────────────────────────────────────────────────────────────────────

    @GetMapping("/stream/{traceId}")
    public ResponseEntity<ApiResponse<Void>> stream(@PathVariable String traceId) {
        log.debug("Streaming endpoint placeholder hit traceId={} — returning 503", traceId);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(503, "streaming not implemented yet"));
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    /** Resolve current user's permission codes via existing platform path. */
    Set<String> resolveCurrentUserPermissions() {
        Long userId = MetaContext.getCurrentUserId();
        if (userId == null) {
            return Collections.emptySet();
        }
        Set<Long> ids = userPermissionService.getUserPermissionIds(userId);
        if (ids == null || ids.isEmpty()) {
            return Collections.emptySet();
        }
        List<Permission> perms = permissionMapper.findByIds(new ArrayList<>(ids));
        Set<String> codes = perms.stream()
                .map(Permission::getCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        return PermissionCodeAliasResolver.expandCodes(codes);
    }

    /** SHA-1 of serialised meta list. Quote-wrapped per RFC 7232. */
    String computeEtag(List<SkillMeta> metas) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(metas);
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(json);
            return "\"" + HexFormat.of().formatHex(digest) + "\"";
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is mandatory in every JRE — propagate as unchecked.
            throw new IllegalStateException("SHA-1 unavailable on this JVM", e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise SkillMeta list for ETag", e);
        }
    }

    /**
     * Wrap a replay {@link SkillResult} as an HTTP 200 envelope tagged with
     * {@link SkillErrorCode#IDEMPOTENCY_REPLAY} so the FE can distinguish a
     * replay from a fresh execute without inspecting headers.
     */
    private ApiResponse<SkillResult> wrapReplay(SkillResult prior) {
        ApiResponse<SkillResult> r = ApiResponse.success(prior);
        // ApiResponse exposes ResponseCode-derived code; overwrite via explicit
        // setter so the FE sees the SPI-specific replay marker.
        r.setCode(SkillErrorCode.IDEMPOTENCY_REPLAY.code());
        r.setMessage("idempotency replay");
        return r;
    }

    /** Reconstruct a {@link SkillResult} from a persisted SkillRun (replay). */
    private SkillResult replayResultOf(SkillRun row) {
        return SkillResult.builder()
                .status(SkillResult.Status.SUCCESS)
                .skillName(row.getSkillName())
                .payload(row.getAfterSnapshot())
                .undoToken(row.getUndoToken())
                .batchId(row.getBatchId())
                .riskLevel(row.getRiskLevel() == null ? null : RiskLevel.fromCode(row.getRiskLevel()))
                .build();
    }

    /** Compose the persistence row from validator output + executor result. */
    private SkillRun buildSkillRun(ValidatedRequest validated,
                                   SkillRequest req,
                                   SkillResult result,
                                   JsonNode beforeSnapshot,
                                   long tenantId) {
        AuraBotSkill skill = validated.skill();
        SkillRun row = new SkillRun();
        row.setPid(validated.candidatePid());
        row.setTenantId(tenantId);
        row.setSkillName(skill.name());
        row.setParamsJson(req.getParams());
        row.setBeforeSnapshot(beforeSnapshot);
        row.setAfterSnapshot(result == null || result.getPayload() == null
                ? null
                : objectMapper.valueToTree(result.getPayload()));
        row.setIdempotencyKey(req.getIdempotencyKey());
        row.setUndoToken(result == null ? null : result.getUndoToken());
        row.setBatchId(result == null ? null : result.getBatchId());
        row.setStatus(SkillRunStatus.SUCCESS.code());
        row.setRiskLevel(skill.riskLevel().code());
        row.setCreatedBy(stringOf(MetaContext.getCurrentUserPid(), MetaContext.getCurrentUserId()));
        row.setCreatedAt(Instant.now());
        row.setDeletedFlag(Boolean.FALSE);
        return row;
    }

    private String stringOf(String pid, Long fallback) {
        if (pid != null && !pid.isBlank()) {
            return pid;
        }
        return fallback == null ? null : String.valueOf(fallback);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Wire DTOs (controller-private)
    // ────────────────────────────────────────────────────────────────────────

    /** Body for {@code POST /skill/undo}. */
    public static class UndoRequest {
        private String undoToken;

        public String getUndoToken() { return undoToken; }
        public void setUndoToken(String undoToken) { this.undoToken = undoToken; }
    }

    /** Body for {@code POST /skill/batch-undo}. */
    public static class BatchUndoRequest {
        private String batchId;

        public String getBatchId() { return batchId; }
        public void setBatchId(String batchId) { this.batchId = batchId; }
    }
}
