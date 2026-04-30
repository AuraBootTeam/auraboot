package com.auraboot.framework.promotion.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.promotion.dao.entity.Promotion;
import com.auraboot.framework.promotion.dao.entity.PromotionUnit;
import com.auraboot.framework.promotion.dao.mapper.PromotionMapper;
import com.auraboot.framework.promotion.dao.mapper.PromotionUnitMapper;
import com.auraboot.framework.promotion.diff.PageSchemaDiffService;
import com.auraboot.framework.promotion.diff.SemanticDiffEntry;
import com.auraboot.framework.promotion.domain.PromotionStateMachine;
import com.auraboot.framework.promotion.domain.PromotionStatus;
import com.auraboot.framework.promotion.dto.DryRunResult;
import com.auraboot.framework.promotion.dto.PromotionRequest;
import com.auraboot.framework.promotion.dto.PromotionResponse;
import com.auraboot.framework.promotion.service.PromotionService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PromotionServiceImpl implements PromotionService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PromotionMapper promotionMapper;
    private final PromotionUnitMapper promotionUnitMapper;
    private final PageSchemaMapper pageSchemaMapper;
    private final PageSchemaDiffService pageSchemaDiffService;

    @Override
    @Transactional
    public PromotionResponse create(PromotionRequest request, Long tenantId, Long userId) {
        if (Objects.equals(request.getSourceEnvId(), request.getTargetEnvId())) {
            throw new IllegalArgumentException("Source and target environment must differ");
        }
        if (request.getUnits() == null || request.getUnits().isEmpty()) {
            throw new IllegalArgumentException("Promotion must include at least one unit");
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
            unit.setSourceVersion(u.getSourceVersion() != null ? u.getSourceVersion() : captureSourceVersion(p.getSourceEnvId(), u.getResourcePid()));
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
            if (!"PAGE_SCHEMA".equals(unit.getResourceType())) continue;

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
            if (target != null && contentDiffers(source, target)) {
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

        result.setValid(result.getConflicts().isEmpty() && result.getMissingDependencies().isEmpty());

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

    private List<PromotionUnit> listUnits(Long promotionId, Long tenantId) {
        QueryWrapper<PromotionUnit> qw = new QueryWrapper<>();
        qw.eq("promotion_id", promotionId)
                .eq("tenant_id", tenantId)
                .eq("deleted_flag", false)
                .orderByAsc("sort_order");
        return promotionUnitMapper.selectList(qw);
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
        r.setDryRunResult(parseDryRunResult(p.getDryRunResult()));
        r.setUnits(listUnits(p.getId(), p.getTenantId()).stream().map(u -> {
            PromotionResponse.PromotionUnitView v = new PromotionResponse.PromotionUnitView();
            v.setPid(u.getPid());
            v.setResourceType(u.getResourceType());
            v.setResourcePid(u.getResourcePid());
            v.setSourceVersion(u.getSourceVersion());
            v.setTargetVersion(u.getTargetVersion());
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
        if (v == null) return null;
        try {
            return JSON.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize JSON: {}", e.getMessage());
            return null;
        }
    }

    private DryRunResult parseDryRunResult(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readValue(json, DryRunResult.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse dry_run_result JSON: {}", e.getMessage());
            return null;
        }
    }
}
