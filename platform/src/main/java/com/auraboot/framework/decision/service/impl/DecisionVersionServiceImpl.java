package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.decision.dto.DrtVersionCreateRequest;
import com.auraboot.framework.decision.dto.DrtVersionDTO;
import com.auraboot.framework.decision.entity.DrtVersionEntity;
import com.auraboot.framework.decision.mapper.DrtVersionMapper;
import com.auraboot.framework.decision.model.DecisionKind;
import com.auraboot.framework.decision.model.DecisionValidateResult;
import com.auraboot.framework.decision.model.RuntimeAdapter;
import com.auraboot.framework.decision.model.VersionStatus;
import com.auraboot.framework.decision.runtime.DecisionRuntime;
import com.auraboot.framework.decision.runtime.ResolvedDecision;
import com.auraboot.framework.decision.service.DecisionVersionService;
import com.auraboot.framework.exception.ValidationException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Decision version lifecycle service implementation.
 *
 * <p>State machine enforced by {@link VersionStatus#canTransitionTo}.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DecisionVersionServiceImpl implements DecisionVersionService {

    private final DrtVersionMapper versionMapper;
    private final DecisionRuntime decisionRuntime;
    private final ObjectMapper objectMapper;

    // ─── tenant guard ────────────────────────────────────────────────────────

    private Long requireTenant() {
        Long tid = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
        if (tid == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Decision version not found");
        }
        return tid;
    }

    private DrtVersionEntity loadOwned(String pid) {
        Long tid = requireTenant();
        LambdaQueryWrapper<DrtVersionEntity> w = new LambdaQueryWrapper<>();
        w.eq(DrtVersionEntity::getPid, pid)
         .eq(DrtVersionEntity::getTenantId, tid);
        DrtVersionEntity entity = versionMapper.selectOne(w);
        if (entity == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Decision version not found: " + pid);
        }
        return entity;
    }

    // ─── public API ──────────────────────────────────────────────────────────

    @Transactional
    @Override
    public DrtVersionDTO createDraft(String decisionCode, DrtVersionCreateRequest request) {
        Long tid = requireTenant();

        // version = max + 1 (or 1 if first)
        Integer maxVer = versionMapper.findMaxVersion(tid, decisionCode);
        int nextVer = (maxVer == null ? 0 : maxVer) + 1;

        DrtVersionEntity entity = new DrtVersionEntity();
        entity.setPid(UniqueIdGenerator.generate());
        entity.setTenantId(tid);
        entity.setDecisionCode(decisionCode);
        entity.setVersion(nextVer);
        entity.setVersionTag(request.getVersionTag());
        entity.setStatus(VersionStatus.DRAFT.name());
        entity.setKind(request.getKind());
        entity.setRuntimeAdapter(request.getRuntimeAdapter());
        entity.setContentFormat("JSON");
        entity.setContentJson(request.getContentJson());
        entity.setInputSchemaJson(request.getInputSchemaJson());
        entity.setOutputSchemaJson(request.getOutputSchemaJson());
        entity.setContextSchemaJson(request.getContextSchemaJson());
        if (request.getContentJson() != null) {
            entity.setContentHash(sha256(request.getContentJson().toString()));
        }
        entity.setCreatedAt(Instant.now());

        versionMapper.insert(entity);

        log.info("Decision version draft created: pid={}, code={}, version={}",
                entity.getPid(), decisionCode, nextVer);
        return toDTO(entity);
    }

    @Transactional
    @Override
    public DecisionValidateResult validate(String pid) {
        DrtVersionEntity entity = loadOwned(pid);

        VersionStatus current = VersionStatus.valueOf(entity.getStatus());
        // Validation is allowed from DRAFT only — VALIDATED can be re-validated if needed
        if (current.isImmutable()) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Cannot validate an immutable version (status=" + current + ")");
        }

        ResolvedDecision resolved = toResolved(entity);
        DecisionValidateResult result = decisionRuntime.validate(resolved);

        if (result.valid()) {
            entity.setStatus(VersionStatus.VALIDATED.name());

            // Persist field_refs and function_refs extracted by the validator
            if (!result.fieldRefs().isEmpty()) {
                entity.setFieldRefsJson(toJsonArray(result.fieldRefs()));
            }
            if (!result.functionRefs().isEmpty()) {
                entity.setFunctionRefsJson(toJsonArray(result.functionRefs()));
            }

            versionMapper.updateById(entity);
            log.info("Decision version validated: pid={}", pid);
        } else {
            log.warn("Decision version validation failed: pid={}, errors={}", pid, result.errors());
        }

        return result;
    }

    @Transactional
    @Override
    public DrtVersionDTO publish(String pid) {
        DrtVersionEntity entity = loadOwned(pid);

        VersionStatus current = VersionStatus.valueOf(entity.getStatus());
        if (!current.canTransitionTo(VersionStatus.PUBLISHED)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Cannot publish from status " + current + ". Must be VALIDATED first.");
        }

        String userPid = MetaContext.getCurrentUserPid();
        Instant now = Instant.now();

        entity.setStatus(VersionStatus.PUBLISHED.name());
        entity.setPublishedBy(userPid);
        entity.setPublishedAt(now);

        versionMapper.updateById(entity);

        log.info("Decision version published: pid={}, code={}, version={}",
                pid, entity.getDecisionCode(), entity.getVersion());
        return toDTO(entity);
    }

    @Transactional
    @Override
    public DrtVersionDTO submitForApproval(String pid) {
        DrtVersionEntity entity = loadOwned(pid);
        VersionStatus current = VersionStatus.valueOf(entity.getStatus());
        if (!current.canTransitionTo(VersionStatus.PENDING_APPROVAL)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Cannot submit for approval from status " + current + ". Must be VALIDATED first.");
        }
        entity.setStatus(VersionStatus.PENDING_APPROVAL.name());
        versionMapper.updateById(entity);
        log.info("Decision version submitted for approval: pid={}, code={}", pid, entity.getDecisionCode());
        return toDTO(entity);
    }

    @Transactional
    @Override
    public DrtVersionDTO approve(String pid, String note) {
        DrtVersionEntity entity = loadOwned(pid);
        VersionStatus current = VersionStatus.valueOf(entity.getStatus());
        if (current != VersionStatus.PENDING_APPROVAL) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Cannot approve from status " + current + ". Must be PENDING_APPROVAL (submit for approval first).");
        }
        String userPid = MetaContext.getCurrentUserPid();
        Instant now = Instant.now();
        entity.setStatus(VersionStatus.PUBLISHED.name());
        entity.setApprovalBy(userPid);
        entity.setApprovalAt(now);
        entity.setApprovalNote(note);
        entity.setPublishedBy(userPid);
        entity.setPublishedAt(now);
        versionMapper.updateById(entity);
        log.info("Decision version approved + published: pid={}, code={}, by={}",
                pid, entity.getDecisionCode(), userPid);
        return toDTO(entity);
    }

    @Transactional
    @Override
    public DrtVersionDTO reject(String pid, String note) {
        DrtVersionEntity entity = loadOwned(pid);
        VersionStatus current = VersionStatus.valueOf(entity.getStatus());
        if (!current.canTransitionTo(VersionStatus.REJECTED)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Cannot reject from status " + current + ". Must be PENDING_APPROVAL.");
        }
        entity.setStatus(VersionStatus.REJECTED.name());
        entity.setApprovalBy(MetaContext.getCurrentUserPid());
        entity.setApprovalAt(Instant.now());
        entity.setApprovalNote(note);
        versionMapper.updateById(entity);
        log.info("Decision version rejected: pid={}, code={}", pid, entity.getDecisionCode());
        return toDTO(entity);
    }

    @Override
    public DrtVersionDTO findByPid(String pid) {
        Long tid = requireTenant();
        LambdaQueryWrapper<DrtVersionEntity> w = new LambdaQueryWrapper<>();
        w.eq(DrtVersionEntity::getPid, pid)
         .eq(DrtVersionEntity::getTenantId, tid);
        DrtVersionEntity entity = versionMapper.selectOne(w);
        return entity != null ? toDTO(entity) : null;
    }

    @Override
    public List<DrtVersionDTO> listByCode(String decisionCode) {
        Long tid = requireTenant();
        LambdaQueryWrapper<DrtVersionEntity> w = new LambdaQueryWrapper<>();
        w.eq(DrtVersionEntity::getTenantId, tid)
         .eq(DrtVersionEntity::getDecisionCode, decisionCode)
         .orderByDesc(DrtVersionEntity::getVersion);
        return versionMapper.selectList(w).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    /**
     * Build a {@link ResolvedDecision} from the entity row for runtime delegation.
     */
    ResolvedDecision toResolved(DrtVersionEntity entity) {
        DecisionKind kind;
        try {
            kind = DecisionKind.valueOf(entity.getKind());
        } catch (IllegalArgumentException e) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Unknown decision kind: " + entity.getKind());
        }
        RuntimeAdapter adapter;
        try {
            adapter = RuntimeAdapter.valueOf(entity.getRuntimeAdapter());
        } catch (IllegalArgumentException e) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Unknown runtime adapter: " + entity.getRuntimeAdapter());
        }
        VersionStatus status = VersionStatus.valueOf(entity.getStatus());
        JsonNode content = entity.getContentJson();

        return new ResolvedDecision(
                entity.getDecisionCode(),
                entity.getVersion(),
                entity.getVersionTag(),
                status,
                kind,
                adapter,
                content
        );
    }

    private JsonNode toJsonArray(List<String> list) {
        ArrayNode node = objectMapper.createArrayNode();
        list.forEach(node::add);
        return node;
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JVM spec
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private DrtVersionDTO toDTO(DrtVersionEntity e) {
        if (e == null) return null;
        DrtVersionDTO dto = new DrtVersionDTO();
        dto.setId(e.getId());
        dto.setPid(e.getPid());
        dto.setTenantId(e.getTenantId());
        dto.setDecisionCode(e.getDecisionCode());
        dto.setVersion(e.getVersion());
        dto.setVersionTag(e.getVersionTag());
        dto.setStatus(e.getStatus());
        dto.setKind(e.getKind());
        dto.setRuntimeAdapter(e.getRuntimeAdapter());
        dto.setContentFormat(e.getContentFormat());
        dto.setContentJson(e.getContentJson());
        dto.setInputSchemaJson(e.getInputSchemaJson());
        dto.setOutputSchemaJson(e.getOutputSchemaJson());
        dto.setContextSchemaJson(e.getContextSchemaJson());
        dto.setContentHash(e.getContentHash());
        dto.setEffectiveFrom(e.getEffectiveFrom());
        dto.setEffectiveTo(e.getEffectiveTo());
        dto.setPublishedBy(e.getPublishedBy());
        dto.setPublishedAt(e.getPublishedAt());
        dto.setCreatedAt(e.getCreatedAt());
        // Deserialise field/function refs from JsonNode arrays
        if (e.getFieldRefsJson() != null && e.getFieldRefsJson().isArray()) {
            dto.setFieldRefs(parseStringArray(e.getFieldRefsJson()));
        }
        if (e.getFunctionRefsJson() != null && e.getFunctionRefsJson().isArray()) {
            dto.setFunctionRefs(parseStringArray(e.getFunctionRefsJson()));
        }
        return dto;
    }

    private List<String> parseStringArray(JsonNode node) {
        return objectMapper.convertValue(node,
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
    }
}
