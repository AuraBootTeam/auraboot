package com.auraboot.framework.dataquality.dbt;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link DbtManifestParser}.
 *
 * <p>6 cases:
 * <ol>
 *   <li>happy path — full manifest with nodes + dependsOn</li>
 *   <li>missing 'nodes' field → {@link DbtParseException}</li>
 *   <li>unknown resource_type accepted (non-model types should not cause failure)</li>
 *   <li>self-referential dependsOn node (loop detection is ingest's job; parser accepts it)</li>
 *   <li>catalog absent → empty catalog with no NPE</li>
 *   <li>large manifest via InputStream (streaming parse)</li>
 * </ol>
 */
class DbtManifestParserTest {

    private static DbtManifestParser parser;

    @BeforeAll
    static void setup() {
        parser = new DbtManifestParser();
    }

    // -----------------------------------------------------------------------
    // Case 1: Happy path
    // -----------------------------------------------------------------------

    @Test
    void happyPath_parsesNodesAndDependencies() {
        String json = """
                {
                  "nodes": {
                    "model.project.orders": {
                      "unique_id": "model.project.orders",
                      "name": "orders",
                      "resource_type": "model",
                      "database": "analytics",
                      "schema": "public",
                      "alias": "orders",
                      "depends_on": {
                        "nodes": ["model.project.stg_orders", "source.project.raw.orders"]
                      }
                    },
                    "model.project.stg_orders": {
                      "unique_id": "model.project.stg_orders",
                      "name": "stg_orders",
                      "resource_type": "model",
                      "database": "analytics",
                      "schema": "staging",
                      "alias": "stg_orders",
                      "depends_on": {"nodes": []}
                    }
                  }
                }
                """;
        DbtArtifact.DbtManifest manifest = parser.parseManifest(json.getBytes(StandardCharsets.UTF_8));

        assertThat(manifest.nodes()).hasSize(2);

        DbtArtifact.DbtNode ordersNode = manifest.nodes().get("model.project.orders");
        assertThat(ordersNode).isNotNull();
        assertThat(ordersNode.name()).isEqualTo("orders");
        assertThat(ordersNode.resourceType()).isEqualTo("model");
        assertThat(ordersNode.database()).isEqualTo("analytics");
        assertThat(ordersNode.dependsOnNodes()).containsExactly(
                "model.project.stg_orders", "source.project.raw.orders");

        DbtArtifact.DbtNode stagingNode = manifest.nodes().get("model.project.stg_orders");
        assertThat(stagingNode).isNotNull();
        assertThat(stagingNode.dependsOnNodes()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Case 2: Missing 'nodes' field
    // -----------------------------------------------------------------------

    @Test
    void missingNodesField_throwsDbtParseException() {
        String json = """
                {
                  "metadata": {"dbt_schema_version": "v1"}
                }
                """;
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> parser.parseManifest(bytes))
                .isInstanceOf(DbtParseException.class)
                .hasMessageContaining("nodes");
    }

    // -----------------------------------------------------------------------
    // Case 3: Non-model resource_type is accepted
    // -----------------------------------------------------------------------

    @Test
    void nonModelResourceType_isAcceptedByParser() {
        String json = """
                {
                  "nodes": {
                    "seed.project.countries": {
                      "unique_id": "seed.project.countries",
                      "name": "countries",
                      "resource_type": "seed",
                      "depends_on": {"nodes": []}
                    },
                    "exposure.project.revenue_dashboard": {
                      "unique_id": "exposure.project.revenue_dashboard",
                      "name": "revenue_dashboard",
                      "resource_type": "exposure",
                      "depends_on": {"nodes": ["model.project.orders"]}
                    }
                  }
                }
                """;
        DbtArtifact.DbtManifest manifest = parser.parseManifest(json.getBytes(StandardCharsets.UTF_8));

        assertThat(manifest.nodes()).hasSize(2);
        assertThat(manifest.nodes().get("seed.project.countries").resourceType()).isEqualTo("seed");
        assertThat(manifest.nodes().get("exposure.project.revenue_dashboard").resourceType()).isEqualTo("exposure");
        // Parser does NOT filter: ingest service decides what to process.
    }

    // -----------------------------------------------------------------------
    // Case 4: Self-loop in dependsOn is parsed without error
    // -----------------------------------------------------------------------

    @Test
    void selfLoopInDependsOn_isParsdWithoutError() {
        String json = """
                {
                  "nodes": {
                    "model.project.circular": {
                      "unique_id": "model.project.circular",
                      "name": "circular",
                      "resource_type": "model",
                      "depends_on": {"nodes": ["model.project.circular"]}
                    }
                  }
                }
                """;
        DbtArtifact.DbtManifest manifest = parser.parseManifest(json.getBytes(StandardCharsets.UTF_8));

        DbtArtifact.DbtNode node = manifest.nodes().get("model.project.circular");
        assertThat(node.dependsOnNodes()).containsExactly("model.project.circular");
        // Loop detection is the ingest service's responsibility, not the parser's.
    }

    // -----------------------------------------------------------------------
    // Case 5: Catalog absent → empty catalog, no NPE
    // -----------------------------------------------------------------------

    @Test
    void catalogAbsent_returnsEmptyCatalog() {
        DbtArtifact.DbtCatalog catalog = parser.parseCatalog((byte[]) null);

        assertThat(catalog).isNotNull();
        assertThat(catalog.nodes()).isEmpty();
    }

    @Test
    void catalogPresent_parsesColumns() {
        String catalogJson = """
                {
                  "nodes": {
                    "model.project.orders": {
                      "unique_id": "model.project.orders",
                      "columns": {
                        "order_id": {"name": "order_id", "type": "integer"},
                        "amount": {"name": "amount", "type": "numeric"}
                      }
                    }
                  }
                }
                """;
        DbtArtifact.DbtCatalog catalog = parser.parseCatalog(catalogJson.getBytes(StandardCharsets.UTF_8));

        assertThat(catalog.nodes()).hasSize(1);
        Map<String, String> columns = catalog.nodes().get("model.project.orders").columns();
        assertThat(columns).containsEntry("order_id", "integer")
                           .containsEntry("amount", "numeric");
    }

    // -----------------------------------------------------------------------
    // Case 6: Large manifest via InputStream (streaming)
    // -----------------------------------------------------------------------

    @Test
    void largeManifestViaInputStream_parsesSuccessfully() {
        // Build a manifest with 100 model nodes programmatically.
        StringBuilder sb = new StringBuilder("{\"nodes\":{");
        for (int i = 0; i < 100; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"model.project.model_").append(i).append("\":{")
              .append("\"unique_id\":\"model.project.model_").append(i).append("\",")
              .append("\"name\":\"model_").append(i).append("\",")
              .append("\"resource_type\":\"model\",")
              .append("\"depends_on\":{\"nodes\":[")
              .append(i > 0 ? "\"model.project.model_" + (i - 1) + "\"" : "")
              .append("]}}");
        }
        sb.append("}}");

        InputStream stream = new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8));
        DbtArtifact.DbtManifest manifest = parser.parseManifest(stream);

        assertThat(manifest.nodes()).hasSize(100);
        // Last node depends on the previous one.
        assertThat(manifest.nodes().get("model.project.model_99").dependsOnNodes())
                .containsExactly("model.project.model_98");
    }
}
