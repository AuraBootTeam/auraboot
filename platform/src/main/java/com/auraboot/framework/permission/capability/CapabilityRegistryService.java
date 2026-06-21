package com.auraboot.framework.permission.capability;

import com.auraboot.framework.plugin.dto.imports.CapabilityDefinitionDTO;

import java.util.List;

/** Stores plugin-shipped capability declarations and serves them to the {@link CapabilityResolver}. */
public interface CapabilityRegistryService {

    /** Upsert a capability declaration for the current tenant (idempotent by code). */
    void saveDefinition(CapabilityDefinitionDTO dto);

    /** All capability declarations for a tenant, as DTOs the resolver consumes. */
    List<CapabilityDefinitionDTO> listDeclarations(Long tenantId);
}
