package com.auraboot.framework.dsl.compiler;

import com.auraboot.framework.dsl.compiler.bom.BomExplosionCompiler;
import com.auraboot.framework.dsl.compiler.model.*;
import com.auraboot.framework.dsl.compiler.mrp.MrpCalculationCompiler;
import com.auraboot.framework.dsl.compiler.query.QueryOptimizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration-style tests for the DSL compiler subsystem.
 * Tests are self-contained (no Spring context) because the compilers are pure logic.
 */
class DslCompilerIntegrationTest {

    private DslCompilerRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DslCompilerRegistry(List.of(
                new BomExplosionCompiler(),
                new MrpCalculationCompiler(),
                new QueryOptimizer()
        ));
    }

    // ─────────────────────────────────────────────
    // Registry tests
    // ─────────────────────────────────────────────

    @Test
    void registeredTypes_containsAllCompilers() {
        assertThat(registry.registeredTypes())
                .containsExactlyInAnyOrder("bom", "mrp", "query");
    }

    @Test
    void compile_unknownType_throws() {
        DslDefinition def = DslDefinition.builder()
                .type("unknown")
                .modelCode("foo")
                .version("v1")
                .build();

        assertThatThrownBy(() -> registry.compile(def))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No DSL compiler registered for type: unknown");
    }

    @Test
    void compile_cacheHit_returnsCachedPlan() {
        DslDefinition def = DslDefinition.builder()
                .type("bom")
                .modelCode("PRODUCT-A")
                .version("v1")
                .config(Map.of())
                .build();

        CompiledPlan first = registry.compile(def);
        assertThat(first.isCached()).isFalse();

        CompiledPlan second = registry.compile(def);
        assertThat(second.isCached()).isTrue();
        assertThat(second.getPlanId()).isEqualTo(first.getPlanId());
    }

    @Test
    void cacheStats_afterCompile_showsOneEntry() {
        DslDefinition def = DslDefinition.builder()
                .type("bom")
                .modelCode("CACHE-TEST")
                .version("v1")
                .config(Map.of())
                .build();

        registry.compile(def);
        Map<String, Object> stats = registry.cacheStats();
        assertThat((int) stats.get("size")).isEqualTo(1);
    }

    @Test
    void clearCache_emptiesCache() {
        DslDefinition def = DslDefinition.builder()
                .type("bom")
                .modelCode("CLEAR-TEST")
                .version("v1")
                .config(Map.of())
                .build();

        registry.compile(def);
        assertThat((int) registry.cacheStats().get("size")).isEqualTo(1);

        registry.clearCache();
        assertThat((int) registry.cacheStats().get("size")).isEqualTo(0);
    }

    @Test
    void evict_removesSpecificEntry() {
        DslDefinition def = DslDefinition.builder()
                .type("bom")
                .modelCode("EVICT-TEST")
                .version("v1")
                .config(Map.of())
                .build();

        registry.compile(def);
        boolean evicted = registry.evict("bom:EVICT-TEST:v1");
        assertThat(evicted).isTrue();
        assertThat((int) registry.cacheStats().get("size")).isEqualTo(0);
    }

    // ─────────────────────────────────────────────
    // BOM Explosion Compiler tests
    // ─────────────────────────────────────────────

    @Nested
    class BomExplosionCompilerTests {

        @Test
        void compile_flatBom_producesBasicPlan() {
            DslDefinition def = DslDefinition.builder()
                    .type("bom")
                    .modelCode("WIDGET-100")
                    .version("v1")
                    .config(Map.of())
                    .build();

            CompiledPlan plan = registry.compile(def);

            assertThat(plan.getPlanId()).isEqualTo("bom-WIDGET-100-v1");
            assertThat(plan.getCompilerName()).isEqualTo("bom");
            assertThat(plan.getStrategy()).isEqualTo(ExecutionStrategy.SEQUENTIAL);
            assertThat(plan.getCompiledAt()).isNotNull();
            assertThat(plan.isCached()).isFalse();

            // Expected steps: cache-lookup, explode-level-1, aggregate, cache-store
            assertThat(plan.getSteps()).hasSize(4);
            assertThat(plan.getSteps().get(0).getType()).isEqualTo(StepType.CACHE_LOOKUP);
            assertThat(plan.getSteps().get(1).getType()).isEqualTo(StepType.BOM_EXPLODE);
            assertThat(plan.getSteps().get(2).getType()).isEqualTo(StepType.AGGREGATE);
            assertThat(plan.getSteps().get(3).getType()).isEqualTo(StepType.CACHE_STORE);
        }

        @Test
        void compile_multiLevelBom_producesStepsPerLevel() {
            DslDefinition child2 = DslDefinition.builder()
                    .type("bom").modelCode("screw").version("v1").build();
            DslDefinition child1 = DslDefinition.builder()
                    .type("bom").modelCode("SUB-ASSY").version("v1")
                    .children(List.of(child2))
                    .build();
            DslDefinition root = DslDefinition.builder()
                    .type("bom").modelCode("TOP-ASSY").version("v1")
                    .config(Map.of())
                    .children(List.of(child1))
                    .build();

            CompiledPlan plan = registry.compile(root);

            // 3 levels → cache-lookup + 3 explode + aggregate + cache-store = 6
            assertThat(plan.getSteps()).hasSize(6);
            assertThat(plan.getOptimizationHints().get("actualLevels")).isEqualTo(3);
        }

        @Test
        void compile_deepBom_cappedByMaxLevel() {
            // Build a 15-level chain
            DslDefinition current = DslDefinition.builder()
                    .type("bom").modelCode("leaf").version("v1").build();
            for (int i = 14; i >= 1; i--) {
                current = DslDefinition.builder()
                        .type("bom").modelCode("LEVEL-" + i).version("v1")
                        .children(List.of(current))
                        .build();
            }

            DslDefinition root = DslDefinition.builder()
                    .type("bom").modelCode("DEEP-ROOT").version("v1")
                    .config(Map.of("maxLevel", 5))
                    .children(current.getChildren())
                    .build();

            CompiledPlan plan = registry.compile(root);
            int actualLevels = (int) plan.getOptimizationHints().get("actualLevels");
            assertThat(actualLevels).isLessThanOrEqualTo(5);
        }

        @Test
        void compile_batchStrategyForDeepBom() {
            // 4+ levels should trigger BATCH strategy
            DslDefinition l4 = DslDefinition.builder().type("bom").modelCode("L4").version("v1").build();
            DslDefinition l3 = DslDefinition.builder().type("bom").modelCode("L3").version("v1").children(List.of(l4)).build();
            DslDefinition l2 = DslDefinition.builder().type("bom").modelCode("L2").version("v1").children(List.of(l3)).build();
            DslDefinition l1 = DslDefinition.builder().type("bom").modelCode("L1").version("v1").children(List.of(l2)).build();
            DslDefinition root = DslDefinition.builder()
                    .type("bom").modelCode("deep").version("v1")
                    .config(Map.of())
                    .children(List.of(l1))
                    .build();

            CompiledPlan plan = registry.compile(root);
            assertThat(plan.getStrategy()).isEqualTo(ExecutionStrategy.BATCH);
        }

        @Test
        void compile_nullModelCode_throws() {
            DslDefinition def = DslDefinition.builder()
                    .type("bom")
                    .version("v1")
                    .config(Map.of())
                    .build();

            assertThatThrownBy(() -> registry.compile(def))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("modelCode is required");
        }

        @Test
        void compile_includePhantom_hintReflected() {
            DslDefinition def = DslDefinition.builder()
                    .type("bom")
                    .modelCode("PHANTOM-TEST")
                    .version("v1")
                    .config(Map.of("includePhantom", true))
                    .build();

            CompiledPlan plan = registry.compile(def);
            assertThat(plan.getOptimizationHints().get("includePhantom")).isEqualTo(true);
        }

        @Test
        void compile_estimatedCost_isPositive() {
            DslDefinition def = DslDefinition.builder()
                    .type("bom")
                    .modelCode("COST-TEST")
                    .version("v1")
                    .config(Map.of())
                    .build();

            CompiledPlan plan = registry.compile(def);
            assertThat(plan.estimatedCost()).isGreaterThan(0);
        }
    }

    // ─────────────────────────────────────────────
    // MRP Calculation Compiler tests
    // ─────────────────────────────────────────────

    @Nested
    class MrpCalculationCompilerTests {

        @Test
        void compile_basicMrp_produces7Steps() {
            DslDefinition def = DslDefinition.builder()
                    .type("mrp")
                    .modelCode("MRP-PLAN-1")
                    .version("v1")
                    .config(Map.of())
                    .build();

            CompiledPlan plan = registry.compile(def);

            assertThat(plan.getPlanId()).isEqualTo("mrp-MRP-PLAN-1-v1");
            assertThat(plan.getCompilerName()).isEqualTo("mrp");
            assertThat(plan.getSteps()).hasSize(7);
            assertThat(plan.getSteps().get(0).getName()).isEqualTo("load-demand-forecast");
            assertThat(plan.getSteps().get(1).getName()).isEqualTo("load-current-inventory");
            assertThat(plan.getSteps().get(2).getName()).isEqualTo("explode-bom-for-mrp");
            assertThat(plan.getSteps().get(3).getName()).isEqualTo("calculate-net-requirements");
            assertThat(plan.getSteps().get(4).getName()).isEqualTo("apply-lot-sizing");
            assertThat(plan.getSteps().get(5).getName()).isEqualTo("generate-purchase-suggestions");
            assertThat(plan.getSteps().get(6).getName()).isEqualTo("generate-production-plan");
        }

        @Test
        void compile_manyMaterials_batchStrategy() {
            // Create 101+ child materials
            List<DslDefinition> children = new java.util.ArrayList<>();
            for (int i = 0; i < 110; i++) {
                children.add(DslDefinition.builder()
                        .type("mrp").modelCode("MAT-" + i).version("v1").build());
            }

            DslDefinition def = DslDefinition.builder()
                    .type("mrp")
                    .modelCode("MRP-BATCH")
                    .version("v1")
                    .config(Map.of())
                    .children(children)
                    .build();

            CompiledPlan plan = registry.compile(def);
            assertThat(plan.getStrategy()).isEqualTo(ExecutionStrategy.BATCH);
            assertThat(plan.getOptimizationHints().get("materialCount")).isEqualTo(111);
        }

        @Test
        void compile_fewMaterials_sequentialStrategy() {
            DslDefinition def = DslDefinition.builder()
                    .type("mrp")
                    .modelCode("MRP-SEQ")
                    .version("v1")
                    .config(Map.of())
                    .build();

            CompiledPlan plan = registry.compile(def);
            assertThat(plan.getStrategy()).isEqualTo(ExecutionStrategy.SEQUENTIAL);
        }

        @Test
        void compile_moderateMaterials_parallelStrategy() {
            List<DslDefinition> children = new java.util.ArrayList<>();
            for (int i = 0; i < 15; i++) {
                children.add(DslDefinition.builder()
                        .type("mrp").modelCode("MAT-" + i).version("v1").build());
            }

            DslDefinition def = DslDefinition.builder()
                    .type("mrp")
                    .modelCode("MRP-PAR")
                    .version("v1")
                    .config(Map.of())
                    .children(children)
                    .build();

            CompiledPlan plan = registry.compile(def);
            assertThat(plan.getStrategy()).isEqualTo(ExecutionStrategy.PARALLEL);
        }

        @Test
        void compile_customConfig_reflectedInHints() {
            DslDefinition def = DslDefinition.builder()
                    .type("mrp")
                    .modelCode("MRP-CUSTOM")
                    .version("v2")
                    .config(Map.of(
                            "planningHorizonDays", 60,
                            "safetyStockPercent", 20,
                            "lotSizingPolicy", "eoq",
                            "batchSize", 500
                    ))
                    .build();

            CompiledPlan plan = registry.compile(def);
            assertThat(plan.getOptimizationHints().get("planningHorizonDays")).isEqualTo(60);
            assertThat(plan.getOptimizationHints().get("safetyStockPercent")).isEqualTo(20);
            assertThat(plan.getOptimizationHints().get("lotSizingPolicy")).isEqualTo("eoq");
            assertThat(plan.getOptimizationHints().get("recommendedBatchSize")).isEqualTo(500);
        }

        @Test
        void compile_nullModelCode_throws() {
            DslDefinition def = DslDefinition.builder()
                    .type("mrp")
                    .version("v1")
                    .config(Map.of())
                    .build();

            assertThatThrownBy(() -> registry.compile(def))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("modelCode is required");
        }

        @Test
        void compile_netRequirementsCost_scalesWithMaterials() {
            List<DslDefinition> children = new java.util.ArrayList<>();
            for (int i = 0; i < 50; i++) {
                children.add(DslDefinition.builder()
                        .type("mrp").modelCode("M-" + i).version("v1").build());
            }

            DslDefinition def = DslDefinition.builder()
                    .type("mrp")
                    .modelCode("MRP-COST")
                    .version("v1")
                    .config(Map.of())
                    .children(children)
                    .build();

            CompiledPlan plan = registry.compile(def);
            // The net-requirements step cost = materialCount * 0.5 = 51 * 0.5 = 25.5
            CompiledStep netReqStep = plan.getSteps().stream()
                    .filter(s -> s.getName().equals("calculate-net-requirements"))
                    .findFirst().orElseThrow();
            assertThat(netReqStep.getCostWeight()).isEqualTo(51 * 0.5);
        }
    }

    // ─────────────────────────────────────────────
    // Query Optimizer tests
    // ─────────────────────────────────────────────

    @Nested
    class QueryOptimizerTests {

        @Test
        void compile_simpleQuery_producesBaselineSteps() {
            DslDefinition def = DslDefinition.builder()
                    .type("query")
                    .modelCode("orders")
                    .version("v1")
                    .config(Map.of(
                            "tables", List.of("orders"),
                            "pageSize", 20
                    ))
                    .build();

            CompiledPlan plan = registry.compile(def);

            assertThat(plan.getCompilerName()).isEqualTo("query");
            // analyze-indexes, execute-query, transform-results (no aggregations)
            assertThat(plan.getSteps()).hasSize(3);
            assertThat(plan.getSteps().get(0).getName()).isEqualTo("analyze-indexes");
            assertThat(plan.getSteps().get(1).getName()).isEqualTo("execute-optimized-query");
            assertThat(plan.getSteps().get(2).getName()).isEqualTo("transform-results");
        }

        @Test
        void compile_withAggregations_addsAggregateStep() {
            DslDefinition def = DslDefinition.builder()
                    .type("query")
                    .modelCode("sales")
                    .version("v1")
                    .config(Map.of(
                            "tables", List.of("sales", "products"),
                            "joins", List.of(Map.of("leftTable", "sales", "rightTable", "products", "on", "product_id")),
                            "aggregations", List.of(Map.of("function", "sum", "field", "amount", "groupBy", "product_id")),
                            "filters", List.of(Map.of("field", "status", "operator", "=", "value", "completed"))
                    ))
                    .build();

            CompiledPlan plan = registry.compile(def);

            assertThat(plan.getSteps()).hasSize(4); // +1 for aggregation
            assertThat(plan.getSteps().stream().anyMatch(s -> s.getName().equals("apply-aggregations"))).isTrue();
        }

        @Test
        void compile_indexSuggestions_forJoinsFiltersSort() {
            DslDefinition def = DslDefinition.builder()
                    .type("query")
                    .modelCode("report")
                    .version("v1")
                    .config(Map.of(
                            "tables", List.of("t1", "t2"),
                            "joins", List.of(Map.of("on", "fk_column")),
                            "filters", List.of(Map.of("field", "status")),
                            "sortFields", List.of("created_at")
                    ))
                    .build();

            CompiledPlan plan = registry.compile(def);

            @SuppressWarnings("unchecked")
            List<String> suggestions = (List<String>) plan.getOptimizationHints().get("indexSuggestions");
            assertThat(suggestions).containsExactlyInAnyOrder(
                    "INDEX on join column: fk_column",
                    "INDEX on filter column: status",
                    "INDEX on sort column: created_at"
            );
        }

        @Test
        void compile_manyJoins_batchStrategy() {
            DslDefinition def = DslDefinition.builder()
                    .type("query")
                    .modelCode("complex")
                    .version("v1")
                    .config(Map.of(
                            "tables", List.of("a", "b", "c", "d", "e"),
                            "joins", List.of(
                                    Map.of("on", "ab"), Map.of("on", "bc"),
                                    Map.of("on", "cd"), Map.of("on", "de")
                            )
                    ))
                    .build();

            CompiledPlan plan = registry.compile(def);
            assertThat(plan.getStrategy()).isEqualTo(ExecutionStrategy.BATCH);
        }

        @Test
        void compile_multipleTables_noJoins_parallelStrategy() {
            DslDefinition def = DslDefinition.builder()
                    .type("query")
                    .modelCode("parallel")
                    .version("v1")
                    .config(Map.of(
                            "tables", List.of("t1", "t2", "t3")
                    ))
                    .build();

            CompiledPlan plan = registry.compile(def);
            assertThat(plan.getStrategy()).isEqualTo(ExecutionStrategy.PARALLEL);
        }

        @Test
        void compile_singleTable_sequentialStrategy() {
            DslDefinition def = DslDefinition.builder()
                    .type("query")
                    .modelCode("simple")
                    .version("v1")
                    .config(Map.of(
                            "tables", List.of("single_table")
                    ))
                    .build();

            CompiledPlan plan = registry.compile(def);
            assertThat(plan.getStrategy()).isEqualTo(ExecutionStrategy.SEQUENTIAL);
        }

        @Test
        void compile_noModelCode_usesAdhoc() {
            DslDefinition def = DslDefinition.builder()
                    .type("query")
                    .version("v1")
                    .config(Map.of("tables", List.of("temp")))
                    .build();

            CompiledPlan plan = registry.compile(def);
            assertThat(plan.getPlanId()).startsWith("query-adhoc-");
        }

        @Test
        void compile_queryCost_scalesWithTablesAndJoins() {
            DslDefinition def = DslDefinition.builder()
                    .type("query")
                    .modelCode("cost-test")
                    .version("v1")
                    .config(Map.of(
                            "tables", List.of("a", "b", "c"),
                            "joins", List.of(Map.of("on", "x"), Map.of("on", "y"))
                    ))
                    .build();

            CompiledPlan plan = registry.compile(def);
            CompiledStep queryStep = plan.getSteps().stream()
                    .filter(s -> s.getName().equals("execute-optimized-query"))
                    .findFirst().orElseThrow();
            // cost = tables(3) * 2.0 + joins(2) * 1.5 = 9.0
            assertThat(queryStep.getCostWeight()).isEqualTo(9.0);
        }
    }

    // ─────────────────────────────────────────────
    // CompiledPlan model tests
    // ─────────────────────────────────────────────

    @Test
    void compiledPlan_estimatedCost_sumsStepWeights() {
        CompiledPlan plan = CompiledPlan.builder()
                .steps(List.of(
                        CompiledStep.builder().name("a").type(StepType.QUERY_EXECUTE).order(0).costWeight(2.0).build(),
                        CompiledStep.builder().name("b").type(StepType.TRANSFORM).order(1).costWeight(3.0).build()
                ))
                .build();

        assertThat(plan.estimatedCost()).isEqualTo(5.0);
    }

    @Test
    void compiledPlan_estimatedCost_nullSteps_returnsZero() {
        CompiledPlan plan = CompiledPlan.builder().build();
        assertThat(plan.estimatedCost()).isEqualTo(0.0);
    }
}
