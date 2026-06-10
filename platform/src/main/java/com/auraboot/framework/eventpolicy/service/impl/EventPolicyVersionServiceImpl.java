package com.auraboot.framework.eventpolicy.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.decision.model.VersionStatus;
import com.auraboot.framework.decision.service.DecisionUsageIndexService;
import com.auraboot.framework.eventpolicy.entity.DrtPolicyVersionEntity;
import com.auraboot.framework.eventpolicy.mapper.DrtPolicyVersionMapper;
import com.auraboot.framework.eventpolicy.model.ConflictStrategy;
import com.auraboot.framework.eventpolicy.model.DedupStrategy;
import com.auraboot.framework.eventpolicy.model.ExecutionMode;
import com.auraboot.framework.eventpolicy.model.FailureStrategy;
import com.auraboot.framework.eventpolicy.model.MatchMode;
import com.auraboot.framework.eventpolicy.model.PolicyPhase;
import com.auraboot.framework.eventpolicy.model.PolicyRule;
import com.auraboot.framework.eventpolicy.service.EventPolicyVersionService;
import com.auraboot.framework.exception.ValidationException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * EventPolicy version lifecycle service implementation.
 *
 * <p>State machine enforced by {@link VersionStatus#canTransitionTo}.
 * Validation deserializes rules_json into List&lt;PolicyRule&gt; and verifies each rule has either
 * a parseable ConditionNode or a decision binding.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventPolicyVersionServiceImpl implements EventPolicyVersionService {

    private final DrtPolicyVersionMapper versionMapper;
    private final ObjectMapper objectMapper;
    private final DecisionUsageIndexService usageIndexService;

    // ─── tenant guard ────────────────────────────────────────────────────────

    private Long requireTenant() {
        Long tid = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
        if (tid == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Tenant context required for EventPolicy version");
        }
        return tid;
    }

    private DrtPolicyVersionEntity loadOwned(String pid) {
        Long tid = requireTenant();
        LambdaQueryWrapper<DrtPolicyVersionEntity> w = new LambdaQueryWrapper<>();
        w.eq(DrtPolicyVersionEntity::getPid, pid)
         .eq(DrtPolicyVersionEntity::getTenantId, tid);
        DrtPolicyVersionEntity entity = versionMapper.selectOne(w);
        if (entity == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Event policy version not found: " + pid);
        }
        return entity;
    }

    // ─── public API ──────────────────────────────────────────────────────────

    @Transactional
    @Override
    public DrtPolicyVersionEntity createDraft(String policyCode,
                                               PolicyPhase phase,
                                               MatchMode matchMode,
                                               ExecutionMode executionMode,
                                               FailureStrategy failureStrategy,
                                               ConflictStrategy conflictStrategy,
                                               DedupStrategy dedupStrategy,
                                               JsonNode rulesJson) {
        Long tid = requireTenant();

        Integer maxVer = versionMapper.findMaxVersion(tid, policyCode);
        int nextVer = (maxVer == null ? 0 : maxVer) + 1;

        DrtPolicyVersionEntity entity = new DrtPolicyVersionEntity();
        entity.setPid(UniqueIdGenerator.generate());
        entity.setTenantId(tid);
        entity.setPolicyCode(policyCode);
        entity.setVersion(nextVer);
        entity.setStatus(VersionStatus.DRAFT.name());
        entity.setPhase(phase != null ? phase.name() : PolicyPhase.AFTER_COMMIT.name());
        entity.setMatchMode(matchMode != null ? matchMode.name() : MatchMode.COLLECT_ALL.name());
        entity.setExecutionMode(executionMode != null ? executionMode.name() : ExecutionMode.ORDERED.name());
        entity.setFailureStrategy(failureStrategy != null ? failureStrategy.name() : FailureStrategy.FAIL_FAST.name());
        entity.setConflictStrategy(conflictStrategy != null ? conflictStrategy.name() : ConflictStrategy.REJECT_ON_CONFLICT.name());
        entity.setDedupStrategy(dedupStrategy != null ? dedupStrategy.name() : DedupStrategy.BY_IDEMPOTENCY_KEY.name());
        entity.setRulesJson(rulesJson);
        if (rulesJson != null) {
            entity.setContentHash(sha256(rulesJson.toString()));
        }
        entity.setCreatedAt(Instant.now());

        versionMapper.insert(entity);

        log.info("EventPolicy version draft created: pid={}, code={}, version={}",
                entity.getPid(), policyCode, nextVer);
        return entity;
    }

    @Transactional
    @Override
    public DrtPolicyVersionEntity validate(String pid) {
        DrtPolicyVersionEntity entity = loadOwned(pid);

        VersionStatus current = VersionStatus.valueOf(entity.getStatus());
        if (current.isImmutable()) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Cannot validate an immutable version (status=" + current + ")");
        }

        // Deserialize rules_json into List<PolicyRule>; ConditionNode is Jackson-polymorphic.
        // If deserialization succeeds, the conditions are structurally valid.
        if (entity.getRulesJson() == null || entity.getRulesJson().isNull()) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "rules_json is required for validation");
        }
        try {
            List<PolicyRule> rules = objectMapper.convertValue(
                    entity.getRulesJson(),
                    new TypeReference<List<PolicyRule>>() {});
            if (rules.isEmpty()) {
                throw new ValidationException(ResponseCode.CommonValidationFailed,
                        "Event policy version must have at least one rule");
            }
            // Verify each rule has either a parseable condition or a decision binding.
            for (PolicyRule rule : rules) {
                if (rule.condition() == null && rule.decisionBinding() == null) {
                    throw new ValidationException(ResponseCode.CommonValidationFailed,
                            "Rule '" + rule.ruleCode() + "' has no condition or decisionBinding");
                }
                if (rule.decisionBinding() != null
                        && (rule.decisionBinding().decisionCode() == null
                        || rule.decisionBinding().decisionCode().isBlank())) {
                    throw new ValidationException(ResponseCode.CommonValidationFailed,
                            "Rule '" + rule.ruleCode() + "' decisionBinding.decisionCode is required");
                }
            }
        } catch (ValidationException ve) {
            throw ve;
        } catch (Exception e) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Event policy rules_json failed to parse: " + e.getMessage());
        }

        entity.setStatus(VersionStatus.VALIDATED.name());
        versionMapper.updateById(entity);
        usageIndexService.refreshSource("EVENT_POLICY", entity.getPid());

        log.info("EventPolicy version validated: pid={}", pid);
        return entity;
    }

    @Transactional
    @Override
    public DrtPolicyVersionEntity publish(String pid) {
        DrtPolicyVersionEntity entity = loadOwned(pid);

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

        // Re-compute content_hash on publish (in case rulesJson was updated in an earlier createDraft)
        if (entity.getRulesJson() != null) {
            entity.setContentHash(sha256(entity.getRulesJson().toString()));
        }

        versionMapper.updateById(entity);
        usageIndexService.refreshSource("EVENT_POLICY", entity.getPid());

        log.info("EventPolicy version published: pid={}, code={}, version={}",
                pid, entity.getPolicyCode(), entity.getVersion());
        return entity;
    }

    @Override
    public DrtPolicyVersionEntity findByPid(String pid) {
        Long tid = requireTenant();
        LambdaQueryWrapper<DrtPolicyVersionEntity> w = new LambdaQueryWrapper<>();
        w.eq(DrtPolicyVersionEntity::getPid, pid)
         .eq(DrtPolicyVersionEntity::getTenantId, tid);
        return versionMapper.selectOne(w);
    }

    @Override
    public List<DrtPolicyVersionEntity> listByCode(String policyCode) {
        Long tid = requireTenant();
        LambdaQueryWrapper<DrtPolicyVersionEntity> w = new LambdaQueryWrapper<>();
        w.eq(DrtPolicyVersionEntity::getTenantId, tid)
         .eq(DrtPolicyVersionEntity::getPolicyCode, policyCode)
         .orderByAsc(DrtPolicyVersionEntity::getVersion);
        return versionMapper.selectList(w);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
