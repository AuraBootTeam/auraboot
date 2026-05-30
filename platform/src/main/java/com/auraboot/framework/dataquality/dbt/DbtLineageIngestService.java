package com.auraboot.framework.dataquality.dbt;

import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.semantic.entity.AbSemanticLineageEdge;
import com.auraboot.framework.semantic.mapper.AbSemanticLineageEdgeMapper;
import com.auraboot.framework.semantic.mapper.AbSemanticModelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Ingests dbt manifest artifact into the semantic lineage graph.
 *
 * <p>Responsibility:
 * <ol>
 *   <li>Iterate {@link DbtArtifact.DbtManifest#nodes()} and emit a lineage edge
 *       for each inter-model dependency (DBT_MODEL → DBT_MODEL).</li>
 *   <li>Where a dbt model name matches an existing {@code ab_semantic_model.model_ref},
 *       also emit a DBT_MODEL → SEMANTIC_MODEL cross-link edge so the lineage UI
 *       can trace back from the physical dbt layer to the semantic layer.</li>
 * </ol>
 *
 * <p><b>Idempotency:</b> on each call, all edges whose {@code src_node_type = 'DBT_MODEL'}
 * and {@code tenant_id} match are soft-deleted before the new batch is inserted.
 * Re-importing the same manifest is therefore safe.
 *
 * <p><b>Security:</b> only the manifest's own data (uniqueIds, node names) is
 * written into {@code pid} columns; no user-supplied strings reach SQL literals.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DbtLineageIngestService {

    /** Node type tag used for edges originating from dbt models. */
    public static final String SRC_TYPE_DBT_MODEL = "DBT_MODEL";

    /** Ref type for dbt model → upstream model dependency edges. */
    public static final String REF_TYPE_DEPENDS_ON = "depends_on";

    /** Ref type for the cross-link from a dbt model to its matching semantic model. */
    public static final String REF_TYPE_DBT_TO_SEMANTIC = "dbt_to_semantic";

    private final AbSemanticLineageEdgeMapper lineageMapper;
    private final AbSemanticModelMapper semanticModelMapper;

    /**
     * Ingest all node dependencies from {@code manifest} for {@code tenantId}.
     *
     * @param tenantId tenant owning these edges
     * @param manifest parsed dbt manifest
     * @return number of edges inserted
     */
    @Transactional
    public int ingest(Long tenantId, DbtArtifact.DbtManifest manifest) {
        // Idempotency: soft-delete all existing DBT_MODEL edges for this tenant.
        softDeleteExistingDbtEdges(tenantId);

        Map<String, DbtArtifact.DbtNode> nodes = manifest.nodes();
        int inserted = 0;

        for (DbtArtifact.DbtNode node : nodes.values()) {
            // Only ingest model-type nodes as edge sources.
            if (!"model".equalsIgnoreCase(node.resourceType())) {
                continue;
            }

            String srcPid = dbtNodePid(node.uniqueId());

            // Emit one edge per upstream dependency.
            for (String depUniqueId : node.dependsOnNodes()) {
                // Skip self-referential edges (circular deps in dbt are invalid but guard anyway).
                if (depUniqueId.equals(node.uniqueId())) {
                    log.warn("Skipping self-referential dbt edge on node {}", node.uniqueId());
                    continue;
                }

                DbtArtifact.DbtNode depNode = nodes.get(depUniqueId);
                String dstType;
                if (depNode != null && "model".equalsIgnoreCase(depNode.resourceType())) {
                    dstType = SRC_TYPE_DBT_MODEL;
                } else if (depNode != null) {
                    // source / seed / exposure — still record, use resource type as dst label
                    dstType = "DBT_" + depNode.resourceType().toUpperCase();
                } else {
                    // Referenced uniqueId not present in manifest (e.g., cross-project dep).
                    // Record a dangling edge so the graph is complete; just mark unknown.
                    dstType = "DBT_UNKNOWN";
                }

                AbSemanticLineageEdge edge = new AbSemanticLineageEdge();
                edge.setPid(UlidGenerator.generate());
                edge.setTenantId(tenantId);
                edge.setSrcNodePid(srcPid);
                edge.setSrcNodeType(SRC_TYPE_DBT_MODEL);
                edge.setDstNodePid(dbtNodePid(depUniqueId));
                edge.setDstNodeType(dstType);
                edge.setRefType(REF_TYPE_DEPENDS_ON);
                lineageMapper.insert(edge);
                inserted++;
            }

            // Cross-link: if a semantic model references this dbt model by name,
            // emit a DBT_MODEL → SEMANTIC_MODEL edge.
            inserted += emitSemanticCrossLink(tenantId, node, srcPid);
        }

        log.info("dbt lineage ingest: tenant={} inserted={} edges from {} manifest nodes",
                tenantId, inserted, nodes.size());
        return inserted;
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Soft-delete all edges previously ingested from dbt for this tenant.
     * Uses raw update to avoid loading every row into memory.
     */
    private void softDeleteExistingDbtEdges(Long tenantId) {
        // MyBatis-Plus update wrapper: set deleted_flag=true where tenant + src_type = DBT_MODEL.
        com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<AbSemanticLineageEdge> w =
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<>();
        w.eq("tenant_id", tenantId)
         .eq("src_node_type", SRC_TYPE_DBT_MODEL)
         .eq("deleted_flag", false)
         .set("deleted_flag", true);
        lineageMapper.update(null, w);
    }

    /**
     * If any {@code ab_semantic_model} row for this tenant has
     * {@code model_ref} matching the dbt node's name, emit a cross-link edge.
     *
     * @return 1 if a cross-link was inserted, 0 otherwise
     */
    private int emitSemanticCrossLink(Long tenantId, DbtArtifact.DbtNode node, String dbtNodePid) {
        var semanticModel = semanticModelMapper.findByModelRef(tenantId, node.name());
        if (semanticModel == null) {
            return 0;
        }
        AbSemanticLineageEdge crossLink = new AbSemanticLineageEdge();
        crossLink.setPid(UlidGenerator.generate());
        crossLink.setTenantId(tenantId);
        crossLink.setSrcNodePid(dbtNodePid);
        crossLink.setSrcNodeType(SRC_TYPE_DBT_MODEL);
        crossLink.setDstNodePid(semanticModel.getPid());
        crossLink.setDstNodeType("SEMANTIC_MODEL");
        crossLink.setRefType(REF_TYPE_DBT_TO_SEMANTIC);
        lineageMapper.insert(crossLink);
        return 1;
    }

    /** Stable pid string for a dbt uniqueId: use the uniqueId itself (max 255 chars). */
    private static String dbtNodePid(String uniqueId) {
        // ab_semantic_lineage_edge.src_node_pid is VARCHAR(255).
        // dbt uniqueIds are typically < 200 chars; truncate just in case.
        return uniqueId.length() <= 250 ? uniqueId : uniqueId.substring(0, 250);
    }
}
