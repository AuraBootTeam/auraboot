package com.auraboot.framework.dsl.service;

import com.auraboot.framework.dsl.dto.DslIntrospectionResponse;

import java.util.Set;

/**
 * DSL Schema Introspection Protocol.
 * <p>
 * Provides structured metadata about all DSL resources (models, fields, commands, pages)
 * and platform capabilities, enabling third-party tools and AI agents to auto-discover
 * the full DSL surface area.
 * </p>
 *
 * @author AuraBoot Team
 * @since 3.0.0
 */
public interface DslIntrospectionService {

    /**
     * Return the full DSL schema snapshot for the current tenant.
     *
     * @param scopes which sections to include (models, fields, commands, pages, capabilities).
     *               If empty, all sections are included.
     * @return introspection response
     */
    DslIntrospectionResponse getFullSchema(Set<String> scopes);

    /**
     * Return the schema for a single model, including its fields, commands, and pages.
     *
     * @param modelCode the model code
     * @return model introspection, or null if the model does not exist
     */
    DslIntrospectionResponse.ModelIntrospection getModelSchema(String modelCode);

    /**
     * Return the platform capability catalog (data types, block types, command types, etc.).
     *
     * @return capability catalog
     */
    DslIntrospectionResponse.CapabilityCatalog getAvailableCapabilities();
}
