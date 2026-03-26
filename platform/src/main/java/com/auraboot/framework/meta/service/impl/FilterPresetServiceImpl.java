package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FilterPresetCreateRequest;
import com.auraboot.framework.meta.entity.FilterPreset;
import com.auraboot.framework.meta.mapper.FilterPresetMapper;
import com.auraboot.framework.meta.service.FilterPresetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Implementation of FilterPresetService.
 *
 * @since 3.4.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FilterPresetServiceImpl implements FilterPresetService {

    private final FilterPresetMapper filterPresetMapper;

    @Override
    @Transactional
    public FilterPreset create(FilterPresetCreateRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();

        FilterPreset entity = new FilterPreset();
        entity.setTenantId(tenantId);
        entity.setUserId("global".equals(request.getScope()) ? null : userId);
        entity.setPageCode(request.getPageCode());
        entity.setModelCode(request.getModelCode());
        entity.setName(request.getName());
        entity.setConditions(request.getConditions());
        entity.setLogic(request.getLogic() != null ? request.getLogic() : "and");
        entity.setIsDefault(request.isDefault());
        entity.setSortOrder(0);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());

        if (request.isDefault()) {
            filterPresetMapper.clearDefaults(tenantId, request.getPageCode(), userId);
        }

        filterPresetMapper.insert(entity);
        log.info("Created filter preset: id={}, name={}, page={}", entity.getId(), entity.getName(), entity.getPageCode());
        return entity;
    }

    @Override
    public List<FilterPreset> listByPageCode(String pageCode) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        return filterPresetMapper.findByPageCode(tenantId, pageCode, userId);
    }

    @Override
    @Transactional
    public FilterPreset update(Long id, FilterPresetCreateRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();

        FilterPreset entity = filterPresetMapper.selectById(id);
        if (entity == null || !entity.getTenantId().equals(tenantId)) {
            throw new IllegalArgumentException("Filter preset not found: " + id);
        }

        entity.setName(request.getName());
        entity.setConditions(request.getConditions());
        entity.setLogic(request.getLogic() != null ? request.getLogic() : entity.getLogic());
        entity.setIsDefault(request.isDefault());
        entity.setUpdatedAt(Instant.now());

        if (request.isDefault()) {
            filterPresetMapper.clearDefaults(tenantId, entity.getPageCode(), userId);
        }

        filterPresetMapper.updateById(entity);
        log.info("Updated filter preset: id={}, name={}", id, entity.getName());
        return entity;
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Long tenantId = MetaContext.getCurrentTenantId();
        int rows = filterPresetMapper.deleteByIdAndTenant(id, tenantId);
        if (rows == 0) {
            throw new IllegalArgumentException("Filter preset not found: " + id);
        }
        log.info("Deleted filter preset: id={}", id);
    }

    @Override
    @Transactional
    public void setDefault(Long id) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();

        FilterPreset entity = filterPresetMapper.selectById(id);
        if (entity == null || !entity.getTenantId().equals(tenantId)) {
            throw new IllegalArgumentException("Filter preset not found: " + id);
        }

        filterPresetMapper.clearDefaults(tenantId, entity.getPageCode(), userId);
        filterPresetMapper.setDefault(id, tenantId);
        log.info("Set default filter preset: id={}, page={}", id, entity.getPageCode());
    }
}
