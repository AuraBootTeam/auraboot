package com.auraboot.framework.dashboard.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.dashboard.dto.DashboardModuleCreateRequest;
import com.auraboot.framework.dashboard.dto.DashboardModuleDTO;
import com.auraboot.framework.dashboard.dto.DashboardModuleMoveRequest;
import com.auraboot.framework.dashboard.dto.DashboardModuleRenameRequest;
import com.auraboot.framework.dashboard.entity.Dashboard;
import com.auraboot.framework.dashboard.entity.DashboardModule;
import com.auraboot.framework.dashboard.mapper.DashboardMapper;
import com.auraboot.framework.dashboard.mapper.DashboardModuleMapper;
import com.auraboot.framework.dashboard.service.DashboardModuleService;
import com.auraboot.framework.exception.ValidationException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dashboard module (folder tree) service implementation.
 *
 * @author AuraBoot Team
 * @since 4.2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardModuleServiceImpl implements DashboardModuleService {

    private final DashboardModuleMapper dashboardModuleMapper;
    private final DashboardMapper dashboardMapper;

    @Override
    @Transactional
    public DashboardModuleDTO create(DashboardModuleCreateRequest request) {
        String name = requireName(request.getName());
        Long tenantId = MetaContext.getCurrentTenantId();
        String currentUserPid = MetaContext.getCurrentUserPid();

        DashboardModule parent = requireParent(tenantId, request.getParentPid());

        DashboardModule module = new DashboardModule();
        module.setPid(UniqueIdGenerator.generate());
        module.setTenantId(tenantId);
        module.setName(name);
        module.setParentId(parent != null ? parent.getId() : null);
        module.setSortOrder(nextSortOrder(tenantId, parent != null ? parent.getId() : null));
        module.setDeletedFlag(false);
        module.setCreatedAt(Instant.now());
        module.setUpdatedAt(Instant.now());
        module.setCreatedBy(currentUserPid);
        module.setUpdatedBy(currentUserPid);
        dashboardModuleMapper.insertModule(module);

        log.info("Dashboard module created: pid={}, parent={}", module.getPid(),
                parent != null ? parent.getPid() : "root");
        return toDTO(module, 0);
    }

    @Override
    @Transactional
    public DashboardModuleDTO rename(String pid, DashboardModuleRenameRequest request) {
        String name = requireName(request.getName());
        Long tenantId = MetaContext.getCurrentTenantId();
        String currentUserPid = MetaContext.getCurrentUserPid();

        DashboardModule module = requireModule(tenantId, pid);
        dashboardModuleMapper.rename(pid, name, Instant.now(), currentUserPid);
        log.info("Dashboard module renamed: pid={}, name={}", pid, name);
        return toDTO(requireModule(tenantId, pid), countDashboards(module.getId()));
    }

    @Override
    @Transactional
    public void delete(String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        String currentUserPid = MetaContext.getCurrentUserPid();

        DashboardModule module = requireModule(tenantId, pid);
        if (dashboardModuleMapper.countChildren(tenantId, module.getId()) > 0) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Folder still has child folders: " + pid);
        }
        if (countDashboards(module.getId()) > 0) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Folder still has dashboards: " + pid);
        }
        dashboardModuleMapper.softDelete(pid, Instant.now(), currentUserPid);
        log.info("Dashboard module deleted: pid={}", pid);
    }

    @Override
    public List<DashboardModuleDTO> tree() {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<DashboardModule> modules = dashboardModuleMapper.findAllByTenant(tenantId);
        return buildTree(modules);
    }

    @Override
    public List<DashboardModuleDTO> moduleCounts() {
        Long tenantId = MetaContext.getCurrentTenantId();
        Map<Long, Long> counts = countDashboardsByModuleId();
        List<DashboardModuleDTO> countsByModule = new ArrayList<>();
        for (DashboardModule module : dashboardModuleMapper.findAllByTenant(tenantId)) {
            DashboardModuleDTO dto = new DashboardModuleDTO();
            dto.setPid(module.getPid());
            dto.setName(module.getName());
            dto.setDashboardCount(counts.getOrDefault(module.getId(), 0L));
            countsByModule.add(dto);
        }
        return countsByModule;
    }

    @Override
    @Transactional
    public DashboardModuleDTO move(String pid, DashboardModuleMoveRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        String currentUserPid = MetaContext.getCurrentUserPid();

        DashboardModule module = requireModule(tenantId, pid);
        DashboardModule target = requireParent(tenantId, request.getTargetParentPid());
        if (target != null) {
            if (target.getId().equals(module.getId())) {
                throw new ValidationException(ResponseCode.CommonValidationFailed,
                        "Cannot move a folder under itself: " + pid);
            }
            if (isDescendant(tenantId, target.getId(), module.getId(), new HashMap<>())) {
                throw new ValidationException(ResponseCode.CommonValidationFailed,
                        "Cannot move a folder under one of its own descendants: " + pid);
            }
        }

        dashboardModuleMapper.move(pid, target != null ? target.getId() : null,
                Instant.now(), currentUserPid);
        log.info("Dashboard module moved: pid={}, newParent={}", pid,
                target != null ? target.getPid() : "root");
        return toDTO(requireModule(tenantId, pid), countDashboards(module.getId()));
    }

    private List<DashboardModuleDTO> buildTree(List<DashboardModule> modules) {
        Map<Long, Long> counts = countDashboardsByModuleId();
        Map<Long, DashboardModuleDTO> dtoById = new HashMap<>();
        for (DashboardModule module : modules) {
            DashboardModuleDTO dto = new DashboardModuleDTO();
            dto.setPid(module.getPid());
            dto.setName(module.getName());
            dto.setSortOrder(module.getSortOrder());
            dto.setCreatedAt(module.getCreatedAt());
            dto.setUpdatedAt(module.getUpdatedAt());
            dto.setDashboardCount(counts.getOrDefault(module.getId(), 0L));
            dto.setChildren(new ArrayList<>());
            dtoById.put(module.getId(), dto);
        }

        List<DashboardModuleDTO> roots = new ArrayList<>();
        for (DashboardModule module : modules) {
            DashboardModuleDTO dto = dtoById.get(module.getId());
            DashboardModuleDTO parentDto = module.getParentId() != null
                    ? dtoById.get(module.getParentId())
                    : null;
            if (parentDto != null) {
                dto.setParentPid(parentDto.getPid());
                parentDto.getChildren().add(dto);
            } else {
                roots.add(dto);
            }
        }
        sortTree(roots);
        return roots;
    }

    private void sortTree(List<DashboardModuleDTO> nodes) {
        nodes.sort(Comparator
                .comparing(DashboardModuleDTO::getSortOrder,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(DashboardModuleDTO::getPid));
        for (DashboardModuleDTO node : nodes) {
            if (node.getChildren() != null) {
                sortTree(node.getChildren());
            }
        }
    }

    private boolean isDescendant(Long tenantId, Long candidateId, Long ancestorId,
                                 Map<Long, Long> parentCache) {
        Long cursor = candidateId;
        while (cursor != null) {
            if (cursor.equals(ancestorId)) {
                return true;
            }
            Long cached = parentCache.get(cursor);
            if (cached == null) {
                DashboardModule module = dashboardModuleMapper.selectById(cursor);
                cached = module != null && !Boolean.TRUE.equals(module.getDeletedFlag())
                        ? module.getParentId()
                        : null;
                parentCache.put(cursor, cached);
            }
            cursor = cached;
        }
        return false;
    }

    private DashboardModule requireModule(Long tenantId, String pid) {
        DashboardModule module = dashboardModuleMapper.findByPid(pid);
        if (module == null || !tenantId.equals(module.getTenantId())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Dashboard folder not found: " + pid);
        }
        return module;
    }

    private DashboardModule requireParent(Long tenantId, String parentPid) {
        if (!StringUtils.hasText(parentPid)) {
            return null;
        }
        DashboardModule parent = dashboardModuleMapper.findByPid(parentPid);
        if (parent == null || !tenantId.equals(parent.getTenantId())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Parent dashboard folder not found: " + parentPid);
        }
        return parent;
    }

    private String requireName(String name) {
        if (!StringUtils.hasText(name) || name.isBlank()) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Folder name is required");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 200) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Folder name must be at most 200 characters");
        }
        return trimmed;
    }

    private int nextSortOrder(Long tenantId, Long parentId) {
        return (int) dashboardModuleMapper.countChildren(tenantId, parentId);
    }

    private long countDashboards(Long moduleId) {
        return dashboardMapper.selectCount(new LambdaQueryWrapper<Dashboard>()
                .eq(Dashboard::getModuleId, moduleId)
                .eq(Dashboard::getDeletedFlag, false));
    }

    /**
     * One grouped query: dashboard counts per module id for the current tenant.
     */
    private Map<Long, Long> countDashboardsByModuleId() {
        List<Map<String, Object>> rows = dashboardMapper.selectMaps(
                new QueryWrapper<Dashboard>()
                        .select("module_id", "COUNT(*) AS cnt")
                        .eq("deleted_flag", false)
                        .isNotNull("module_id")
                        .groupBy("module_id"));
        Map<Long, Long> counts = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object moduleId = row.get("module_id");
            Object cnt = row.get("cnt");
            if (moduleId instanceof Number idNumber && cnt instanceof Number cntNumber) {
                counts.put(idNumber.longValue(), cntNumber.longValue());
            }
        }
        return counts;
    }

    private DashboardModuleDTO toDTO(DashboardModule module, long dashboardCount) {
        DashboardModuleDTO dto = new DashboardModuleDTO();
        dto.setPid(module.getPid());
        dto.setName(module.getName());
        dto.setSortOrder(module.getSortOrder());
        dto.setCreatedAt(module.getCreatedAt());
        dto.setUpdatedAt(module.getUpdatedAt());
        dto.setDashboardCount(dashboardCount);
        dto.setChildren(new ArrayList<>());
        if (module.getParentId() != null) {
            DashboardModule parent = dashboardModuleMapper.selectById(module.getParentId());
            if (parent != null) {
                dto.setParentPid(parent.getPid());
            }
        }
        return dto;
    }
}
