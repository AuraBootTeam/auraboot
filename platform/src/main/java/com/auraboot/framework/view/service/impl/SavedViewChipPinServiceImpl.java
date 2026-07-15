package com.auraboot.framework.view.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.view.dto.ChipPinDTO;
import com.auraboot.framework.view.entity.SavedViewChipPin;
import com.auraboot.framework.view.mapper.SavedViewChipPinMapper;
import com.auraboot.framework.view.service.SavedViewChipPinService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Personal quick-filter chip pins. Reads the current principal from
 * {@link MetaContext}; the unique constraint on (tenant, user, view) makes a
 * blind insert throw on a re-pin, so we query first and update the order instead
 * (query-first, per the tryCreate-conflict gotcha).
 */
@Service
public class SavedViewChipPinServiceImpl implements SavedViewChipPinService {

    private static final String SCOPE_PERSONAL = "personal";

    private final SavedViewChipPinMapper chipPinMapper;

    public SavedViewChipPinServiceImpl(SavedViewChipPinMapper chipPinMapper) {
        this.chipPinMapper = chipPinMapper;
    }

    @Override
    public void pinPersonal(String viewPid, String modelCode, String pageKey, Integer order) {
        String userPid = requireUserPid();
        Long tenantId = requireTenantId();

        SavedViewChipPin existing = chipPinMapper.selectOne(personalPinQuery(tenantId, userPid, viewPid));
        if (existing != null) {
            if (order != null && !order.equals(existing.getSortOrder())) {
                existing.setSortOrder(order);
                chipPinMapper.updateById(existing);
            }
            return;
        }

        SavedViewChipPin pin = new SavedViewChipPin();
        pin.setPid(UniqueIdGenerator.generate());
        pin.setTenantId(tenantId);
        pin.setScope(SCOPE_PERSONAL);
        pin.setUserId(userPid);
        pin.setViewPid(viewPid);
        pin.setModelCode(modelCode);
        pin.setPageKey(pageKey);
        pin.setSortOrder(order != null ? order : 0);
        pin.setCreatedBy(userPid);
        chipPinMapper.insert(pin);
    }

    @Override
    public void unpinPersonal(String viewPid) {
        String userPid = requireUserPid();
        Long tenantId = requireTenantId();
        chipPinMapper.delete(personalPinQuery(tenantId, userPid, viewPid));
    }

    @Override
    public List<ChipPinDTO> listEffectivePins(String modelCode, String pageKey) {
        String userPid = requireUserPid();
        Long tenantId = requireTenantId();

        LambdaQueryWrapper<SavedViewChipPin> query = new LambdaQueryWrapper<SavedViewChipPin>()
                .eq(SavedViewChipPin::getTenantId, tenantId)
                .eq(SavedViewChipPin::getModelCode, modelCode)
                .eq(SavedViewChipPin::getScope, SCOPE_PERSONAL)
                .eq(SavedViewChipPin::getUserId, userPid);
        // A model-level pin (page_key null) applies to every page of the model; a
        // page-scoped pin only to its own page.
        if (pageKey != null && !pageKey.isBlank()) {
            query.and(w -> w.eq(SavedViewChipPin::getPageKey, pageKey).or().isNull(SavedViewChipPin::getPageKey));
        }

        return chipPinMapper.selectList(query).stream()
                .map(p -> new ChipPinDTO(p.getViewPid(), p.getSortOrder() != null ? p.getSortOrder() : 0))
                .collect(Collectors.toList());
    }

    private LambdaQueryWrapper<SavedViewChipPin> personalPinQuery(Long tenantId, String userPid, String viewPid) {
        return new LambdaQueryWrapper<SavedViewChipPin>()
                .eq(SavedViewChipPin::getTenantId, tenantId)
                .eq(SavedViewChipPin::getScope, SCOPE_PERSONAL)
                .eq(SavedViewChipPin::getUserId, userPid)
                .eq(SavedViewChipPin::getViewPid, viewPid);
    }

    private String requireUserPid() {
        String pid = MetaContext.getCurrentUserPid();
        if (pid == null || pid.isBlank()) {
            throw new IllegalStateException("Quick-filter chip pin requires a current user in context");
        }
        return pid;
    }

    private Long requireTenantId() {
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("Quick-filter chip pin requires a tenant in context");
        }
        return tenantId;
    }
}
