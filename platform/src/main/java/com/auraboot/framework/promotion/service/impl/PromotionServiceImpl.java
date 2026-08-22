package com.auraboot.framework.promotion.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.environment.dao.entity.Environment;
import com.auraboot.framework.environment.dao.mapper.EnvironmentMapper;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.menu.service.MenuEnvironmentScopeService;
import com.auraboot.framework.promotion.dao.entity.Promotion;
import com.auraboot.framework.promotion.dao.entity.PromotionUnit;
import com.auraboot.framework.promotion.dao.mapper.PromotionMapper;
import com.auraboot.framework.promotion.dao.mapper.PromotionUnitMapper;
import com.auraboot.framework.promotion.diff.PageSchemaDiffService;
import com.auraboot.framework.promotion.diff.SemanticDiffEntry;
import com.auraboot.framework.promotion.domain.PromotionStateMachine;
import com.auraboot.framework.promotion.domain.PromotionStatus;
import com.auraboot.framework.promotion.dto.DryRunResult;
import com.auraboot.framework.promotion.dto.PromotionDriftDecisionRequest;
import com.auraboot.framework.promotion.dto.PromotionRequest;
import com.auraboot.framework.promotion.dto.PromotionResponse;
import com.auraboot.framework.promotion.reference.service.ResourceReferenceService;
import com.auraboot.framework.promotion.service.PromotionService;
import com.auraboot.framework.promotion.service.PromotionDriftCoordinator;
import com.auraboot.framework.promotion.service.PromotionDriftCoordinator.Assessment;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PromotionServiceImpl implements PromotionService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Dry-run freshness window — apply rejected if last validate is older than this. */
    private static final Duration DRY_RUN_TTL = Duration.ofHours(24);

    private final PromotionMapper promotionMapper;
    private final PromotionUnitMapper promotionUnitMapper;
    private final PageSchemaMapper pageSchemaMapper;
    private final EnvironmentMapper environmentMapper;
    private final PageSchemaDiffService pageSchemaDiffService;
    private final ResourceReferenceService resourceReferenceService;
    private final MenuEnvironmentScopeService menuEnvironmentScopeService;
    private final PromotionDriftCoordinator promotionDriftCoordinator;
    private final PlatformTransactionManager transactionManager;
    private final com.auraboot.framework.audit.service.AdminEventLogService adminEventLogService;

    @Override
    @Transactional
    public PromotionResponse create(PromotionRequest request, Long tenantId, Long userId) {
        if (Objects.equals(request.getSourceEnvId(), request.getTargetEnvId())) {
            throw new IllegalArgumentException("Source and target environment must differ");
        }
        if (request.getUnits() == null || request.getUnits().isEmpty()) {
            throw new IllegalArgumentException("Promotion must include at least one unit");
        }

        // Validate that both source and target env IDs belong to the caller's tenant.
        // selectById is already auto-filtered by tenant_id (ab_environment is not in
        // ignoreTable), so a cross-tenant id returns null. The explicit getTenantId()
        // compare is defense in depth against future ignoreTable changes.
        Environment sourceEnv = environmentMapper.selectById(request.getSourceEnvId());
        if (sourceEnv == null || !tenantId.equals(sourceEnv.getTenantId())) {
            throw new BusinessException(ResponseCode.BadParam,
                    "Source environment not found in tenant: " + request.getSourceEnvId());
        }
        Environment targetEnv = environmentMapper.selectById(request.getTargetEnvId());
        if (targetEnv == null || !tenantId.equals(targetEnv.getTenantId())) {
            throw new BusinessException(ResponseCode.BadParam,
                    "Target environment not found in tenant: " + request.getTargetEnvId());
        }

        Promotion p = new Promotion();
        p.setPid(UniqueIdGenerator.generate());
        p.setTenantId(tenantId);
        p.setSourceEnvId(request.getSourceEnvId());
        p.setTargetEnvId(request.getTargetEnvId());
        p.setStatus(PromotionStatus.DRAFT.name());
        p.setCreatedAt(new Date());
        p.setCreatedBy(userId);
        p.setUpdatedAt(new Date());
        p.setUpdatedBy(userId);
        p.setDeletedFlag(false);
        promotionMapper.insert(p);

        int order = 0;
        for (PromotionRequest.PromotionUnitDto u : request.getUnits()) {
            if (!"PAGE_SCHEMA".equals(u.getResourceType())) {
                throw new IllegalArgumentException("Unsupported resourceType in PoC: " + u.getResourceType());
            }
            PromotionUnit unit = new PromotionUnit();
            unit.setPid(UniqueIdGenerator.generate());
            unit.setTenantId(tenantId);
            unit.setPromotionId(p.getId());
            unit.setResourceType(u.getResourceType());
            unit.setResourcePid(u.getResourcePid());
            unit.setSourceVersion(u.getSourceVersion() != null
                    ? u.getSourceVersion()
                    : captureSourceVersion(p.getSourceEnvId(), u.getResourcePid()));
            unit.setSortOrder(u.getSortOrder() != null ? u.getSortOrder() : order++);
            unit.setCreatedAt(new Date());
            unit.setDeletedFlag(false);
            promotionUnitMapper.insert(unit);
        }

        p.setPlanSummary(toJson(buildPlanSummary(request)));
        promotionMapper.updateById(p);

        log.info("Created promotion {}: {} → {} with {} unit(s)",
                p.getPid(), request.getSourceEnvId(), request.getTargetEnvId(), request.getUnits().size());
        return toResponse(p);
    }

    @Override
    public PromotionResponse getByPid(String pid, Long tenantId) {
        Promotion p = findByPidOrThrow(pid, tenantId);
        return toResponse(p);
    }

    @Override
    public List<PromotionResponse> listByStatus(Long tenantId, String statusFilter) {
        QueryWrapper<Promotion> qw = new QueryWrapper<>();
        qw.eq("tenant_id", tenantId).eq("deleted_flag", false);
        if (statusFilter != null && !statusFilter.isBlank()) {
            qw.eq("status", statusFilter);
        }
        qw.orderByDesc("created_at");
        return promotionMapper.selectList(qw).stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public DryRunResult validate(String pid, Long tenantId) {
        Promotion p = findByPidOrThrow(pid, tenantId);
        PromotionStatus current = PromotionStatus.valueOf(p.getStatus());
        PromotionStateMachine.assertCanTransition(current, PromotionStatus.VALIDATED);

        DryRunResult result = new DryRunResult();
        result.setValidatedAt(new Date());

        List<PromotionUnit> units = listUnits(p.getId(), tenantId);
        for (PromotionUnit unit : units) {
            if (!"PAGE_SCHEMA".equals(unit.getResourceType())) {
                continue;
            }

            PageSchema source = withEnvId(p.getSourceEnvId(),
                    () -> pageSchemaMapper.selectByPid(unit.getResourcePid()));
            if (source == null) {
                DryRunResult.Conflict c = new DryRunResult.Conflict();
                c.setResourceType("PAGE_SCHEMA");
                c.setResourcePid(unit.getResourcePid());
                c.setReason("source resource missing in source env (deleted after draft?)");
                result.getConflicts().add(c);
                continue;
            }

            // Find target by logical identity (page_key) within target env
            PageSchema target = withEnvId(p.getTargetEnvId(),
                    () -> findByPageKey(source.getPageKey(), tenantId));
            Optional<Assessment> drift = promotionDriftCoordinator.assess(
                    p, unit, source, target,
                    MetaContext.exists() ? MetaContext.getCurrentUserId() : null);
            drift.map(promotionDriftCoordinator::toView).ifPresent(result.getDrifts()::add);
            if (target != null && drift.isEmpty() && contentDiffers(source, target)) {
                List<SemanticDiffEntry> diff = pageSchemaDiffService.diff(source, target);
                DryRunResult.Conflict c = new DryRunResult.Conflict();
                c.setResourceType("PAGE_SCHEMA");
                c.setResourcePid(unit.getResourcePid());
                c.setSourceVersion(source.getVersion());
                c.setTargetVersion(target.getVersion());
                c.setReason("target env already has a different version of " + source.getPageKey()
                        + " (" + diff.size() + " field-level changes)");
                c.setDiff(diff);
                result.getConflicts().add(c);
            }
        }

        boolean driftsReady = result.getDrifts().stream().allMatch(DryRunResult.Drift::isApplyReady);
        result.setValid(result.getConflicts().isEmpty()
                && result.getMissingDependencies().isEmpty()
                && driftsReady);

        p.setDryRunResult(toJson(result));
        p.setDryRunAt(result.getValidatedAt());
        if (result.isValid()) {
            // DRAFT → VALIDATED, or VALIDATED → VALIDATED (refresh)
            p.setStatus(PromotionStatus.VALIDATED.name());
        } else {
            // Errors found — keep at DRAFT (or roll VALIDATED back to DRAFT to force re-run)
            p.setStatus(PromotionStatus.DRAFT.name());
        }
        p.setUpdatedAt(new Date());
        p.setUpdatedBy(MetaContext.exists() ? MetaContext.getCurrentUserId() : null);
        promotionMapper.updateById(p);

        log.info("Validated promotion {}: valid={}, conflicts={}",
                p.getPid(), result.isValid(), result.getConflicts().size());
        return result;
    }

    @Override
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public PromotionResponse resolveDrift(
            String pid,
            String unitPid,
            PromotionDriftDecisionRequest request,
            Long tenantId,
            Long actorUserId) {
        Promotion promotion = findByPidOrThrow(pid, tenantId);
        PromotionStatus current = PromotionStatus.valueOf(promotion.getStatus());
        if (current != PromotionStatus.DRAFT && current != PromotionStatus.VALIDATED) {
            throw new IllegalStateException("Promotion drift cannot be resolved in status " + current);
        }
        PromotionUnit unit = findUnitByPid(promotion.getId(), unitPid, tenantId);
        PageSchema source = withEnvId(promotion.getSourceEnvId(),
                () -> pageSchemaMapper.selectByPid(unit.getResourcePid()));
        if (source == null) {
            throw new IllegalStateException("Promotion source resource is missing: " + unit.getResourcePid());
        }
        PageSchema target = withEnvId(promotion.getTargetEnvId(),
                () -> findByPageKey(source.getPageKey(), tenantId));
        try {
            promotionDriftCoordinator.resolve(
                    promotion, unit, source, target, request, actorUserId);
        } catch (ResponseStatusException rejectedDecision) {
            // The exact fingerprint may have become stale while the decision form was open.
            // Preserve the STALE/DETECTED ledger rows and synchronize the promotion back to
            // its current dry-run state before surfacing the conflict to the caller.
            validate(pid, tenantId);
            throw rejectedDecision;
        }
        validate(pid, tenantId);
        return toResponse(findByPidOrThrow(pid, tenantId));
    }

    // ---- apply ----

    @Override
    public PromotionResponse apply(String pid, Long tenantId, Long approverId, String reason) {
        Promotion p = findByPidOrThrow(pid, tenantId);

        // 1. State + freshness pre-check (no DB writes yet)
        PromotionStatus current = PromotionStatus.valueOf(p.getStatus());
        PromotionStateMachine.assertCanTransition(current, PromotionStatus.APPLIED);
        if (p.getDryRunAt() == null
                || Duration.between(p.getDryRunAt().toInstant(), Instant.now()).compareTo(DRY_RUN_TTL) > 0) {
            throw new IllegalStateException("Dry-run is stale (>24h). Re-validate before applying.");
        }
        DryRunResult lastDryRun = parseDryRunResult(p.getDryRunResult());
        if (lastDryRun == null || !lastDryRun.isValid()) {
            throw new IllegalStateException(
                    "Last dry-run had conflicts or unresolved drift and is not apply-ready. "
                            + "Resolve them, then re-validate.");
        }

        // Recompute target-local release fingerprints before entering the failure-marking apply
        // transaction. A stale human decision is an expected revalidation state, not an APPLIED
        // transaction failure and must not make the promotion terminally FAILED.
        try {
            requireCurrentDriftDecisions(p, approverId);
        } catch (ResponseStatusException staleDrift) {
            invalidatePromotionForDrift(p, approverId);
            throw staleDrift;
        }

        // 2. Four-eyes for locked target
        Environment target = environmentMapper.selectById(p.getTargetEnvId());
        if (target == null) {
            throw new IllegalStateException("Target environment missing: " + p.getTargetEnvId());
        }
        if (Boolean.TRUE.equals(target.getIsLocked())) {
            if (Objects.equals(approverId, p.getCreatedBy())) {
                throw new IllegalStateException(
                        "Four-eyes: applying to a locked environment requires a different approver than the creator");
            }
            if (reason == null || reason.trim().isEmpty()) {
                throw new IllegalArgumentException("Reason is required when applying to a locked environment");
            }
        }

        // 3. Outer wrapper: do all writes in one tx; on failure, mark FAILED in a NEW tx
        TransactionTemplate applyTx = new TransactionTemplate(transactionManager);
        applyTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        Throwable failure = null;
        try {
            applyTx.executeWithoutResult(status -> applyAllUnits(p, approverId, reason));
        } catch (Throwable t) {
            failure = t;
        }

        if (failure != null) {
            // Best-effort FAILED-status persistence in a separate tx. If this fails (e.g. when
            // running inside a Spring @Transactional+@Rollback test where the outer tx has not
            // committed the promotion row), we swallow the secondary error and surface the
            // ORIGINAL failure to the caller — that's what users / retries care about.
            try {
                markFailedInNewTx(pid, tenantId, failure.getMessage());
            } catch (Exception markFailEx) {
                log.warn("Could not persist FAILED status for promotion {} (original failure: {}): {}",
                        pid, failure.getMessage(), markFailEx.getMessage());
            }
            adminEventLogService.record(com.auraboot.framework.audit.entity.AdminEventLog.builder()
                    .tenantId(tenantId)
                    .actorUserId(approverId)
                    .actionType("promotion.apply")
                    .resourceType("promotion")
                    .resourcePid(pid)
                    .success(false)
                    .reason(failure.getMessage())
                    .build());
            log.warn("Promotion {} failed during apply: {}", pid, failure.getMessage());
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException("Promotion apply failed", failure);
        }

        // Success: status was set inside the tx
        adminEventLogService.record(com.auraboot.framework.audit.entity.AdminEventLog.builder()
                .tenantId(tenantId)
                .actorUserId(approverId)
                .actionType("promotion.apply")
                .resourceType("promotion")
                .resourcePid(pid)
                .success(true)
                .reason(reason)
                .build());
        return toResponse(findByPidOrThrow(pid, tenantId));
    }

    /**
     * All write side-effects of apply, run inside a single transaction. Any throw rolls
     * back the lot.
     */
    private void applyAllUnits(Promotion p, Long approverId, String reason) {
        List<PromotionUnit> units = listUnits(p.getId(), p.getTenantId());
        for (PromotionUnit unit : units) {
            applyOneUnit(p, unit, approverId, reason);
        }

        // Mark APPLIED in same tx
        Promotion fresh = promotionMapper.selectById(p.getId());
        fresh.setStatus(PromotionStatus.APPLIED.name());
        fresh.setAppliedAt(new Date());
        fresh.setAppliedBy(approverId);
        fresh.setAppliedReason(reason);
        fresh.setUpdatedAt(new Date());
        fresh.setUpdatedBy(approverId);
        promotionMapper.updateById(fresh);
    }

    private void requireCurrentDriftDecisions(Promotion promotion, Long actorUserId) {
        for (PromotionUnit unit : listUnits(promotion.getId(), promotion.getTenantId())) {
            PageSchema source = withEnvId(promotion.getSourceEnvId(),
                    () -> pageSchemaMapper.selectByPid(unit.getResourcePid()));
            if (source == null) {
                continue;
            }
            PageSchema target = withEnvId(promotion.getTargetEnvId(),
                    () -> findByPageKey(source.getPageKey(), promotion.getTenantId()));
            promotionDriftCoordinator.requireApplyReady(
                    promotion, unit, source, target, actorUserId);
        }
    }

    private void invalidatePromotionForDrift(Promotion promotion, Long actorUserId) {
        UpdateWrapper<Promotion> update = new UpdateWrapper<>();
        update.eq("id", promotion.getId())
                .eq("tenant_id", promotion.getTenantId())
                .eq("deleted_flag", false)
                .set("status", PromotionStatus.DRAFT.name())
                .set("dry_run_at", null)
                .set("dry_run_result", null)
                .set("updated_at", new Date())
                .set("updated_by", actorUserId);
        promotionMapper.update(null, update);
    }

    private void applyOneUnit(Promotion p, PromotionUnit unit, Long approverId, String reason) {
        if (!"PAGE_SCHEMA".equals(unit.getResourceType())) {
            throw new UnsupportedOperationException("Unsupported resourceType in PoC: " + unit.getResourceType());
        }

        // Read source page (filtered to source env)
        PageSchema source = withEnvId(p.getSourceEnvId(),
                () -> pageSchemaMapper.selectByPid(unit.getResourcePid()));
        if (source == null) {
            throw new IllegalStateException(
                    "Source page missing in source env (deleted after draft?): " + unit.getResourcePid());
        }
        if (unit.getSourceVersion() != null
                && !unit.getSourceVersion().equals(source.getVersion())) {
            throw new IllegalStateException("Promotion source changed after draft: " + unit.getResourcePid());
        }

        // Find existing target page (by page_key, in target env)
        PageSchema existingTarget = withEnvId(p.getTargetEnvId(),
                () -> findByPageKey(source.getPageKey(), p.getTenantId()));
        Optional<Assessment> drift = promotionDriftCoordinator.requireApplyReady(
                p, unit, source, existingTarget, approverId);

        int targetVersion = (existingTarget == null) ? 1 : existingTarget.getVersion() + 1;
        ObjectNode sourceSnapshot = promotionDriftCoordinator.sourceForApply(
                unit, pageSnapshot(source));

        // Mark prior is_current row as not_current (only if there's a prior)
        if (existingTarget != null) {
            UpdateWrapper<PageSchema> uw = new UpdateWrapper<>();
            uw.eq("id", existingTarget.getId()).set("is_current", false).set("updated_at", new Date());
            withEnvId(p.getTargetEnvId(), () -> {
                pageSchemaMapper.update(null, uw);
                return null;
            });
        }

        // INSERT new row in target env (envId auto-stamped by AuraBootObjectHandler since
        // MetaContext.envId is set inside withEnvId)
        PageSchema clone = new PageSchema();
        clone.setPid(UniqueIdGenerator.generate());
        clone.setTenantId(p.getTenantId());
        clone.setPageKey(sourceSnapshot.path("pageKey").asText(source.getPageKey()));
        clone.setModelCode(sourceSnapshot.path("modelCode").asText(source.getModelCode()));
        clone.setName(sourceSnapshot.path("name").asText(source.getName())
                + "_v" + targetVersion);  // tenant-namespace uniqueness on name
        clone.setDescription(sourceSnapshot.path("description").asText(source.getDescription()));
        clone.setKind(sourceSnapshot.path("kind").asText(source.getKind()));
        clone.setProfile(sourceSnapshot.path("profile").asText(source.getProfile()));
        clone.setSchemaVersion(sourceSnapshot.path("schemaVersion").asInt(source.getSchemaVersion()));
        clone.setTitle(toJson(sourceSnapshot.path("title")));
        clone.setLayout(toJson(sourceSnapshot.path("layout")));
        clone.setBlocks(toJson(runtimeBlocks(sourceSnapshot)));
        clone.setMetaInfo(source.getMetaInfo());
        clone.setIsTemplate(source.getIsTemplate());
        clone.setTemplateCategory(source.getTemplateCategory());
        clone.setPluginPid(source.getPluginPid());
        clone.setOwnershipScope(source.getOwnershipScope());
        clone.setOwnershipRef(source.getOwnershipRef());
        clone.setSortWeight(source.getSortWeight());
        clone.setStatus("draft");
        clone.setVersion(targetVersion);
        clone.setSemver(source.getSemver());
        clone.setIsCurrent(true);
        clone.setRowVersion(1);
        clone.setDeletedFlag(false);

        // env-layering #17: promotion to a locked target env bypasses the lock guard —
        // four-eyes already enforced in the outer apply() pre-check.
        withEnvId(p.getTargetEnvId(), () ->
                MetaContext.runWithoutLockGuard(() -> {
                    pageSchemaMapper.insert(clone);
                    // Refresh reverse references for the freshly written page (env-scoped)
                    resourceReferenceService.refresh(clone);
                    menuEnvironmentScopeService.includeEnvironment(
                            p.getTenantId(), source.getPageKey(), p.getTargetEnvId());
                    return null;
                }));

        drift.ifPresent(assessment -> promotionDriftCoordinator.applyDecision(
                p, unit, assessment, approverId, reason));

        // Stamp target_version on the unit
        unit.setTargetVersion(targetVersion);
        promotionUnitMapper.updateById(unit);
    }

    private void markFailedInNewTx(String pid, Long tenantId, String failureReason) {
        TransactionTemplate failureTx = new TransactionTemplate(transactionManager);
        failureTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        failureTx.executeWithoutResult(status -> {
            Promotion p = findByPidOrThrow(pid, tenantId);
            // Use direct UPDATE — state machine guards VALIDATED→FAILED, no risk of race here
            p.setStatus(PromotionStatus.FAILED.name());
            p.setFailureReason(failureReason);
            p.setUpdatedAt(new Date());
            promotionMapper.updateById(p);
        });
    }

    // ---- helpers ----

    private Integer captureSourceVersion(Long sourceEnvId, String resourcePid) {
        PageSchema page = withEnvId(sourceEnvId, () -> pageSchemaMapper.selectByPid(resourcePid));
        return page != null ? page.getVersion() : null;
    }

    private PageSchema findByPageKey(String pageKey, Long tenantId) {
        QueryWrapper<PageSchema> qw = new QueryWrapper<>();
        qw.eq("page_key", pageKey)
                .eq("tenant_id", tenantId)
                .eq("deleted_flag", false)
                .orderByDesc("version")
                .last("LIMIT 1");
        return pageSchemaMapper.selectOne(qw);
    }

    private boolean contentDiffers(PageSchema a, PageSchema b) {
        return !Objects.equals(a.getBlocks(), b.getBlocks())
                || !Objects.equals(a.getTitle(), b.getTitle())
                || !Objects.equals(a.getLayout(), b.getLayout());
    }

    private ObjectNode pageSnapshot(PageSchema page) {
        ObjectNode snapshot = JSON.createObjectNode();
        snapshot.put("pid", page.getPid());
        snapshot.put("pageKey", page.getPageKey());
        snapshot.put("modelCode", page.getModelCode());
        snapshot.put("name", page.getName());
        if (page.getDescription() != null) snapshot.put("description", page.getDescription());
        snapshot.put("kind", page.getKind());
        snapshot.put("profile", page.getProfile());
        snapshot.put("schemaVersion", page.getSchemaVersion());
        snapshot.set("title", parseNode(page.getTitle(), JSON.createObjectNode()));
        snapshot.set("layout", parseNode(page.getLayout(), JSON.createObjectNode()));
        snapshot.set("blocks", parseNode(page.getBlocks(), JSON.createArrayNode()));
        return snapshot;
    }

    private com.fasterxml.jackson.databind.JsonNode runtimeBlocks(ObjectNode snapshot) {
        com.fasterxml.jackson.databind.JsonNode blocks = snapshot.path("blocks");
        if (blocks.isArray() && blocks.size() == 1) {
            com.fasterxml.jackson.databind.JsonNode root = blocks.get(0);
            if (snapshot.path("kind").asText().equals(root.path("blockType").asText())
                    && root.path("blocks").isArray()) {
                return root.path("blocks");
            }
        }
        return blocks;
    }

    private com.fasterxml.jackson.databind.JsonNode parseNode(
            String value, com.fasterxml.jackson.databind.JsonNode fallback) {
        try {
            return value == null ? fallback : JSON.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("promotion.page.snapshot-invalid", exception);
        }
    }

    private List<PromotionUnit> listUnits(Long promotionId, Long tenantId) {
        QueryWrapper<PromotionUnit> qw = new QueryWrapper<>();
        qw.eq("promotion_id", promotionId)
                .eq("tenant_id", tenantId)
                .eq("deleted_flag", false)
                .orderByAsc("sort_order");
        return promotionUnitMapper.selectList(qw);
    }

    private PromotionUnit findUnitByPid(Long promotionId, String unitPid, Long tenantId) {
        QueryWrapper<PromotionUnit> query = new QueryWrapper<>();
        query.eq("promotion_id", promotionId)
                .eq("pid", unitPid)
                .eq("tenant_id", tenantId)
                .eq("deleted_flag", false);
        PromotionUnit unit = promotionUnitMapper.selectOne(query);
        if (unit == null) {
            throw new IllegalArgumentException("Promotion unit not found: " + unitPid);
        }
        return unit;
    }

    private Promotion findByPidOrThrow(String pid, Long tenantId) {
        QueryWrapper<Promotion> qw = new QueryWrapper<>();
        qw.eq("pid", pid).eq("tenant_id", tenantId).eq("deleted_flag", false);
        Promotion p = promotionMapper.selectOne(qw);
        if (p == null) {
            throw new IllegalArgumentException("Promotion not found: " + pid);
        }
        return p;
    }

    /** Run an action with a temporarily-overridden env id, restoring the prior value. */
    private <T> T withEnvId(Long envId, Supplier<T> action) {
        Long prior = MetaContext.getCurrentEnvironmentId();
        MetaContext.setEnvironmentId(envId);
        try {
            return action.get();
        } finally {
            MetaContext.setEnvironmentId(prior);
        }
    }

    private PromotionResponse toResponse(Promotion p) {
        PromotionResponse r = new PromotionResponse();
        r.setPid(p.getPid());
        r.setSourceEnvId(p.getSourceEnvId());
        r.setTargetEnvId(p.getTargetEnvId());
        r.setStatus(p.getStatus());
        r.setCreatedAt(p.getCreatedAt());
        r.setCreatedBy(p.getCreatedBy());
        r.setUpdatedAt(p.getUpdatedAt());
        r.setDryRunAt(p.getDryRunAt());
        r.setAppliedAt(p.getAppliedAt());
        r.setAppliedBy(p.getAppliedBy());
        r.setAppliedReason(p.getAppliedReason());
        r.setRejectedAt(p.getRejectedAt());
        r.setRejectedBy(p.getRejectedBy());
        r.setRejectedReason(p.getRejectedReason());
        r.setFailureReason(p.getFailureReason());
        r.setParentPromotionPid(p.getParentPromotionPid());
        r.setOriginDriftDecisionPid(p.getOriginDriftDecisionPid());
        r.setDryRunResult(parseDryRunResult(p.getDryRunResult()));
        r.setUnits(listUnits(p.getId(), p.getTenantId()).stream().map(u -> {
            PromotionResponse.PromotionUnitView v = new PromotionResponse.PromotionUnitView();
            v.setPid(u.getPid());
            v.setResourceType(u.getResourceType());
            v.setResourcePid(u.getResourcePid());
            v.setSourceVersion(u.getSourceVersion());
            v.setTargetVersion(u.getTargetVersion());
            v.setTargetResourcePid(u.getTargetResourcePid());
            v.setDriftStatus(u.getDriftStatus());
            v.setDriftFingerprint(u.getDriftFingerprint());
            v.setDriftDecision(u.getDriftDecision());
            v.setDriftExecutionStatus(u.getDriftExecutionStatus());
            v.setDriftExecutionPid(u.getDriftExecutionPid());
            v.setSortOrder(u.getSortOrder());
            return v;
        }).collect(Collectors.toList()));
        return r;
    }

    private Object buildPlanSummary(PromotionRequest req) {
        java.util.Map<String, Object> summary = new java.util.HashMap<>();
        summary.put("unitCount", req.getUnits().size());
        summary.put("resourceTypes",
                req.getUnits().stream().map(PromotionRequest.PromotionUnitDto::getResourceType).distinct().toList());
        return summary;
    }

    private String toJson(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize JSON: {}", e.getMessage());
            return null;
        }
    }

    private DryRunResult parseDryRunResult(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, DryRunResult.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse dry_run_result JSON: {}", e.getMessage());
            return null;
        }
    }
}
