package com.auraboot.framework.eventpolicy.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.eventpolicy.dto.EventPolicyDefinitionSummary;
import com.auraboot.framework.eventpolicy.entity.DrtPolicyDefinitionEntity;
import com.auraboot.framework.eventpolicy.mapper.DrtPolicyDefinitionMapper;
import com.auraboot.framework.eventpolicy.mapper.DrtPolicyVersionMapper;
import com.auraboot.framework.eventpolicy.model.ConflictStrategy;
import com.auraboot.framework.eventpolicy.model.DedupStrategy;
import com.auraboot.framework.eventpolicy.model.ExecutionMode;
import com.auraboot.framework.eventpolicy.model.FailureStrategy;
import com.auraboot.framework.eventpolicy.model.MatchMode;
import com.auraboot.framework.eventpolicy.model.PolicyPhase;
import com.auraboot.framework.eventpolicy.service.EventPolicyDefinitionService;
import com.auraboot.framework.eventpolicy.service.EventPolicyVersionService;
import com.auraboot.framework.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * EventPolicy definition CRUD service implementation (tenant-scoped, mirroring DrtDefinitionServiceImpl).
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventPolicyDefinitionServiceImpl implements EventPolicyDefinitionService {

    private final DrtPolicyDefinitionMapper definitionMapper;
    private final DrtPolicyVersionMapper versionMapper;
    private final EventPolicyVersionService versionService;

    // ─── tenant guard ────────────────────────────────────────────────────────

    private Long requireTenant() {
        Long tid = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
        if (tid == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Tenant context required for EventPolicy");
        }
        return tid;
    }

    // ─── public API ──────────────────────────────────────────────────────────

    @Transactional
    @Override
    public DrtPolicyDefinitionEntity create(String policyCode, String policyName,
                                             String eventType, String targetType, String targetKey) {
        Long tid = requireTenant();

        if (definitionMapper.findByTenantAndCode(tid, policyCode) != null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Event policy code already exists: " + policyCode);
        }

        String userPid = MetaContext.getCurrentUserPid();
        Instant now = Instant.now();

        DrtPolicyDefinitionEntity entity = new DrtPolicyDefinitionEntity();
        entity.setPid(UniqueIdGenerator.generate());
        entity.setTenantId(tid);
        entity.setPolicyCode(policyCode);
        entity.setPolicyName(policyName);
        entity.setEventType(eventType);
        entity.setTargetType(targetType);
        entity.setTargetKey(targetKey);
        entity.setEnabled(true);
        entity.setCreatedBy(userPid);
        entity.setCreatedAt(now);
        entity.setUpdatedBy(userPid);
        entity.setUpdatedAt(now);

        definitionMapper.insert(entity);

        log.info("EventPolicy definition created: pid={}, code={}", entity.getPid(), policyCode);
        return entity;
    }

    @Override
    public DrtPolicyDefinitionEntity findByCode(String policyCode) {
        Long tid = requireTenant();
        return definitionMapper.findByTenantAndCode(tid, policyCode);
    }

    @Transactional
    @Override
    public DrtPolicyDefinitionEntity setEnabled(String policyCode, boolean enabled) {
        DrtPolicyDefinitionEntity entity = loadOwned(policyCode);
        entity.setEnabled(enabled);
        entity.setUpdatedBy(MetaContext.getCurrentUserPid());
        entity.setUpdatedAt(Instant.now());
        definitionMapper.updateById(entity);
        log.info("EventPolicy definition enabled changed: code={}, enabled={}", policyCode, enabled);
        return entity;
    }

    @Transactional
    @Override
    public DrtPolicyDefinitionEntity copy(String sourcePolicyCode, String newPolicyCode, String newPolicyName) {
        Long tid = requireTenant();
        DrtPolicyDefinitionEntity source = loadOwned(sourcePolicyCode);
        DrtPolicyDefinitionEntity copy = create(
                newPolicyCode,
                newPolicyName,
                source.getEventType(),
                source.getTargetType(),
                source.getTargetKey());

        var latest = versionMapper.findLatest(tid, sourcePolicyCode);
        if (latest != null) {
            versionService.createDraft(
                    newPolicyCode,
                    enumOrDefault(latest.getPhase(), PolicyPhase.class, PolicyPhase.AFTER_COMMIT),
                    enumOrDefault(latest.getMatchMode(), MatchMode.class, MatchMode.COLLECT_ALL),
                    enumOrDefault(latest.getExecutionMode(), ExecutionMode.class, ExecutionMode.ORDERED),
                    enumOrDefault(latest.getFailureStrategy(), FailureStrategy.class, FailureStrategy.FAIL_FAST),
                    enumOrDefault(latest.getConflictStrategy(), ConflictStrategy.class, ConflictStrategy.REJECT_ON_CONFLICT),
                    enumOrDefault(latest.getDedupStrategy(), DedupStrategy.class, DedupStrategy.BY_IDEMPOTENCY_KEY),
                    latest.getRulesJson());
        }

        log.info("EventPolicy definition copied: source={}, copy={}", sourcePolicyCode, newPolicyCode);
        return copy;
    }

    @Override
    public List<EventPolicyDefinitionSummary> listDefinitions(
            String keyword, String eventType, String targetType, String targetKey, String status) {
        Long tid = requireTenant();
        return definitionMapper.listDefinitions(tid, keyword, eventType, targetType, targetKey)
                .stream()
                .map(definition -> EventPolicyDefinitionSummary.of(
                        definition,
                        versionMapper.findLatest(tid, definition.getPolicyCode())))
                .filter(summary -> status == null || status.isBlank() || status.equals(summary.getStatus()))
                .toList();
    }

    @Override
    public List<DrtPolicyDefinitionEntity> findByEventAndTarget(String eventType, String targetType, String targetKey) {
        Long tid = requireTenant();
        return definitionMapper.findByEventAndTarget(tid, eventType, targetType, targetKey);
    }

    private DrtPolicyDefinitionEntity loadOwned(String policyCode) {
        Long tid = requireTenant();
        DrtPolicyDefinitionEntity entity = definitionMapper.findByTenantAndCode(tid, policyCode);
        if (entity == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Event policy definition not found: " + policyCode);
        }
        return entity;
    }

    private <T extends Enum<T>> T enumOrDefault(String value, Class<T> enumType, T fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Enum.valueOf(enumType, value);
    }
}
