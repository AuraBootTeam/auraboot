package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.decision.ast.ConditionAstEvaluator;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.EvalTrace;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.decision.dto.ConditionFragmentCreateRequest;
import com.auraboot.framework.decision.dto.ConditionFragmentDTO;
import com.auraboot.framework.decision.dto.ConditionFragmentEvaluateRequest;
import com.auraboot.framework.decision.dto.ConditionFragmentEvaluationDTO;
import com.auraboot.framework.decision.dto.ConditionFragmentImpactDTO;
import com.auraboot.framework.decision.dto.ConditionFragmentVersionCreateRequest;
import com.auraboot.framework.decision.dto.ConditionFragmentVersionUpdateRequest;
import com.auraboot.framework.decision.dto.DecisionImpactRefDTO;
import com.auraboot.framework.decision.entity.ConditionFragmentEntity;
import com.auraboot.framework.decision.entity.DecisionUsageRefEntity;
import com.auraboot.framework.decision.mapper.ConditionFragmentMapper;
import com.auraboot.framework.decision.mapper.DecisionUsageRefMapper;
import com.auraboot.framework.decision.model.VersionStatus;
import com.auraboot.framework.decision.rule.ConditionSpec;
import com.auraboot.framework.decision.rule.RuleReferenceCollector;
import com.auraboot.framework.decision.rule.RuleReferenceSet;
import com.auraboot.framework.decision.service.ConditionFragmentService;
import com.auraboot.framework.exception.ValidationException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tenant-scoped condition fragment service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConditionFragmentServiceImpl implements ConditionFragmentService {

    private static final String TARGET_TYPE_CONDITION_FRAGMENT = "CONDITION_FRAGMENT";

    private final ConditionFragmentMapper fragmentMapper;
    private final DecisionUsageRefMapper usageRefMapper;
    private final ObjectMapper objectMapper;
    private final ConditionAstEvaluator evaluator = new ConditionAstEvaluator();

    private Long requireTenant() {
        Long tid = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
        if (tid == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Condition fragment not found");
        }
        return tid;
    }

    @Transactional
    @Override
    public ConditionFragmentDTO create(ConditionFragmentCreateRequest request) {
        Long tid = requireTenant();
        if (fragmentMapper.findLatestByTenantAndCode(tid, request.getFragmentCode()) != null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Condition fragment code already exists: " + request.getFragmentCode());
        }

        ConditionSpec spec = parseSpec(request.getConditionSpec());
        RuleReferenceSet refs = RuleReferenceCollector.collect(spec);
        Instant now = Instant.now();
        String userPid = MetaContext.getCurrentUserPid();

        ConditionFragmentEntity entity = new ConditionFragmentEntity();
        entity.setPid(UniqueIdGenerator.generate());
        entity.setTenantId(tid);
        entity.setFragmentCode(request.getFragmentCode());
        entity.setFragmentName(request.getFragmentName());
        entity.setDescription(request.getDescription());
        entity.setScopeType(request.getScopeType());
        entity.setScopeRef(request.getScopeRef());
        entity.setVersion(1);
        entity.setStatus("DRAFT");
        entity.setConditionSpecJson(request.getConditionSpec());
        entity.setFieldRefsJson(objectMapper.valueToTree(refs.fieldRefs()));
        entity.setDecisionRefsJson(objectMapper.valueToTree(refs.decisionRefs()));
        entity.setOwnerModule(request.getOwnerModule());
        entity.setEnabled(request.getEnabled() == null || request.getEnabled());
        entity.setCreatedBy(userPid);
        entity.setCreatedAt(now);
        entity.setUpdatedBy(userPid);
        entity.setUpdatedAt(now);
        fragmentMapper.insert(entity);
        log.info("Condition fragment created: code={}, pid={}", entity.getFragmentCode(), entity.getPid());
        return toDTO(entity);
    }

    @Transactional
    @Override
    public ConditionFragmentDTO createVersion(String fragmentCode, ConditionFragmentVersionCreateRequest request) {
        Long tid = requireTenant();
        ConditionFragmentEntity latest = loadLatest(fragmentCode);
        VersionStatus latestStatus = VersionStatus.valueOf(latest.getStatus());
        if (!latestStatus.isImmutable()) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Cannot create a new condition fragment version while latest version is " + latestStatus);
        }

        ConditionSpec spec = parseSpec(request.getConditionSpec());
        RuleReferenceSet refs = RuleReferenceCollector.collect(spec);
        Instant now = Instant.now();
        String userPid = MetaContext.getCurrentUserPid();
        Integer maxVersion = fragmentMapper.findMaxVersion(tid, fragmentCode);

        ConditionFragmentEntity entity = new ConditionFragmentEntity();
        entity.setPid(UniqueIdGenerator.generate());
        entity.setTenantId(tid);
        entity.setFragmentCode(fragmentCode);
        entity.setFragmentName(valueOrDefault(request.getFragmentName(), latest.getFragmentName()));
        entity.setDescription(valueOrDefault(request.getDescription(), latest.getDescription()));
        entity.setScopeType(valueOrDefault(request.getScopeType(), latest.getScopeType()));
        entity.setScopeRef(valueOrDefault(request.getScopeRef(), latest.getScopeRef()));
        entity.setVersion((maxVersion == null ? 0 : maxVersion) + 1);
        entity.setStatus(VersionStatus.DRAFT.name());
        entity.setConditionSpecJson(request.getConditionSpec());
        entity.setFieldRefsJson(objectMapper.valueToTree(refs.fieldRefs()));
        entity.setDecisionRefsJson(objectMapper.valueToTree(refs.decisionRefs()));
        entity.setOwnerModule(valueOrDefault(request.getOwnerModule(), latest.getOwnerModule()));
        entity.setEnabled(request.getEnabled() == null ? latest.getEnabled() : request.getEnabled());
        entity.setCreatedBy(userPid);
        entity.setCreatedAt(now);
        entity.setUpdatedBy(userPid);
        entity.setUpdatedAt(now);
        fragmentMapper.insert(entity);
        log.info("Condition fragment draft version created: code={}, pid={}, version={}",
                fragmentCode, entity.getPid(), entity.getVersion());
        return toDTO(entity);
    }

    @Transactional
    @Override
    public ConditionFragmentDTO updateDraft(String pid, ConditionFragmentVersionUpdateRequest request) {
        ConditionFragmentEntity entity = loadOwned(pid);
        VersionStatus current = VersionStatus.valueOf(entity.getStatus());
        if (current != VersionStatus.DRAFT && current != VersionStatus.VALIDATED
                && current != VersionStatus.REJECTED) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Cannot update condition fragment version from status " + current
                            + ". Only DRAFT, VALIDATED or REJECTED versions are editable.");
        }

        ConditionSpec spec = parseSpec(request.getConditionSpec());
        RuleReferenceSet refs = RuleReferenceCollector.collect(spec);
        entity.setFragmentName(valueOrDefault(request.getFragmentName(), entity.getFragmentName()));
        entity.setDescription(valueOrDefault(request.getDescription(), entity.getDescription()));
        entity.setScopeType(valueOrDefault(request.getScopeType(), entity.getScopeType()));
        entity.setScopeRef(valueOrDefault(request.getScopeRef(), entity.getScopeRef()));
        entity.setOwnerModule(valueOrDefault(request.getOwnerModule(), entity.getOwnerModule()));
        entity.setEnabled(request.getEnabled() == null ? entity.getEnabled() : request.getEnabled());
        entity.setStatus(VersionStatus.DRAFT.name());
        entity.setConditionSpecJson(request.getConditionSpec());
        entity.setFieldRefsJson(objectMapper.valueToTree(refs.fieldRefs()));
        entity.setDecisionRefsJson(objectMapper.valueToTree(refs.decisionRefs()));
        entity.setUpdatedBy(MetaContext.getCurrentUserPid());
        entity.setUpdatedAt(Instant.now());
        fragmentMapper.updateById(entity);
        log.info("Condition fragment draft updated: code={}, pid={}, version={}",
                entity.getFragmentCode(), entity.getPid(), entity.getVersion());
        return toDTO(entity);
    }

    @Override
    public ConditionFragmentDTO findByCode(String fragmentCode) {
        ConditionFragmentEntity entity = loadLatest(fragmentCode);
        return toDTO(entity);
    }

    @Override
    public List<ConditionFragmentDTO> listVersions(String fragmentCode) {
        Long tid = requireTenant();
        return fragmentMapper.findAllByTenantAndCode(tid, fragmentCode).stream()
                .map(this::toDTO)
                .toList();
    }

    @Transactional
    @Override
    public ConditionFragmentDTO validate(String pid) {
        ConditionFragmentEntity entity = loadOwned(pid);
        VersionStatus current = VersionStatus.valueOf(entity.getStatus());
        if (current.isImmutable()) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Cannot validate an immutable condition fragment version (status=" + current + ")");
        }

        ConditionSpec spec = parseSpec(entity.getConditionSpecJson());
        RuleReferenceSet refs = RuleReferenceCollector.collect(spec);
        entity.setStatus(VersionStatus.VALIDATED.name());
        entity.setFieldRefsJson(objectMapper.valueToTree(refs.fieldRefs()));
        entity.setDecisionRefsJson(objectMapper.valueToTree(refs.decisionRefs()));
        entity.setUpdatedBy(MetaContext.getCurrentUserPid());
        entity.setUpdatedAt(Instant.now());
        fragmentMapper.updateById(entity);
        return toDTO(entity);
    }

    @Transactional
    @Override
    public ConditionFragmentDTO publish(String pid, boolean impactAcknowledged) {
        ConditionFragmentEntity entity = loadOwned(pid);
        VersionStatus current = VersionStatus.valueOf(entity.getStatus());
        if (!current.canTransitionTo(VersionStatus.PUBLISHED)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Cannot publish condition fragment from status " + current + ". Must be VALIDATED first.");
        }

        Long tid = requireTenant();
        List<DecisionImpactRefDTO> incoming = incomingRefs(tid, entity);
        if (entity.getVersion() != null && entity.getVersion() > 1 && !incoming.isEmpty() && !impactAcknowledged) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Impact acknowledgement required before publishing condition fragment: "
                            + incoming.size() + " downstream consumers reference " + entity.getFragmentCode());
        }

        Instant now = Instant.now();
        String userPid = MetaContext.getCurrentUserPid();
        fragmentMapper.deprecateOtherPublished(tid, entity.getFragmentCode(), entity.getPid(), userPid, now);
        entity.setStatus(VersionStatus.PUBLISHED.name());
        entity.setPublishedBy(userPid);
        entity.setPublishedAt(now);
        entity.setUpdatedBy(userPid);
        entity.setUpdatedAt(now);
        fragmentMapper.updateById(entity);
        return toDTO(entity);
    }

    @Override
    public PageResult<ConditionFragmentDTO> list(String keyword, String scopeType, String scopeRef, int page, int size) {
        Long tid = requireTenant();
        LambdaQueryWrapper<ConditionFragmentEntity> w = new LambdaQueryWrapper<>();
        w.eq(ConditionFragmentEntity::getTenantId, tid);
        if (StringUtils.hasText(keyword)) {
            w.and(q -> q.like(ConditionFragmentEntity::getFragmentCode, keyword)
                    .or().like(ConditionFragmentEntity::getFragmentName, keyword));
        }
        if (StringUtils.hasText(scopeType)) {
            w.eq(ConditionFragmentEntity::getScopeType, scopeType);
        }
        if (StringUtils.hasText(scopeRef)) {
            w.eq(ConditionFragmentEntity::getScopeRef, scopeRef);
        }
        w.orderByDesc(ConditionFragmentEntity::getCreatedAt);
        Page<ConditionFragmentEntity> pageResult = fragmentMapper.selectPage(new Page<>(page, size), w);
        PageResult<ConditionFragmentDTO> result = new PageResult<>();
        result.setRecords(pageResult.getRecords().stream().map(this::toDTO).toList());
        result.setTotal(pageResult.getTotal());
        result.setCurrent((long) page);
        result.setSize((long) size);
        result.setPages(pageResult.getPages());
        result.setHasPrevious(page > 1);
        result.setHasNext(page < pageResult.getPages());
        return result;
    }

    @Override
    public ConditionFragmentEvaluationDTO evaluate(String fragmentCode, ConditionFragmentEvaluateRequest request) {
        ConditionFragmentEntity entity = loadForEvaluation(fragmentCode);
        ConditionSpec spec = parseSpec(entity.getConditionSpecJson());
        EvalTrace trace = evaluator.evaluate(spec.root(), toDecisionContext(request == null ? null : request.getContext()));
        ConditionFragmentEvaluationDTO dto = new ConditionFragmentEvaluationDTO();
        dto.setFragmentCode(entity.getFragmentCode());
        dto.setVersion(entity.getVersion());
        dto.setResult(trace.result().name());
        dto.setMatched(trace.isMatch());
        dto.setTrace(trace);
        return dto;
    }

    @Override
    public ConditionFragmentImpactDTO impact(String fragmentCode) {
        Long tid = requireTenant();
        ConditionFragmentEntity fragment = loadLatest(fragmentCode);
        List<DecisionImpactRefDTO> incoming = incomingRefs(tid, fragment);
        ConditionFragmentImpactDTO dto = new ConditionFragmentImpactDTO();
        dto.setFragmentCode(fragmentCode);
        dto.setIncoming(incoming);
        dto.setIncomingCount(incoming.size());
        return dto;
    }

    private ConditionFragmentEntity loadLatest(String fragmentCode) {
        Long tid = requireTenant();
        ConditionFragmentEntity entity = fragmentMapper.findLatestByTenantAndCode(tid, fragmentCode);
        if (entity == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Condition fragment not found: " + fragmentCode);
        }
        return entity;
    }

    private ConditionFragmentEntity loadOwned(String pid) {
        Long tid = requireTenant();
        ConditionFragmentEntity entity = fragmentMapper.findByTenantAndPid(tid, pid);
        if (entity == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Condition fragment version not found: " + pid);
        }
        return entity;
    }

    private ConditionFragmentEntity loadForEvaluation(String fragmentCode) {
        Long tid = requireTenant();
        ConditionFragmentEntity bindable = fragmentMapper.findLatestBindableByTenantAndCode(tid, fragmentCode);
        if (bindable != null) {
            return bindable;
        }
        return loadLatest(fragmentCode);
    }

    private ConditionSpec parseSpec(JsonNode node) {
        try {
            ConditionSpec spec = objectMapper.treeToValue(node, ConditionSpec.class);
            if (spec == null || spec.root() == null) {
                throw new ValidationException(ResponseCode.CommonValidationFailed, "Condition fragment root is required");
            }
            return spec;
        } catch (ValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Invalid condition fragment spec: " + e.getMessage());
        }
    }

    private DecisionContext toDecisionContext(Map<String, Object> rawContext) {
        Map<Scope, Object> scopes = new HashMap<>();
        if (rawContext != null) {
            rawContext.forEach((key, value) -> scopes.put(Scope.fromCode(key), value));
        }
        return DecisionContext.of(scopes);
    }

    private ConditionFragmentDTO toDTO(ConditionFragmentEntity e) {
        ConditionFragmentDTO dto = new ConditionFragmentDTO();
        dto.setId(e.getId());
        dto.setPid(e.getPid());
        dto.setTenantId(e.getTenantId());
        dto.setFragmentCode(e.getFragmentCode());
        dto.setFragmentName(e.getFragmentName());
        dto.setDescription(e.getDescription());
        dto.setScopeType(e.getScopeType());
        dto.setScopeRef(e.getScopeRef());
        dto.setVersion(e.getVersion());
        dto.setStatus(e.getStatus());
        dto.setConditionSpec(e.getConditionSpecJson());
        dto.setFieldRefs(readStringList(e.getFieldRefsJson()));
        dto.setDecisionRefs(readStringList(e.getDecisionRefsJson()));
        dto.setOwnerModule(e.getOwnerModule());
        dto.setEnabled(e.getEnabled());
        dto.setPublishedBy(e.getPublishedBy());
        dto.setPublishedAt(e.getPublishedAt());
        dto.setCreatedBy(e.getCreatedBy());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedBy(e.getUpdatedBy());
        dto.setUpdatedAt(e.getUpdatedAt());
        return dto;
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        return objectMapper.convertValue(node, new TypeReference<>() {});
    }

    private DecisionImpactRefDTO toImpactRef(DecisionUsageRefEntity ref) {
        Map<String, Object> metadata = metadataFrom(ref.getMetadataJson());
        DecisionImpactRefDTO dto = new DecisionImpactRefDTO();
        dto.setSourceType(ref.getSourceType());
        dto.setSourceCode(ref.getSourceCode());
        dto.setSourceName(metadata.get("sourceName") instanceof String sourceName ? sourceName : null);
        dto.setSourceVersion(ref.getSourceVersion());
        dto.setSourcePid(ref.getSourcePid());
        dto.setTargetType(ref.getTargetType());
        dto.setTargetCode(ref.getTargetCode());
        dto.setTargetPath(ref.getTargetPath());
        dto.setBinding(ref.getBinding());
        dto.setMetadata(metadata);
        return dto;
    }

    private List<DecisionImpactRefDTO> incomingRefs(Long tid, ConditionFragmentEntity fragment) {
        Map<String, DecisionImpactRefDTO> refs = new LinkedHashMap<>();
        usageRefMapper.findTargetRefs(tid, TARGET_TYPE_CONDITION_FRAGMENT, fragment.getFragmentCode())
                .stream()
                .map(this::toImpactRef)
                .forEach(ref -> refs.put(impactKey(ref), ref));

        for (String decisionRef : readStringList(fragment.getDecisionRefsJson())) {
            usageRefMapper.findTargetRefs(tid, "DECISION", decisionRef)
                    .stream()
                    .filter(ref -> matchesFragmentScope(fragment, ref))
                    .map(this::toImpactRef)
                    .forEach(ref -> refs.put(impactKey(ref), ref));
        }
        return new ArrayList<>(refs.values());
    }

    private boolean matchesFragmentScope(ConditionFragmentEntity fragment, DecisionUsageRefEntity ref) {
        String scopeType = normalize(fragment.getScopeType());
        String scopeRef = fragment.getScopeRef();
        if (!StringUtils.hasText(scopeType)) {
            return true;
        }
        return switch (scopeType) {
            case "SLA" -> "SLA_RULE".equals(ref.getSourceType());
            case "BPM" -> "BPM_PROCESS".equals(ref.getSourceType()) && sourceRefMatches(ref, scopeRef);
            case "AUTOMATION" -> "AUTOMATION".equals(ref.getSourceType()) && sourceRefMatches(ref, scopeRef);
            case "EVENT_POLICY" -> "EVENT_POLICY".equals(ref.getSourceType()) && sourceRefMatches(ref, scopeRef);
            case "PERMISSION" -> "PERMISSION_POLICY".equals(ref.getSourceType()) && sourceRefMatches(ref, scopeRef);
            case "MODEL" -> sourceRefMatches(ref, scopeRef);
            default -> scopeType.equals(ref.getSourceType()) && sourceRefMatches(ref, scopeRef);
        };
    }

    private boolean sourceRefMatches(DecisionUsageRefEntity ref, String scopeRef) {
        if (!StringUtils.hasText(scopeRef)) {
            return true;
        }
        if (scopeRef.equals(ref.getSourceCode())) {
            return true;
        }
        return scopeRef.equals(metadataString(ref, "modelCode"))
                || scopeRef.equals(metadataString(ref, "processKey"))
                || scopeRef.equals(metadataString(ref, "policyCode"))
                || scopeRef.equals(metadataString(ref, "targetKey"));
    }

    private String metadataString(DecisionUsageRefEntity ref, String key) {
        Object value = metadataFrom(ref.getMetadataJson()).get(key);
        return value == null ? null : String.valueOf(value);
    }

    private Map<String, Object> metadataFrom(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
    }

    private String impactKey(DecisionImpactRefDTO ref) {
        return String.join("|",
                valueOrDefault(ref.getSourceType(), ""),
                valueOrDefault(ref.getSourceCode(), ""),
                valueOrDefault(ref.getSourcePid(), ""),
                valueOrDefault(ref.getTargetType(), ""),
                valueOrDefault(ref.getTargetCode(), ""),
                valueOrDefault(ref.getTargetPath(), ""),
                valueOrDefault(ref.getBinding(), ""));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private String valueOrDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }
}
