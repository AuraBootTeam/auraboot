package com.auraboot.framework.eventpolicy.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.eventpolicy.entity.DrtPolicyDefinitionEntity;
import com.auraboot.framework.eventpolicy.mapper.DrtPolicyDefinitionMapper;
import com.auraboot.framework.eventpolicy.service.EventPolicyDefinitionService;
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

    @Override
    public List<DrtPolicyDefinitionEntity> findByEventAndTarget(String eventType, String targetType, String targetKey) {
        Long tid = requireTenant();
        return definitionMapper.findByEventAndTarget(tid, eventType, targetType, targetKey);
    }
}
