package com.auraboot.framework.dsl.compiler.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Generic DSL definition input for compilation.
 * Wraps the raw DSL configuration and metadata needed by compilers.
 */
@Data
@Builder
public class DslDefinition {

    /** Definition type discriminator, e.g. "bom", "mrp", "query". */
    private String type;

    /** The root model / entity code this definition targets. */
    private String modelCode;

    /** Raw DSL configuration payload (type-specific). */
    private Map<String, Object> config;

    /** Optional child definitions (e.g. BOM sub-assemblies, MRP demand lines). */
    private List<DslDefinition> children;

    /** Version tag for cache invalidation. */
    private String version;
}
