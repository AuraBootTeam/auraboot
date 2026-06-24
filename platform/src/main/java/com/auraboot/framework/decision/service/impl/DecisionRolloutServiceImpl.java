package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.decision.dto.DecisionRolloutActionRequest;
import com.auraboot.framework.decision.dto.DecisionRolloutCreateRequest;
import com.auraboot.framework.decision.dto.DecisionRolloutDTO;
import com.auraboot.framework.decision.dto.DecisionRolloutMetricAggregateRow;
import com.auraboot.framework.decision.dto.DecisionRolloutMetricDistributionRow;
import com.auraboot.framework.decision.dto.DecisionRolloutMetricWindowRow;
import com.auraboot.framework.decision.dto.DecisionRolloutMetricsDTO;
import com.auraboot.framework.decision.dto.DrtEvaluateRequest;
import com.auraboot.framework.decision.entity.DecisionRolloutPolicyEntity;
import com.auraboot.framework.decision.entity.DrtVersionEntity;
import com.auraboot.framework.decision.event.DecisionRolloutPolicyChangedEvent;
import com.auraboot.framework.decision.mapper.DecisionRolloutPolicyMapper;
import com.auraboot.framework.decision.mapper.DrtLogMapper;
import com.auraboot.framework.decision.mapper.DrtVersionMapper;
import com.auraboot.framework.decision.model.DecisionRolloutArm;
import com.auraboot.framework.decision.model.DecisionRolloutSelection;
import com.auraboot.framework.decision.model.DecisionRolloutStatus;
import com.auraboot.framework.decision.model.VersionStatus;
import com.auraboot.framework.decision.service.DecisionRolloutService;
import com.auraboot.framework.event.AuraEventBus;
import com.auraboot.framework.exception.ValidationException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@RequiredArgsConstructor
public class DecisionRolloutServiceImpl implements DecisionRolloutService {

    private static final int DEFAULT_ROLLOUT_METRICS_WINDOW_HOURS = 24 * 7;
    private static final int MAX_ROLLOUT_METRICS_WINDOW_HOURS = 24 * 90;
    private static final int DEFAULT_ROLLOUT_METRICS_BUCKET_MINUTES = 60;
    private static final int MIN_ROLLOUT_METRICS_BUCKET_MINUTES = 5;
    private static final int MAX_ROLLOUT_METRICS_BUCKET_MINUTES = 24 * 60;
    private static final int ROLLOUT_METRICS_RETENTION_DAYS = 90;
    private static final String ROLLOUT_METRICS_SOURCE = "PRE_AGGREGATED_BUCKETS";
    private static final String ROLLOUT_METRICS_LATENCY_AGGREGATION = "MAX_BUCKET_P95";

    private final DecisionRolloutPolicyMapper rolloutMapper;
    private final DrtVersionMapper versionMapper;
    private final DrtLogMapper logMapper;
    private final ObjectMapper objectMapper;
    private final AuraEventBus eventBus;
    private final ConcurrentMap<ServingPolicyCacheKey, ServingPolicyCacheEntry> servingPolicyCache =
            new ConcurrentHashMap<>();

    @Transactional
    @Override
    public DecisionRolloutDTO create(String decisionCode, DecisionRolloutCreateRequest request) {
        Long tid = requireTenant();
        if (request.getBaselineVersion().equals(request.getCandidateVersion())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "baselineVersion and candidateVersion must be different");
        }
        requireBindableVersion(tid, decisionCode, request.getBaselineVersion());
        requireBindableVersion(tid, decisionCode, request.getCandidateVersion());

        Instant now = Instant.now();
        DecisionRolloutPolicyEntity entity = new DecisionRolloutPolicyEntity();
        entity.setPid(UniqueIdGenerator.generate());
        entity.setTenantId(tid);
        entity.setDecisionCode(decisionCode);
        entity.setBaselineVersion(request.getBaselineVersion());
        entity.setCandidateVersion(request.getCandidateVersion());
        entity.setStatus(DecisionRolloutStatus.DRAFT.name());
        entity.setPercentage(clampPercentage(request.getPercentage()));
        entity.setCohortJson(request.getCohort());
        entity.setSegmentJson(request.getSegment());
        entity.setRoutingKeyExpr(request.getRoutingKeyExpr());
        entity.setSalt(request.getSalt() == null || request.getSalt().isBlank() ? entity.getPid() : request.getSalt());
        entity.setAuditJson(audit("CREATE", null));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        rolloutMapper.insert(entity);
        invalidateAndPublishServingPolicyChange(entity);
        return toDTO(entity);
    }

    @Override
    public List<DecisionRolloutDTO> list(String decisionCode) {
        return rolloutMapper.findByDecision(requireTenant(), decisionCode).stream().map(this::toDTO).toList();
    }

    @Override
    public PageResult<DecisionRolloutDTO> listPage(
            String decisionCode,
            String status,
            String keyword,
            int page,
            int size,
            String sortField,
            String sortOrder) {
        Long tid = requireTenant();
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(1, Math.min(size, 100));

        QueryWrapper<DecisionRolloutPolicyEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("tenant_id", tid);
        if (StringUtils.hasText(decisionCode)) {
            wrapper.like("decision_code", decisionCode.trim());
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq("status", status.trim().toUpperCase());
        }
        if (StringUtils.hasText(keyword)) {
            String q = keyword.trim();
            wrapper.and(w -> w.like("decision_code", q)
                    .or().like("status", q)
                    .or().like("routing_key_expr", q));
        }

        String sortColumn = rolloutSortColumn(sortField);
        if ("asc".equalsIgnoreCase(sortOrder)) {
            wrapper.orderByAsc(sortColumn);
        } else {
            wrapper.orderByDesc(sortColumn);
        }

        Page<DecisionRolloutPolicyEntity> entityPage =
                rolloutMapper.selectPage(new Page<>(safePage + 1L, safeSize), wrapper);
        PageResult<DecisionRolloutDTO> result = new PageResult<>();
        result.setRecords(entityPage.getRecords().stream().map(this::toDTO).toList());
        result.setTotal(entityPage.getTotal());
        result.setSize(entityPage.getSize());
        result.setCurrent(entityPage.getCurrent());
        result.setPages(entityPage.getPages());
        result.setHasPrevious(entityPage.hasPrevious());
        result.setHasNext(entityPage.hasNext());
        return result;
    }

    @Override
    public DecisionRolloutDTO get(String pid) {
        return toDTO(load(pid));
    }

    @Override
    public DecisionRolloutDTO active(String decisionCode) {
        DecisionRolloutPolicyEntity active = rolloutMapper.findActive(requireTenant(), decisionCode);
        return active == null ? null : toDTO(active);
    }

    @Transactional
    @Override
    public DecisionRolloutDTO activate(String pid, DecisionRolloutActionRequest request) {
        DecisionRolloutPolicyEntity entity = load(pid);
        DecisionRolloutStatus status = DecisionRolloutStatus.valueOf(entity.getStatus());
        if (status != DecisionRolloutStatus.DRAFT && status != DecisionRolloutStatus.PAUSED) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Cannot activate rollout from status " + status);
        }
        DecisionRolloutPolicyEntity existing = rolloutMapper.findActive(entity.getTenantId(), entity.getDecisionCode());
        if (existing != null && !existing.getPid().equals(entity.getPid())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Another rollout policy is already ACTIVE for decision " + entity.getDecisionCode());
        }
        entity.setStatus(DecisionRolloutStatus.ACTIVE.name());
        entity.setStartedBy(currentUserPid());
        entity.setStartedAt(entity.getStartedAt() == null ? Instant.now() : entity.getStartedAt());
        touchAudit(entity, "ACTIVATE", note(request));
        rolloutMapper.updateById(entity);
        invalidateAndPublishServingPolicyChange(entity);
        return toDTO(entity);
    }

    @Transactional
    @Override
    public DecisionRolloutDTO pause(String pid, DecisionRolloutActionRequest request) {
        DecisionRolloutPolicyEntity entity = load(pid);
        if (!DecisionRolloutStatus.ACTIVE.name().equals(entity.getStatus())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Only ACTIVE rollout can be paused");
        }
        entity.setStatus(DecisionRolloutStatus.PAUSED.name());
        touchAudit(entity, "PAUSE", note(request));
        rolloutMapper.updateById(entity);
        invalidateAndPublishServingPolicyChange(entity);
        return toDTO(entity);
    }

    @Transactional
    @Override
    public DecisionRolloutDTO promote(String pid, DecisionRolloutActionRequest request) {
        DecisionRolloutPolicyEntity entity = load(pid);
        if (!DecisionRolloutStatus.ACTIVE.name().equals(entity.getStatus())
                && !DecisionRolloutStatus.PAUSED.name().equals(entity.getStatus())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Only ACTIVE or PAUSED rollout can be promoted");
        }
        entity.setStatus(DecisionRolloutStatus.PROMOTED.name());
        entity.setEndedBy(currentUserPid());
        entity.setEndedAt(Instant.now());
        touchAudit(entity, "PROMOTE", note(request));
        rolloutMapper.updateById(entity);
        invalidateAndPublishServingPolicyChange(entity);
        return toDTO(entity);
    }

    @Transactional
    @Override
    public DecisionRolloutDTO rollback(String pid, DecisionRolloutActionRequest request) {
        DecisionRolloutPolicyEntity entity = load(pid);
        if (!DecisionRolloutStatus.ACTIVE.name().equals(entity.getStatus())
                && !DecisionRolloutStatus.PAUSED.name().equals(entity.getStatus())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Only ACTIVE or PAUSED rollout can be rolled back");
        }
        entity.setStatus(DecisionRolloutStatus.ROLLED_BACK.name());
        entity.setEndedBy(currentUserPid());
        entity.setEndedAt(Instant.now());
        touchAudit(entity, "ROLLBACK", note(request));
        rolloutMapper.updateById(entity);
        invalidateAndPublishServingPolicyChange(entity);
        return toDTO(entity);
    }

    @Override
    public DecisionRolloutMetricsDTO metrics(String pid) {
        return metrics(pid, DEFAULT_ROLLOUT_METRICS_WINDOW_HOURS,
                DEFAULT_ROLLOUT_METRICS_BUCKET_MINUTES, true);
    }

    @Override
    public DecisionRolloutMetricsDTO metrics(String pid, int windowHours, int bucketMinutes, boolean refresh) {
        DecisionRolloutPolicyEntity policy = load(pid);
        int safeWindowHours = normalizeWindowHours(windowHours);
        int safeBucketSeconds = normalizeBucketMinutes(bucketMinutes) * 60;
        Instant now = Instant.now();
        Instant since = now.minusSeconds(safeWindowHours * 60L * 60L);
        if (refresh) {
            logMapper.refreshRolloutMetricBuckets(policy.getTenantId(), policy.getPid(), since, safeBucketSeconds);
            logMapper.deleteRolloutMetricBucketsOlderThan(
                    policy.getTenantId(), now.minusSeconds(ROLLOUT_METRICS_RETENTION_DAYS * 24L * 60L * 60L));
        }

        DecisionRolloutMetricsDTO dto = new DecisionRolloutMetricsDTO();
        dto.setPolicyPid(policy.getPid());
        dto.setWindowHours(safeWindowHours);
        dto.setBucketSeconds(safeBucketSeconds);
        dto.setRetentionDays(ROLLOUT_METRICS_RETENTION_DAYS);
        dto.setSource(ROLLOUT_METRICS_SOURCE);
        dto.setLatencyAggregation(ROLLOUT_METRICS_LATENCY_AGGREGATION);
        dto.setRefreshedAt(now);
        dto.getBaseline().setVersion(policy.getBaselineVersion());
        dto.getCandidate().setVersion(policy.getCandidateVersion());

        logMapper.aggregateMetricBucketsByRolloutPolicy(policy.getTenantId(), policy.getPid(), since, safeBucketSeconds)
                .forEach(row -> applyAggregate(dto, row));
        logMapper.aggregateBucketDistributionByRolloutPolicy(policy.getTenantId(), policy.getPid(), since, safeBucketSeconds)
                .forEach(row -> applyDistribution(dto, row));
        dto.setWindows(metricWindows(policy, since, safeBucketSeconds));
        return dto;
    }

    @Override
    public DecisionRolloutSelection select(Long tenantId, DrtEvaluateRequest request, List<DrtVersionEntity> candidates) {
        DecisionRolloutPolicyEntity policy = findServingPolicy(tenantId, request.getDecisionCode());
        if (policy == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND,
                    "No serving rollout policy for decision " + request.getDecisionCode());
        }
        String routingKey = routingKey(request);
        int bucket = stableBucket(tenantId, request.getDecisionCode(), routingKey, policy.getSalt());
        DecisionRolloutArm arm = selectArm(policy, routingKey, bucket, tenantSegment(request));
        Integer selectedVersion = arm == DecisionRolloutArm.CANDIDATE
                ? policy.getCandidateVersion()
                : policy.getBaselineVersion();
        DrtVersionEntity selected = candidates == null ? null : candidates.stream()
                .filter(v -> selectedVersion.equals(v.getVersion()))
                .filter(this::isBindable)
                .findFirst().orElse(null);
        if (selected == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Rollout selected non-bindable or missing version " + selectedVersion);
        }
        return new DecisionRolloutSelection(policy, selected, arm, bucket, routingKey);
    }

    private DecisionRolloutPolicyEntity findServingPolicy(Long tenantId, String decisionCode) {
        ServingPolicyCacheKey key = new ServingPolicyCacheKey(tenantId, decisionCode);
        Instant servingUpdatedAt = rolloutMapper.findServingUpdatedAt(tenantId, decisionCode);
        ServingPolicyCacheEntry cached = servingPolicyCache.get(key);
        if (cached != null && cached.matches(servingUpdatedAt)) {
            return cached.policy();
        }
        DecisionRolloutPolicyEntity policy = rolloutMapper.findServing(tenantId, decisionCode);
        servingPolicyCache.put(key, new ServingPolicyCacheEntry(policy, servingUpdatedAt));
        return policy;
    }

    private void invalidateServingPolicyCache(Long tenantId, String decisionCode) {
        if (tenantId == null || decisionCode == null) {
            return;
        }
        servingPolicyCache.remove(new ServingPolicyCacheKey(tenantId, decisionCode));
    }

    private void invalidateAndPublishServingPolicyChange(DecisionRolloutPolicyEntity entity) {
        invalidateServingPolicyCache(entity.getTenantId(), entity.getDecisionCode());
        eventBus.publishAfterCommit(new DecisionRolloutPolicyChangedEvent(
                entity.getTenantId(), entity.getPid(), entity.getDecisionCode()));
    }

    @EventListener
    public void onRolloutPolicyChanged(DecisionRolloutPolicyChangedEvent event) {
        invalidateServingPolicyCache(event.getTenantId(), event.getDecisionCode());
    }

    private DecisionRolloutArm selectArm(DecisionRolloutPolicyEntity policy, String routingKey, int bucket, String tenantSegment) {
        DecisionRolloutStatus status = DecisionRolloutStatus.valueOf(policy.getStatus());
        if (status == DecisionRolloutStatus.PROMOTED) {
            return DecisionRolloutArm.CANDIDATE;
        }
        if (status == DecisionRolloutStatus.ROLLED_BACK) {
            return DecisionRolloutArm.BASELINE;
        }
        boolean eligible = eligible(policy, routingKey, tenantSegment);
        return eligible && bucket < clampPercentage(policy.getPercentage())
                ? DecisionRolloutArm.CANDIDATE
                : DecisionRolloutArm.BASELINE;
    }

    private void applyAggregate(DecisionRolloutMetricsDTO dto, DecisionRolloutMetricAggregateRow row) {
        DecisionRolloutMetricsDTO.ArmMetrics metrics = armMetrics(dto, row.getRolloutArm());
        if (metrics == null) {
            return;
        }
        applyAggregate(metrics, row.getEvaluations(), row.getMatched(), row.getErrors(), row.getP95LatencyMs());
    }

    private void applyDistribution(DecisionRolloutMetricsDTO dto, DecisionRolloutMetricDistributionRow row) {
        DecisionRolloutMetricsDTO.ArmMetrics metrics = armMetrics(dto, row.getRolloutArm());
        if (metrics == null || row.getResultKey() == null) {
            return;
        }
        metrics.getResultDistribution().put(row.getResultKey(), safeLong(row.getItemCount()));
    }

    private List<DecisionRolloutMetricsDTO.WindowMetrics> metricWindows(
            DecisionRolloutPolicyEntity policy,
            Instant since,
            int bucketSeconds) {
        List<DecisionRolloutMetricWindowRow> rows = logMapper.findRolloutMetricBucketWindows(
                policy.getTenantId(), policy.getPid(), since, bucketSeconds);
        Map<Instant, DecisionRolloutMetricsDTO.WindowMetrics> byWindow = new LinkedHashMap<>();
        for (DecisionRolloutMetricWindowRow row : rows) {
            if (row.getWindowStart() == null) {
                continue;
            }
            DecisionRolloutMetricsDTO.WindowMetrics window = byWindow.computeIfAbsent(row.getWindowStart(), key -> {
                DecisionRolloutMetricsDTO.WindowMetrics created = new DecisionRolloutMetricsDTO.WindowMetrics();
                created.setWindowStart(key);
                created.getBaseline().setVersion(policy.getBaselineVersion());
                created.getCandidate().setVersion(policy.getCandidateVersion());
                return created;
            });
            DecisionRolloutMetricsDTO.ArmMetrics metrics = armMetrics(window, row.getRolloutArm());
            if (metrics != null) {
                applyAggregate(metrics, row.getEvaluations(), row.getMatched(), row.getErrors(), row.getP95LatencyMs());
            }
        }
        return new ArrayList<>(byWindow.values());
    }

    private void applyAggregate(
            DecisionRolloutMetricsDTO.ArmMetrics metrics,
            Long evaluations,
            Long matched,
            Long errors,
            Long p95LatencyMs) {
        long evals = safeLong(evaluations);
        metrics.setEvaluations(evals);
        metrics.setMatched(safeLong(matched));
        metrics.setErrors(safeLong(errors));
        metrics.setMatchedRate(evals == 0 ? 0.0 : (double) metrics.getMatched() / evals);
        metrics.setErrorRate(evals == 0 ? 0.0 : (double) metrics.getErrors() / evals);
        metrics.setP95LatencyMs(p95LatencyMs);
    }

    private DecisionRolloutMetricsDTO.ArmMetrics armMetrics(DecisionRolloutMetricsDTO dto, String rolloutArm) {
        if (DecisionRolloutArm.BASELINE.name().equals(rolloutArm)) {
            return dto.getBaseline();
        }
        if (DecisionRolloutArm.CANDIDATE.name().equals(rolloutArm)) {
            return dto.getCandidate();
        }
        return null;
    }

    private DecisionRolloutMetricsDTO.ArmMetrics armMetrics(DecisionRolloutMetricsDTO.WindowMetrics window, String rolloutArm) {
        if (DecisionRolloutArm.BASELINE.name().equals(rolloutArm)) {
            return window.getBaseline();
        }
        if (DecisionRolloutArm.CANDIDATE.name().equals(rolloutArm)) {
            return window.getCandidate();
        }
        return null;
    }

    private long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    private int normalizeWindowHours(int windowHours) {
        if (windowHours <= 0) {
            return DEFAULT_ROLLOUT_METRICS_WINDOW_HOURS;
        }
        return Math.min(windowHours, MAX_ROLLOUT_METRICS_WINDOW_HOURS);
    }

    private int normalizeBucketMinutes(int bucketMinutes) {
        if (bucketMinutes <= 0) {
            return DEFAULT_ROLLOUT_METRICS_BUCKET_MINUTES;
        }
        return Math.max(MIN_ROLLOUT_METRICS_BUCKET_MINUTES,
                Math.min(bucketMinutes, MAX_ROLLOUT_METRICS_BUCKET_MINUTES));
    }

    private DecisionRolloutPolicyEntity load(String pid) {
        DecisionRolloutPolicyEntity entity = rolloutMapper.findByPid(requireTenant(), pid);
        if (entity == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Rollout policy not found: " + pid);
        }
        return entity;
    }

    private DrtVersionEntity requireBindableVersion(Long tenantId, String decisionCode, Integer version) {
        DrtVersionEntity entity = versionMapper.findByTenantCodeVersion(tenantId, decisionCode, version);
        if (entity == null || !isBindable(entity)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Version " + version + " is not bindable for decision " + decisionCode);
        }
        return entity;
    }

    private boolean isBindable(DrtVersionEntity entity) {
        try {
            return entity.getStatus() != null && VersionStatus.valueOf(entity.getStatus()).isBindable();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean eligible(DecisionRolloutPolicyEntity policy, String routingKey, String tenantSegment) {
        return eligibleCohort(policy.getCohortJson(), routingKey) && eligibleSegment(policy.getSegmentJson(), tenantSegment);
    }

    private boolean eligibleCohort(JsonNode cohort, String routingKey) {
        if (cohort == null || cohort.isNull() || cohort.isMissingNode() || cohort.isEmpty()) {
            return true;
        }
        JsonNode keys = cohort.path("routingKeys");
        if (keys.isArray() && keys.size() > 0) {
            for (JsonNode key : keys) {
                if (routingKey.equals(key.asText())) {
                    return true;
                }
            }
            return false;
        }
        JsonNode prefixes = cohort.path("traceIdPrefix");
        if (prefixes.isArray() && prefixes.size() > 0) {
            for (JsonNode prefix : prefixes) {
                if (routingKey.startsWith(prefix.asText())) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private boolean eligibleSegment(JsonNode segment, String tenantSegment) {
        if (segment == null || segment.isNull() || segment.isMissingNode() || segment.isEmpty()) {
            return true;
        }
        if (tenantSegment == null || tenantSegment.isBlank()) {
            return false;
        }
        JsonNode single = segment.path("tenantSegment");
        if (single.isTextual()) {
            return tenantSegment.equals(single.asText());
        }
        JsonNode many = segment.path("tenantSegments");
        if (many.isArray() && many.size() > 0) {
            for (JsonNode candidate : many) {
                if (tenantSegment.equals(candidate.asText())) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private String routingKey(DrtEvaluateRequest request) {
        if (request.getRoutingKey() != null && !request.getRoutingKey().isBlank()) {
            return request.getRoutingKey();
        }
        Object metaKey = scopedValue(request, "meta", "routingKey");
        if (metaKey != null) {
            return String.valueOf(metaKey);
        }
        Object recordPid = scopedValue(request, "record", "recordPid");
        if (recordPid == null) {
            recordPid = scopedValue(request, "record", "pid");
        }
        if (recordPid != null) {
            return String.valueOf(recordPid);
        }
        if (request.getCorrelationId() != null && !request.getCorrelationId().isBlank()) {
            return request.getCorrelationId();
        }
        if (request.getCallerRef() != null && !request.getCallerRef().isBlank()) {
            return request.getCallerRef();
        }
        return "__default__";
    }

    private String tenantSegment(DrtEvaluateRequest request) {
        if (request.getTenantSegment() != null && !request.getTenantSegment().isBlank()) {
            return request.getTenantSegment();
        }
        Object metaSegment = scopedValue(request, "meta", "tenantSegment");
        return metaSegment == null ? null : String.valueOf(metaSegment);
    }

    private Object scopedValue(DrtEvaluateRequest request, String scope, String key) {
        if (request.getContext() == null) {
            return null;
        }
        Map<String, Object> values = request.getContext().get(scope);
        if (values == null) {
            values = request.getContext().get(scope.toUpperCase());
        }
        return values == null ? null : values.get(key);
    }

    private int stableBucket(Long tenantId, String decisionCode, String routingKey, String salt) {
        String input = tenantId + ":" + decisionCode + ":" + routingKey + ":" + (salt == null ? "" : salt);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            int value = ((digest[0] & 0xff) << 24) | ((digest[1] & 0xff) << 16)
                    | ((digest[2] & 0xff) << 8) | (digest[3] & 0xff);
            return Math.floorMod(value, 100);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private int clampPercentage(Integer percentage) {
        if (percentage == null) {
            return 0;
        }
        return Math.max(0, Math.min(percentage, 100));
    }

    private void touchAudit(DecisionRolloutPolicyEntity entity, String action, String note) {
        entity.setAuditJson(audit(action, note));
        entity.setUpdatedAt(Instant.now());
    }

    private ObjectNode audit(String action, String note) {
        ObjectNode audit = objectMapper.createObjectNode();
        audit.put("action", action);
        audit.put("by", currentUserPid());
        audit.put("at", Instant.now().toString());
        if (note != null && !note.isBlank()) {
            audit.put("note", note);
        }
        return audit;
    }

    private String note(DecisionRolloutActionRequest request) {
        return request == null ? null : request.getNote();
    }

    private String rolloutSortColumn(String sortField) {
        if (!StringUtils.hasText(sortField)) {
            return "created_at";
        }
        return switch (sortField.trim()) {
            case "decisionCode" -> "decision_code";
            case "baselineVersion" -> "baseline_version";
            case "candidateVersion" -> "candidate_version";
            case "status" -> "status";
            case "percentage" -> "percentage";
            case "startedAt" -> "started_at";
            case "endedAt" -> "ended_at";
            case "updatedAt" -> "updated_at";
            case "createdAt" -> "created_at";
            default -> "created_at";
        };
    }

    private String currentUserPid() {
        return MetaContext.exists() ? MetaContext.getCurrentUserPid() : null;
    }

    private Long requireTenant() {
        Long tid = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
        if (tid == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Tenant context required");
        }
        return tid;
    }

    private DecisionRolloutDTO toDTO(DecisionRolloutPolicyEntity entity) {
        if (entity == null) {
            return null;
        }
        DecisionRolloutDTO dto = new DecisionRolloutDTO();
        dto.setPid(entity.getPid());
        dto.setDecisionCode(entity.getDecisionCode());
        dto.setBaselineVersion(entity.getBaselineVersion());
        dto.setCandidateVersion(entity.getCandidateVersion());
        dto.setStatus(entity.getStatus());
        dto.setPercentage(entity.getPercentage());
        dto.setCohort(entity.getCohortJson());
        dto.setSegment(entity.getSegmentJson());
        dto.setRoutingKeyExpr(entity.getRoutingKeyExpr());
        dto.setSalt(entity.getSalt());
        dto.setStartedBy(entity.getStartedBy());
        dto.setStartedAt(entity.getStartedAt());
        dto.setEndedBy(entity.getEndedBy());
        dto.setEndedAt(entity.getEndedAt());
        dto.setAudit(entity.getAuditJson());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }

    private record ServingPolicyCacheKey(Long tenantId, String decisionCode) {}

    private record ServingPolicyCacheEntry(DecisionRolloutPolicyEntity policy, Instant servingUpdatedAt) {
        boolean matches(Instant currentServingUpdatedAt) {
            return Objects.equals(servingUpdatedAt, currentServingUpdatedAt);
        }
    }
}
