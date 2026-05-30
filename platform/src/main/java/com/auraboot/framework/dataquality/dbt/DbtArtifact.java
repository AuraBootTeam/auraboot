package com.auraboot.framework.dataquality.dbt;

import java.util.List;
import java.util.Map;

/**
 * Value-type records representing the dbt artifact JSON shapes.
 *
 * <p>We parse {@code target/manifest.json} and optionally {@code catalog.json}
 * to extract model nodes and their upstream dependencies for lineage ingest.
 *
 * <p>dbt manifest schema reference: dbt Core docs §Artifact contracts v10+.
 */
public final class DbtArtifact {

    private DbtArtifact() {}

    /**
     * Top-level container of {@code manifest.json}.
     * Only the {@code nodes} map is consumed; other manifest keys are ignored.
     */
    public record DbtManifest(Map<String, DbtNode> nodes) {}

    /**
     * A single node entry from {@code manifest.nodes} or {@code manifest.sources}.
     *
     * @param uniqueId       Globally unique identifier, e.g. {@code model.project.my_model}
     * @param name           Short name, e.g. {@code my_model}
     * @param resourceType   {@code model}, {@code source}, {@code seed}, or {@code exposure}
     * @param database       Database/catalog name (may be null for source-only setups)
     * @param schema         Schema name (e.g. {@code public})
     * @param alias          Physical table alias (falls back to {@code name})
     * @param dependsOnNodes List of upstream uniqueIds this node depends on
     */
    public record DbtNode(
            String uniqueId,
            String name,
            String resourceType,
            String database,
            String schema,
            String alias,
            List<String> dependsOnNodes
    ) {}

    /**
     * Top-level container of {@code catalog.json}.
     * Provides column-type metadata; optional — lineage ingest works without it.
     */
    public record DbtCatalog(Map<String, DbtCatalogNode> nodes) {}

    /**
     * Per-node entry from {@code catalog.nodes}.
     *
     * @param uniqueId  Matches the corresponding manifest node uniqueId
     * @param columns   Map of column name → data type string (e.g. {@code "integer"})
     */
    public record DbtCatalogNode(
            String uniqueId,
            Map<String, String> columns
    ) {}
}
