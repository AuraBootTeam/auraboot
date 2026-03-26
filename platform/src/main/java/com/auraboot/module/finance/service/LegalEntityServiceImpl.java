package com.auraboot.module.finance.service;

import com.auraboot.framework.application.exception.ResourceNotFoundException;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.module.finance.dto.LegalEntityCreateRequest;
import com.auraboot.module.finance.dto.LegalEntityTree;
import com.auraboot.module.finance.entity.LegalEntity;
import com.auraboot.module.finance.mapper.LegalEntityMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Default implementation of {@link LegalEntityService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LegalEntityServiceImpl implements LegalEntityService {

    private final LegalEntityMapper mapper;

    @Override
    @Transactional
    public LegalEntity create(LegalEntityCreateRequest req) {
        Long tenantId = MetaContext.getCurrentTenantId();

        // Enforce unique entityCode within tenant
        long existing = mapper.selectCount(
                new LambdaQueryWrapper<LegalEntity>()
                        .eq(LegalEntity::getTenantId, tenantId)
                        .eq(LegalEntity::getEntityCode, req.getEntityCode())
        );
        if (existing > 0) {
            throw new IllegalArgumentException(
                    "Entity code '" + req.getEntityCode() + "' is already used in this tenant");
        }

        LegalEntity entity = new LegalEntity();
        entity.setId(IdWorker.getId());
        entity.setPid(UniqueIdGenerator.generate());
        entity.setTenantId(tenantId);
        entity.setEntityCode(req.getEntityCode());
        entity.setEntityName(req.getEntityName());
        entity.setParentId(req.getParentId());
        entity.setCurrency(req.getCurrency());
        entity.setOwnershipPct(req.getOwnershipPct());
        entity.setIsParent(req.getIsParent() != null ? req.getIsParent() : Boolean.FALSE);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());

        mapper.insert(entity);
        log.info("Created legal entity id={} code={} tenantId={}", entity.getId(), entity.getEntityCode(), tenantId);
        return entity;
    }

    @Override
    @Transactional
    public LegalEntity update(Long id, LegalEntityCreateRequest req) {
        LegalEntity entity = getOwnedEntityOrThrow(id);

        // If code is changing, check uniqueness
        if (!entity.getEntityCode().equals(req.getEntityCode())) {
            long collision = mapper.selectCount(
                    new LambdaQueryWrapper<LegalEntity>()
                            .eq(LegalEntity::getTenantId, entity.getTenantId())
                            .eq(LegalEntity::getEntityCode, req.getEntityCode())
                            .ne(LegalEntity::getId, id)
            );
            if (collision > 0) {
                throw new IllegalArgumentException(
                        "Entity code '" + req.getEntityCode() + "' is already used in this tenant");
            }
        }

        entity.setEntityCode(req.getEntityCode());
        entity.setEntityName(req.getEntityName());
        entity.setParentId(req.getParentId());
        entity.setCurrency(req.getCurrency());
        entity.setOwnershipPct(req.getOwnershipPct());
        if (req.getIsParent() != null) {
            entity.setIsParent(req.getIsParent());
        }
        entity.setUpdatedAt(Instant.now());

        mapper.updateById(entity);
        return entity;
    }

    @Override
    public List<LegalEntity> findAll(Long tenantId) {
        return mapper.findAllByTenantId(tenantId);
    }

    @Override
    public LegalEntity findById(Long id) {
        return getOwnedEntityOrThrow(id);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        LegalEntity entity = getOwnedEntityOrThrow(id);

        // Prevent deletion if there are child entities
        long childCount = mapper.selectCount(
                new LambdaQueryWrapper<LegalEntity>()
                        .eq(LegalEntity::getTenantId, entity.getTenantId())
                        .eq(LegalEntity::getParentId, id)
        );
        if (childCount > 0) {
            throw new IllegalStateException(
                    "Cannot delete entity '" + entity.getEntityCode() + "' because it has " + childCount + " child entities");
        }

        mapper.deleteById(id);
        log.info("Deleted legal entity id={} code={}", id, entity.getEntityCode());
    }

    @Override
    public List<LegalEntityTree> buildHierarchy(Long tenantId) {
        List<LegalEntity> all = mapper.findAllByTenantId(tenantId);

        // Index by id for O(n) tree construction
        Map<Long, LegalEntityTree> nodeIndex = new HashMap<>();
        for (LegalEntity e : all) {
            nodeIndex.put(e.getId(), new LegalEntityTree(e));
        }

        List<LegalEntityTree> roots = new ArrayList<>();
        for (LegalEntity e : all) {
            LegalEntityTree node = nodeIndex.get(e.getId());
            if (e.getParentId() == null) {
                roots.add(node);
            } else {
                LegalEntityTree parent = nodeIndex.get(e.getParentId());
                if (parent != null) {
                    parent.addChild(node);
                } else {
                    // Parent not in this tenant's entity set — treat as root
                    roots.add(node);
                }
            }
        }
        return roots;
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private LegalEntity getOwnedEntityOrThrow(Long id) {
        Long tenantId = MetaContext.getCurrentTenantId();
        LegalEntity entity = mapper.selectOne(
                new LambdaQueryWrapper<LegalEntity>()
                        .eq(LegalEntity::getId, id)
                        .eq(LegalEntity::getTenantId, tenantId)
        );
        if (entity == null) {
            throw new ResourceNotFoundException("LegalEntity not found: id=" + id);
        }
        return entity;
    }
}
