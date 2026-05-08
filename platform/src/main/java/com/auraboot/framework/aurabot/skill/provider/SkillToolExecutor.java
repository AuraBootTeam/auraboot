package com.auraboot.framework.aurabot.skill.provider;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.aurabot.skill.AuraBotSkill;
import com.auraboot.framework.aurabot.skill.AuraBotSkillRegistry;
import com.auraboot.framework.aurabot.skill.PreviewTokenStore;
import com.auraboot.framework.aurabot.skill.RiskLevel;
import com.auraboot.framework.aurabot.skill.SkillRequest;
import com.auraboot.framework.aurabot.skill.SkillRequestValidator;
import com.auraboot.framework.aurabot.skill.SkillRequestValidator.ValidatedRequest;
import com.auraboot.framework.aurabot.skill.SkillRequestValidator.ValidationMode;
import com.auraboot.framework.aurabot.skill.SkillResult;
import com.auraboot.framework.aurabot.skill.error.SkillErrorCode;
import com.auraboot.framework.aurabot.skill.error.SkillSpiException;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Chat-aware dispatch shim over the AuraBot Skill SPI (Plan §C-5 Task 3 §4.2).
 *
 * <p>The platform-level {@link AuraBotSkill} contract is risk-graded: LOW
 * skills run inline, MEDIUM+ skills require an explicit preview-then-confirm
 * round-trip. The V2 chat tool path needs the same gating but cannot expose
 * raw HTTP {@code /skill/dry-run} + {@code /skill/execute} to the LLM —
 * inside a chat turn we want a single "tool call" abstraction that returns
 * either a finished result or a {@code PREVIEW_PENDING} envelope the FE can
 * render as a confirm card.
 *
 * <p>This executor wraps {@link SkillRequestValidator},
 * {@link AuraBotSkillRegistry} and {@link PreviewTokenStore} into two methods:
 * <ul>
 *     <li>{@link #dispatch(String, SkillRequest)} — risk-aware. LOW: re-validates
 *         in EXECUTE mode and runs {@link AuraBotSkill#execute}. MEDIUM+:
 *         runs {@link AuraBotSkill#dryRun} and mints a preview token.</li>
 *     <li>{@link #confirm(String, SkillRequest, String)} — consumes a previously
 *         minted token and runs execute. Token mismatch (unknown skill, params
 *         hash drift) → typed {@link SkillErrorCode#PREVIEW_TOKEN_INVALID}.</li>
 * </ul>
 *
 * <p>The Controller path ({@code AuraBotSkillController}) keeps doing direct
 * validator + executor work; this shim is intentionally additive — chat
 * (Task 4 {@code ChatToolExecutor} branch) calls into here without touching
 * the existing REST surface.
 *
 * <p>Permissions are resolved from {@link MetaContext} via the same
 * {@link UserPermissionService}+{@link PermissionMapper} chain the controller
 * uses, so a chat-side dispatch sees the exact same RBAC posture as the FE
 * picker. The validator does the actual subset check.
 */
@Slf4j
@Component
public class SkillToolExecutor {

    private final AuraBotSkillRegistry registry;
    private final SkillRequestValidator validator;
    private final PreviewTokenStore previewTokenStore;
    private final UserPermissionService userPermissionService;
    private final PermissionMapper permissionMapper;
    private final ObjectMapper objectMapper;

    public SkillToolExecutor(AuraBotSkillRegistry registry,
                             SkillRequestValidator validator,
                             PreviewTokenStore previewTokenStore,
                             UserPermissionService userPermissionService,
                             PermissionMapper permissionMapper,
                             ObjectMapper objectMapper) {
        this.registry = registry;
        this.validator = validator;
        this.previewTokenStore = previewTokenStore;
        this.userPermissionService = userPermissionService;
        this.permissionMapper = permissionMapper;
        this.objectMapper = objectMapper;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Dispatch — LOW inline / MEDIUM+ preview-pending
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Risk-aware dispatch. Validates in DRY_RUN mode first (so unknown skill /
     * permission denial / schema mismatch surface as typed errors before any
     * mutation), then either runs execute inline (LOW) or returns a
     * preview-pending envelope (MEDIUM+).
     */
    public DispatchOutcome dispatch(String skillName, SkillRequest input) {
        SkillRequest req = buildRequest(skillName, input);
        long tenantId = currentTenantId();
        Set<String> perms = resolvePermissionsFromContext();

        // First pass: DRY_RUN validation. Catches missing skill / permission
        // gaps / schema violations without consulting Redis idempotency or
        // burning a preview token. The validator returns a ValidatedRequest
        // we partially reuse below — for LOW we re-validate in EXECUTE mode
        // so the idempotency claim runs through the canonical pipeline.
        ValidatedRequest dry = validator.validate(req, perms, tenantId, ValidationMode.DRY_RUN);
        AuraBotSkill skill = dry.skill();
        RiskLevel risk = skill.riskLevel();

        if (!risk.atLeast(RiskLevel.MEDIUM)) {
            // LOW path — re-validate in EXECUTE mode so the Redis idempotency
            // ledger is claimed under the canonical (tenant, skill, idem) key.
            // Replay short-circuit (DB row exists) is honoured: surface as
            // EXECUTED with the prior result so the chat layer renders the
            // same outcome on re-run.
            ValidatedRequest exec = validator.validate(req, perms, tenantId, ValidationMode.EXECUTE);
            if (exec.shortCircuit().isPresent()) {
                return DispatchOutcome.executed(exec.shortCircuit().get(), risk);
            }
            SkillResult result = skill.execute(req);
            return DispatchOutcome.executed(result, risk);
        }

        // MEDIUM+ path — run dryRun, mint preview token, return PREVIEW_PENDING.
        // The chat tool layer (Task 4 ChatToolExecutor branch) wraps this into
        // a tool-message envelope so the FE renders a confirm card.
        SkillResult preview = skill.dryRun(req);
        JsonNode previewPayload = preview == null || preview.getPayload() == null
                ? null
                : objectMapper.valueToTree(preview.getPayload());
        String token = previewTokenStore.save(tenantId, skill.name(), req.getParams(), previewPayload);

        log.info("AUDIT skill={} action=chat-dispatch tenantId={} risk={} outcome=PREVIEW_PENDING",
                skill.name(), tenantId, risk.code());
        return DispatchOutcome.pending(preview, token, risk);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Confirm — consume preview token, run execute
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Consume a preview token minted by an earlier {@link #dispatch} call and
     * run the skill's commit path. Token validation is delegated to
     * {@link PreviewTokenStore#consume} which enforces skill-name + params-hash
     * match; any drift surfaces as {@link SkillErrorCode#PREVIEW_TOKEN_INVALID}.
     *
     * <p>This entry deliberately does NOT re-run the validator — the dispatch
     * step already gated permission / schema, and the token's params-hash is
     * the second-line guard against parameter tampering between preview and
     * confirm. Routing back through the validator would also re-claim the
     * Redis idempotency ledger which the controller path owns.
     */
    public DispatchOutcome confirm(String skillName, SkillRequest input, String previewToken) {
        SkillRequest req = buildRequest(skillName, input);
        if (req.getPreviewToken() == null || req.getPreviewToken().isBlank()) {
            req.setPreviewToken(previewToken);
        }

        AuraBotSkill skill = registry.get(skillName)
                .orElseThrow(() -> new SkillSpiException(SkillErrorCode.SKILL_NOT_FOUND,
                        "skill not found: " + skillName));

        previewTokenStore.consume(previewToken, skillName, req.getParams())
                .orElseThrow(() -> new SkillSpiException(SkillErrorCode.PREVIEW_TOKEN_INVALID,
                        "preview token invalid, expired, or params mismatch"));

        SkillResult result = skill.execute(req);

        log.info("AUDIT skill={} action=chat-confirm tenantId={} risk={} outcome=EXECUTED",
                skill.name(), currentTenantIdOrZero(), skill.riskLevel().code());
        return DispatchOutcome.executed(result, skill.riskLevel());
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Materialise a chat-side request: copy skillName/params from {@code input}
     * (which the chat layer assembles from the tool-call arguments), and
     * stamp a stable idempotency key so accidental retries during a turn
     * don't double-execute. The {@code chat-} prefix is operationally
     * distinguishable from REST-side keys in audit logs.
     */
    private SkillRequest buildRequest(String skillName, SkillRequest input) {
        SkillRequest.SkillRequestBuilder b = SkillRequest.builder()
                .skillName(skillName);
        if (input != null) {
            b.params(input.getParams());
            b.context(input.getContext());
            b.previewToken(input.getPreviewToken());
            b.confirmText(input.getConfirmText());
            b.idempotencyKey(input.getIdempotencyKey() == null || input.getIdempotencyKey().isBlank()
                    ? "chat-" + UUID.randomUUID()
                    : input.getIdempotencyKey());
        } else {
            b.idempotencyKey("chat-" + UUID.randomUUID());
        }
        return b.build();
    }

    private long currentTenantId() {
        Long t = MetaContext.getCurrentTenantId();
        if (t == null) {
            throw new SkillSpiException(SkillErrorCode.SKILL_INTERNAL_ERROR,
                    "no tenant context for chat skill dispatch", null);
        }
        return t;
    }

    private long currentTenantIdOrZero() {
        Long t = MetaContext.getCurrentTenantId();
        return t == null ? 0L : t;
    }

    private Set<String> resolvePermissionsFromContext() {
        Long userId = MetaContext.getCurrentUserId();
        if (userId == null) {
            return Collections.emptySet();
        }
        Set<Long> ids = userPermissionService.getUserPermissionIds(userId);
        if (ids == null || ids.isEmpty()) {
            return Collections.emptySet();
        }
        List<Permission> perms = permissionMapper.findByIds(new ArrayList<>(ids));
        return perms.stream()
                .map(Permission::getCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // ────────────────────────────────────────────────────────────────────────
    // Outcome types
    // ────────────────────────────────────────────────────────────────────────

    /** Discriminator for {@link DispatchOutcome}. */
    public enum OutcomeKind {
        /** LOW skill ran inline, or confirm() executed; {@link DispatchOutcome#result()} carries the result. */
        EXECUTED,
        /** MEDIUM+ skill returned a preview; FE must surface a confirm card. */
        PREVIEW_PENDING
    }

    /**
     * Dispatch outcome envelope returned by both {@link #dispatch} and
     * {@link #confirm}. Two factory methods mirror the two outcome kinds —
     * unused fields stay {@code null} so accidental reads surface as NPE
     * during chat-layer wiring (Task 4) rather than as silent empty strings.
     *
     * @param kind         {@link OutcomeKind#EXECUTED} or {@link OutcomeKind#PREVIEW_PENDING}.
     * @param result       the executed result; non-null when {@code kind == EXECUTED}.
     * @param preview      the dryRun preview {@link SkillResult}; non-null when {@code kind == PREVIEW_PENDING}.
     * @param previewToken minted preview token; non-null when {@code kind == PREVIEW_PENDING}.
     * @param riskLevel    persisted risk code (e.g. {@code "low"}) — always set for log/audit symmetry.
     */
    public record DispatchOutcome(OutcomeKind kind,
                                  SkillResult result,
                                  SkillResult preview,
                                  String previewToken,
                                  String riskLevel) {

        public static DispatchOutcome executed(SkillResult result, RiskLevel risk) {
            return new DispatchOutcome(OutcomeKind.EXECUTED, result, null, null,
                    risk == null ? null : risk.code());
        }

        public static DispatchOutcome pending(SkillResult preview, String previewToken, RiskLevel risk) {
            return new DispatchOutcome(OutcomeKind.PREVIEW_PENDING, null, preview, previewToken,
                    risk == null ? null : risk.code());
        }
    }
}
