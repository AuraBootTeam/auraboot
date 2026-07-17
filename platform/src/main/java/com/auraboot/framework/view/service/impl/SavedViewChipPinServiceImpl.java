package com.auraboot.framework.view.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.tenant.service.CurrentUserTeamResolver;
import com.auraboot.framework.view.dto.ChipPinDTO;
import com.auraboot.framework.view.entity.SavedViewChipPin;
import com.auraboot.framework.view.mapper.SavedViewChipPinMapper;
import com.auraboot.framework.view.service.SavedViewChipPinService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Personal and team quick-filter chip pins. Reads the current principal from
 * {@link MetaContext}; the unique constraint on (tenant, user, view) / (tenant,
 * team, view) makes a blind insert throw on a re-pin, so we query first and
 * update the order instead (query-first, per the tryCreate-conflict gotcha).
 *
 * <p>Seeing a team pin is read-level — {@link #listEffectivePins} unions the
 * team pins of every team the current user belongs to. Authoring one
 * ({@link #pinTeam} / {@link #unpinTeam}) additionally requires the
 * {@link MetaPermission#VIEW_TEAM_MANAGE} capability, mirroring how
 * {@code SavedViewServiceImpl} gates team-scoped view mutations.
 */
@Service
public class SavedViewChipPinServiceImpl implements SavedViewChipPinService {

    private static final String SCOPE_PERSONAL = "personal";
    private static final String SCOPE_TEAM = "team";

    private final SavedViewChipPinMapper chipPinMapper;
    private final CurrentUserTeamResolver currentUserTeamResolver;
    private final UserPermissionService userPermissionService;

    public SavedViewChipPinServiceImpl(SavedViewChipPinMapper chipPinMapper,
                                       CurrentUserTeamResolver currentUserTeamResolver,
                                       UserPermissionService userPermissionService) {
        this.chipPinMapper = chipPinMapper;
        this.currentUserTeamResolver = currentUserTeamResolver;
        this.userPermissionService = userPermissionService;
    }

    @Override
    public void pinPersonal(String viewPid, String modelCode, String pageKey, Integer order) {
        String userPid = requireUserPid();
        Long tenantId = requireTenantId();

        SavedViewChipPin existing = chipPinMapper.selectOne(personalPinQuery(tenantId, userPid, viewPid));
        if (existing != null) {
            updateOrderIfChanged(existing, order);
            return;
        }

        SavedViewChipPin pin = newPin(tenantId, viewPid, modelCode, pageKey, order, userPid);
        pin.setScope(SCOPE_PERSONAL);
        pin.setUserId(userPid);
        chipPinMapper.insert(pin);
    }

    @Override
    public void unpinPersonal(String viewPid) {
        String userPid = requireUserPid();
        Long tenantId = requireTenantId();
        chipPinMapper.delete(personalPinQuery(tenantId, userPid, viewPid));
    }

    @Override
    public void pinTeam(String viewPid, String teamId, String modelCode, String pageKey, Integer order) {
        String userPid = requireUserPid();
        Long tenantId = requireTenantId();
        requireTeamAuthoring(teamId);

        SavedViewChipPin existing = chipPinMapper.selectOne(teamPinQuery(tenantId, teamId, viewPid));
        if (existing != null) {
            updateOrderIfChanged(existing, order);
            return;
        }

        SavedViewChipPin pin = newPin(tenantId, viewPid, modelCode, pageKey, order, userPid);
        pin.setScope(SCOPE_TEAM);
        pin.setTeamId(teamId);
        chipPinMapper.insert(pin);
    }

    @Override
    public void unpinTeam(String viewPid, String teamId) {
        Long tenantId = requireTenantId();
        requireTeamAuthoring(teamId);
        chipPinMapper.delete(teamPinQuery(tenantId, teamId, viewPid));
    }

    @Override
    public List<ChipPinDTO> listEffectivePins(String modelCode, String pageKey) {
        String userPid = requireUserPid();
        Long tenantId = requireTenantId();

        List<SavedViewChipPin> rows = new ArrayList<>(
                chipPinMapper.selectList(personalListQuery(tenantId, userPid, modelCode, pageKey)));

        List<String> teamIds = currentUserTeamResolver.resolveCurrentUserTeamIds();
        if (teamIds != null && !teamIds.isEmpty()) {
            rows.addAll(chipPinMapper.selectList(teamListQuery(tenantId, teamIds, modelCode, pageKey)));
        }

        // De-duplicate by viewPid (a view pinned both personally and via a team
        // surfaces as a single chip); personal pins are added first, so their
        // order wins.
        Map<String, ChipPinDTO> byView = new LinkedHashMap<>();
        for (SavedViewChipPin p : rows) {
            byView.putIfAbsent(p.getViewPid(),
                    new ChipPinDTO(p.getViewPid(), p.getSortOrder() != null ? p.getSortOrder() : 0));
        }
        return new ArrayList<>(byView.values());
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private SavedViewChipPin newPin(Long tenantId, String viewPid, String modelCode,
                                    String pageKey, Integer order, String createdBy) {
        SavedViewChipPin pin = new SavedViewChipPin();
        pin.setPid(UniqueIdGenerator.generate());
        pin.setTenantId(tenantId);
        pin.setViewPid(viewPid);
        pin.setModelCode(modelCode);
        pin.setPageKey(pageKey);
        pin.setSortOrder(order != null ? order : 0);
        pin.setCreatedBy(createdBy);
        return pin;
    }

    private void updateOrderIfChanged(SavedViewChipPin existing, Integer order) {
        if (order != null && !order.equals(existing.getSortOrder())) {
            existing.setSortOrder(order);
            chipPinMapper.updateById(existing);
        }
    }

    /**
     * A caller may author a team pin only if they can manage team views and belong
     * to the target team — the same gate {@code SavedViewServiceImpl#canSave} /
     * {@code canManage} apply to team-scoped view mutations: team-manage, or the
     * broader view-manage. (You must not be able to save a team view but not pin it.)
     */
    private void requireTeamAuthoring(String teamId) {
        if (!StringUtils.hasText(teamId)) {
            throw new ValidationException(ResponseCode.BadParam, "teamId is required for a team pin");
        }
        Long userId = MetaContext.getCurrentUserId();
        boolean canManageTeamViews = userId != null
                && (userPermissionService.hasPermission(userId, MetaPermission.VIEW_TEAM_MANAGE)
                || userPermissionService.hasPermission(userId, MetaPermission.VIEW_MANAGE));
        if (!canManageTeamViews) {
            throw new ValidationException(ResponseCode.FORBIDDEN,
                    "Pinning a view for a team requires team-manage permission");
        }
        List<String> teamIds = currentUserTeamResolver.resolveCurrentUserTeamIds();
        if (teamIds == null || !teamIds.contains(teamId)) {
            throw new ValidationException(ResponseCode.FORBIDDEN,
                    "You are not a member of team: " + teamId);
        }
    }

    private LambdaQueryWrapper<SavedViewChipPin> personalPinQuery(Long tenantId, String userPid, String viewPid) {
        return new LambdaQueryWrapper<SavedViewChipPin>()
                .eq(SavedViewChipPin::getTenantId, tenantId)
                .eq(SavedViewChipPin::getScope, SCOPE_PERSONAL)
                .eq(SavedViewChipPin::getUserId, userPid)
                .eq(SavedViewChipPin::getViewPid, viewPid);
    }

    private LambdaQueryWrapper<SavedViewChipPin> teamPinQuery(Long tenantId, String teamId, String viewPid) {
        return new LambdaQueryWrapper<SavedViewChipPin>()
                .eq(SavedViewChipPin::getTenantId, tenantId)
                .eq(SavedViewChipPin::getScope, SCOPE_TEAM)
                .eq(SavedViewChipPin::getTeamId, teamId)
                .eq(SavedViewChipPin::getViewPid, viewPid);
    }

    private LambdaQueryWrapper<SavedViewChipPin> personalListQuery(Long tenantId, String userPid,
                                                                   String modelCode, String pageKey) {
        LambdaQueryWrapper<SavedViewChipPin> query = new LambdaQueryWrapper<SavedViewChipPin>()
                .eq(SavedViewChipPin::getTenantId, tenantId)
                .eq(SavedViewChipPin::getModelCode, modelCode)
                .eq(SavedViewChipPin::getScope, SCOPE_PERSONAL)
                .eq(SavedViewChipPin::getUserId, userPid);
        applyPageScope(query, pageKey);
        return query;
    }

    private LambdaQueryWrapper<SavedViewChipPin> teamListQuery(Long tenantId, List<String> teamIds,
                                                              String modelCode, String pageKey) {
        LambdaQueryWrapper<SavedViewChipPin> query = new LambdaQueryWrapper<SavedViewChipPin>()
                .eq(SavedViewChipPin::getTenantId, tenantId)
                .eq(SavedViewChipPin::getModelCode, modelCode)
                .eq(SavedViewChipPin::getScope, SCOPE_TEAM)
                .in(SavedViewChipPin::getTeamId, teamIds);
        applyPageScope(query, pageKey);
        return query;
    }

    /**
     * A model-level pin (page_key null) applies to every page of the model; a
     * page-scoped pin only to its own page.
     */
    private void applyPageScope(LambdaQueryWrapper<SavedViewChipPin> query, String pageKey) {
        if (pageKey != null && !pageKey.isBlank()) {
            query.and(w -> w.eq(SavedViewChipPin::getPageKey, pageKey).or().isNull(SavedViewChipPin::getPageKey));
        }
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
