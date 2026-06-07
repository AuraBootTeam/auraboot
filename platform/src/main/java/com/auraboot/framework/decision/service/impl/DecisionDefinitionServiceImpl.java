package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.decision.dto.DrtDefinitionCreateRequest;
import com.auraboot.framework.decision.dto.DrtDefinitionDTO;
import com.auraboot.framework.decision.entity.DrtDefinitionEntity;
import com.auraboot.framework.decision.mapper.DrtDefinitionMapper;
import com.auraboot.framework.decision.service.DecisionDefinitionService;
import com.auraboot.framework.exception.ValidationException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Decision definition CRUD service implementation (tenant-scoped, mirroring AutomationServiceImpl pattern).
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DecisionDefinitionServiceImpl implements DecisionDefinitionService {

    private final DrtDefinitionMapper definitionMapper;

    // ─── tenant guard ────────────────────────────────────────────────────────

    private Long requireTenant() {
        Long tid = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
        if (tid == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Decision definition not found");
        }
        return tid;
    }

    private DrtDefinitionEntity loadOwned(String pid) {
        Long tid = requireTenant();
        LambdaQueryWrapper<DrtDefinitionEntity> w = new LambdaQueryWrapper<>();
        w.eq(DrtDefinitionEntity::getPid, pid)
         .eq(DrtDefinitionEntity::getTenantId, tid);
        DrtDefinitionEntity entity = definitionMapper.selectOne(w);
        if (entity == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Decision definition not found: " + pid);
        }
        return entity;
    }

    // ─── public API ──────────────────────────────────────────────────────────

    @Transactional
    @Override
    public DrtDefinitionDTO create(DrtDefinitionCreateRequest request) {
        Long tid = requireTenant();

        // Guard: code must be unique within tenant
        if (definitionMapper.findByTenantAndCode(tid, request.getDecisionCode()) != null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Decision code already exists: " + request.getDecisionCode());
        }

        String userPid = MetaContext.getCurrentUserPid();
        Instant now = Instant.now();

        DrtDefinitionEntity entity = new DrtDefinitionEntity();
        entity.setPid(UniqueIdGenerator.generate());
        entity.setTenantId(tid);
        entity.setDecisionCode(request.getDecisionCode());
        entity.setDecisionName(request.getDecisionName());
        entity.setDescription(request.getDescription());
        entity.setScopeType(request.getScopeType());
        entity.setScopeRef(request.getScopeRef());
        entity.setOwnerModule(request.getOwnerModule());
        entity.setEnabled(request.getEnabled() != null ? request.getEnabled() : true);
        entity.setCreatedBy(userPid);
        entity.setCreatedAt(now);
        entity.setUpdatedBy(userPid);
        entity.setUpdatedAt(now);

        definitionMapper.insert(entity);

        log.info("Decision definition created: pid={}, code={}", entity.getPid(), entity.getDecisionCode());
        return toDTO(entity);
    }

    @Transactional
    @Override
    public DrtDefinitionDTO update(String pid, DrtDefinitionCreateRequest request) {
        DrtDefinitionEntity entity = loadOwned(pid);
        String userPid = MetaContext.getCurrentUserPid();

        if (StringUtils.hasText(request.getDecisionName())) {
            entity.setDecisionName(request.getDecisionName());
        }
        if (request.getDescription() != null) {
            entity.setDescription(request.getDescription());
        }
        if (request.getScopeType() != null) {
            entity.setScopeType(request.getScopeType());
        }
        if (request.getScopeRef() != null) {
            entity.setScopeRef(request.getScopeRef());
        }
        if (request.getOwnerModule() != null) {
            entity.setOwnerModule(request.getOwnerModule());
        }
        if (request.getEnabled() != null) {
            entity.setEnabled(request.getEnabled());
        }
        entity.setUpdatedBy(userPid);
        entity.setUpdatedAt(Instant.now());

        definitionMapper.updateById(entity);
        log.info("Decision definition updated: pid={}", pid);
        return toDTO(entity);
    }

    @Override
    public DrtDefinitionDTO findByPid(String pid) {
        Long tid = requireTenant();
        LambdaQueryWrapper<DrtDefinitionEntity> w = new LambdaQueryWrapper<>();
        w.eq(DrtDefinitionEntity::getPid, pid)
         .eq(DrtDefinitionEntity::getTenantId, tid);
        DrtDefinitionEntity entity = definitionMapper.selectOne(w);
        return entity != null ? toDTO(entity) : null;
    }

    @Override
    public DrtDefinitionDTO findByCode(String decisionCode) {
        Long tid = requireTenant();
        DrtDefinitionEntity entity = definitionMapper.findByTenantAndCode(tid, decisionCode);
        return entity != null ? toDTO(entity) : null;
    }

    @Override
    public PageResult<DrtDefinitionDTO> list(String keyword, int page, int size) {
        Long tid = requireTenant();
        LambdaQueryWrapper<DrtDefinitionEntity> w = new LambdaQueryWrapper<>();
        w.eq(DrtDefinitionEntity::getTenantId, tid);
        if (StringUtils.hasText(keyword)) {
            w.and(q -> q.like(DrtDefinitionEntity::getDecisionCode, keyword)
                        .or().like(DrtDefinitionEntity::getDecisionName, keyword));
        }
        w.orderByDesc(DrtDefinitionEntity::getCreatedAt);

        Page<DrtDefinitionEntity> pageResult = definitionMapper.selectPage(new Page<>(page, size), w);

        List<DrtDefinitionDTO> dtos = pageResult.getRecords().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());

        PageResult<DrtDefinitionDTO> result = new PageResult<>();
        result.setRecords(dtos);
        result.setTotal(pageResult.getTotal());
        result.setCurrent((long) page);
        result.setSize((long) size);
        result.setPages(pageResult.getPages());
        result.setHasPrevious(page > 1);
        result.setHasNext(page < pageResult.getPages());
        return result;
    }

    // ─── mapping ─────────────────────────────────────────────────────────────

    private DrtDefinitionDTO toDTO(DrtDefinitionEntity e) {
        if (e == null) return null;
        DrtDefinitionDTO dto = new DrtDefinitionDTO();
        dto.setId(e.getId());
        dto.setPid(e.getPid());
        dto.setTenantId(e.getTenantId());
        dto.setDecisionCode(e.getDecisionCode());
        dto.setDecisionName(e.getDecisionName());
        dto.setDescription(e.getDescription());
        dto.setScopeType(e.getScopeType());
        dto.setScopeRef(e.getScopeRef());
        dto.setOwnerModule(e.getOwnerModule());
        dto.setEnabled(e.getEnabled());
        dto.setCreatedBy(e.getCreatedBy());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedBy(e.getUpdatedBy());
        dto.setUpdatedAt(e.getUpdatedAt());
        return dto;
    }
}
