package com.auraboot.framework.application.release;

import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.service.RoleService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.stereotype.Service;

/** Projects only tenant-owned authorization state from the exact bound Release. */
@Service
public final class ApplicationReleaseTenantInitializer {
    private final ApplicationRuntimeDefinitionCatalog catalog;
    private final RoleMapper roleMapper;
    private final RoleService roleService;
    private final ObjectMapper mapper;

    public ApplicationReleaseTenantInitializer(ApplicationRuntimeDefinitionCatalog catalog,
                                               RoleMapper roleMapper,
                                               RoleService roleService,
                                               ObjectMapper mapper) {
        this.catalog = catalog;
        this.roleMapper = roleMapper;
        this.roleService = roleService;
        this.mapper = mapper;
    }

    public void initialize(long tenantId, String applicationCode) {
        for (var definition : catalog.roles(tenantId, applicationCode)) {
            if (roleMapper.findByTenantIdAndCode(tenantId, definition.getCode()) != null) continue;
            Role role = new Role();
            role.setTenantId(tenantId);
            role.setCode(definition.getCode());
            role.setName(definition.getEffectiveName());
            role.setDescription(definition.getDescription());
            role.setType(definition.getType() == null ? "custom" : definition.getType());
            role.setPriority(definition.getPriority() == null ? 100 : definition.getPriority());
            role.setIsDefault(Boolean.TRUE.equals(definition.getIsDefault()));
            role.setScopeType(definition.getScopeType() == null ? "tenant" : definition.getScopeType());
            role.setScopeContent(json(definition.getScopeContent()));
            role.setDefaultDataScopeType(definition.getDefaultDataScopeType());
            roleService.createRole(role);
        }
    }

    private String json(Object value) {
        if (value == null) return null;
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new DataRetrievalFailureException("Application Release role state cannot be initialized", failure);
        }
    }
}
