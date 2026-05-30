package com.auraboot.framework.dataquality.dbt;

import com.auraboot.framework.semantic.entity.AbSemanticLineageEdge;
import com.auraboot.framework.semantic.entity.AbSemanticModel;
import com.auraboot.framework.semantic.mapper.AbSemanticLineageEdgeMapper;
import com.auraboot.framework.semantic.mapper.AbSemanticModelMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DbtLineageIngestService}.
 *
 * <p>4 cases:
 * <ol>
 *   <li>First ingest — edges inserted for all model-to-model deps</li>
 *   <li>Idempotent re-import — soft-delete called before re-insert</li>
 *   <li>Cross-link to existing semantic_model — extra edge emitted</li>
 *   <li>Unknown depends_on node — edge still inserted with DBT_UNKNOWN dst type</li>
 * </ol>
 */
class DbtLineageIngestServiceTest {

    private AbSemanticLineageEdgeMapper lineageMapper;
    private AbSemanticModelMapper semanticModelMapper;
    private DbtLineageIngestService service;

    @BeforeEach
    void setup() {
        lineageMapper = mock(AbSemanticLineageEdgeMapper.class);
        semanticModelMapper = mock(AbSemanticModelMapper.class);
        when(lineageMapper.insert(any(AbSemanticLineageEdge.class))).thenReturn(1);
        when(lineageMapper.update(any(), any())).thenReturn(0);
        when(semanticModelMapper.findByModelRef(anyLong(), anyString())).thenReturn(null);

        service = new DbtLineageIngestService(lineageMapper, semanticModelMapper);
    }

    // -----------------------------------------------------------------------
    // Case 1: First ingest
    // -----------------------------------------------------------------------

    @Test
    void firstIngest_insertsEdgesForModelDependencies() {
        DbtArtifact.DbtManifest manifest = new DbtArtifact.DbtManifest(Map.of(
                "model.proj.orders", new DbtArtifact.DbtNode(
                        "model.proj.orders", "orders", "model", "db", "public", "orders",
                        List.of("model.proj.stg_orders")),
                "model.proj.stg_orders", new DbtArtifact.DbtNode(
                        "model.proj.stg_orders", "stg_orders", "model", "db", "staging", "stg_orders",
                        List.of())
        ));

        int count = service.ingest(1L, manifest);

        // orders → stg_orders = 1 edge; stg_orders has no deps = 0 edges
        assertThat(count).isEqualTo(1);

        ArgumentCaptor<AbSemanticLineageEdge> captor = ArgumentCaptor.forClass(AbSemanticLineageEdge.class);
        verify(lineageMapper, times(1)).insert(captor.capture());

        AbSemanticLineageEdge edge = captor.getValue();
        assertThat(edge.getSrcNodeType()).isEqualTo(DbtLineageIngestService.SRC_TYPE_DBT_MODEL);
        assertThat(edge.getDstNodeType()).isEqualTo(DbtLineageIngestService.SRC_TYPE_DBT_MODEL);
        assertThat(edge.getRefType()).isEqualTo(DbtLineageIngestService.REF_TYPE_DEPENDS_ON);
        assertThat(edge.getSrcNodePid()).isEqualTo("model.proj.orders");
        assertThat(edge.getDstNodePid()).isEqualTo("model.proj.stg_orders");
        assertThat(edge.getTenantId()).isEqualTo(1L);
        assertThat(edge.getPid()).isNotBlank();
    }

    // -----------------------------------------------------------------------
    // Case 2: Idempotent re-import
    // -----------------------------------------------------------------------

    @Test
    void idempotentReImport_softDeletesBeforeInsert() {
        DbtArtifact.DbtManifest manifest = new DbtArtifact.DbtManifest(Map.of(
                "model.proj.fact_sales", new DbtArtifact.DbtNode(
                        "model.proj.fact_sales", "fact_sales", "model", null, "public", null,
                        List.of("model.proj.dim_customer"))
        ));

        // First ingest
        service.ingest(2L, manifest);
        // Second ingest (re-import)
        service.ingest(2L, manifest);

        // soft-delete update should be called twice (once per ingest)
        verify(lineageMapper, times(2)).update(any(), any());
        // edge should be inserted twice (idempotent re-insert)
        verify(lineageMapper, times(2)).insert(any(AbSemanticLineageEdge.class));
    }

    // -----------------------------------------------------------------------
    // Case 3: Cross-link to existing semantic_model
    // -----------------------------------------------------------------------

    @Test
    void crossLinkToExistingSemanticModel_emitsExtraEdge() {
        // semantic model exists with model_ref = "orders"
        AbSemanticModel semanticModel = new AbSemanticModel();
        semanticModel.setPid("SEM_PID_ORDERS");
        when(semanticModelMapper.findByModelRef(3L, "orders")).thenReturn(semanticModel);

        DbtArtifact.DbtManifest manifest = new DbtArtifact.DbtManifest(Map.of(
                "model.proj.orders", new DbtArtifact.DbtNode(
                        "model.proj.orders", "orders", "model", null, "public", null,
                        List.of())  // no upstream deps
        ));

        int count = service.ingest(3L, manifest);

        // 0 dep edges + 1 cross-link = 1
        assertThat(count).isEqualTo(1);

        ArgumentCaptor<AbSemanticLineageEdge> captor = ArgumentCaptor.forClass(AbSemanticLineageEdge.class);
        verify(lineageMapper, times(1)).insert(captor.capture());

        AbSemanticLineageEdge crossLink = captor.getValue();
        assertThat(crossLink.getSrcNodeType()).isEqualTo(DbtLineageIngestService.SRC_TYPE_DBT_MODEL);
        assertThat(crossLink.getDstNodeType()).isEqualTo("SEMANTIC_MODEL");
        assertThat(crossLink.getDstNodePid()).isEqualTo("SEM_PID_ORDERS");
        assertThat(crossLink.getRefType()).isEqualTo(DbtLineageIngestService.REF_TYPE_DBT_TO_SEMANTIC);
    }

    // -----------------------------------------------------------------------
    // Case 4: Unknown depends_on node → DBT_UNKNOWN dst type, still inserted
    // -----------------------------------------------------------------------

    @Test
    void unknownDependsOnNode_insertsEdgeWithDbtUnknownType() {
        DbtArtifact.DbtManifest manifest = new DbtArtifact.DbtManifest(Map.of(
                "model.proj.downstream", new DbtArtifact.DbtNode(
                        "model.proj.downstream", "downstream", "model", null, "public", null,
                        List.of("model.other_project.external_model"))
                // "model.other_project.external_model" is NOT in the manifest nodes map
        ));

        int count = service.ingest(4L, manifest);

        assertThat(count).isEqualTo(1);

        ArgumentCaptor<AbSemanticLineageEdge> captor = ArgumentCaptor.forClass(AbSemanticLineageEdge.class);
        verify(lineageMapper).insert(captor.capture());

        assertThat(captor.getValue().getDstNodeType()).isEqualTo("DBT_UNKNOWN");
        assertThat(captor.getValue().getDstNodePid()).isEqualTo("model.other_project.external_model");
    }
}
