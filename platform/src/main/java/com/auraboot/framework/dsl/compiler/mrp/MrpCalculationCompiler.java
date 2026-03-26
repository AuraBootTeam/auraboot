package com.auraboot.framework.dsl.compiler.mrp;

import com.auraboot.framework.dsl.compiler.DslCompiler;
import com.auraboot.framework.dsl.compiler.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * Compiles an MRP (Material Requirements Planning) definition into an execution plan.
 *
 * <p>Input config keys:
 * <ul>
 *   <li>{@code planningHorizonDays} — how far ahead to plan (default 30)</li>
 *   <li>{@code safetyStockPercent} — safety stock as % of demand (default 10)</li>
 *   <li>{@code lotSizingPolicy} — "lot_for_lot" | "fixed_order" | "eoq" (default LOT_FOR_LOT)</li>
 *   <li>{@code batchSize} — items per batch (default 200)</li>
 * </ul>
 *
 * <p>Compiled plan structure:
 * <ol>
 *   <li>Load demand forecast</li>
 *   <li>Load current inventory</li>
 *   <li>Explode BOM (delegates to BOM compiler output if available)</li>
 *   <li>Calculate net requirements per material</li>
 *   <li>Apply lot sizing</li>
 *   <li>Generate purchase suggestions</li>
 *   <li>Generate production plan</li>
 * </ol>
 */
@Slf4j
@Component
public class MrpCalculationCompiler implements DslCompiler {

    public static final String TYPE = "mrp";
    private static final int DEFAULT_HORIZON_DAYS = 30;
    private static final int DEFAULT_SAFETY_STOCK_PERCENT = 10;
    private static final int DEFAULT_BATCH_SIZE = 200;
    private static final String DEFAULT_LOT_POLICY = "lot_for_lot";

    @Override
    public String supportedType() {
        return TYPE;
    }

    @Override
    public CompiledPlan compile(DslDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        if (definition.getModelCode() == null || definition.getModelCode().isBlank()) {
            throw new IllegalArgumentException("modelCode is required for MRP compilation");
        }

        Map<String, Object> cfg = definition.getConfig() != null ? definition.getConfig() : Map.of();
        int horizonDays = toInt(cfg.get("planningHorizonDays"), DEFAULT_HORIZON_DAYS);
        int safetyStockPct = toInt(cfg.get("safetyStockPercent"), DEFAULT_SAFETY_STOCK_PERCENT);
        String lotPolicy = cfg.getOrDefault("lotSizingPolicy", DEFAULT_LOT_POLICY).toString();
        int batchSize = toInt(cfg.get("batchSize"), DEFAULT_BATCH_SIZE);

        int materialCount = countMaterials(definition);

        List<CompiledStep> steps = new ArrayList<>();
        int order = 0;

        // 1. Load demand forecast
        steps.add(CompiledStep.builder()
                .name("load-demand-forecast")
                .type(StepType.QUERY_EXECUTE)
                .order(order++)
                .parameters(Map.of(
                        "source", "demand_forecast",
                        "horizonDays", horizonDays
                ))
                .costWeight(2.0)
                .build());

        // 2. Load current inventory
        steps.add(CompiledStep.builder()
                .name("load-current-inventory")
                .type(StepType.QUERY_EXECUTE)
                .order(order++)
                .parameters(Map.of("source", "inventory_snapshot"))
                .costWeight(1.5)
                .build());

        // 3. Explode BOM (reference to BOM plan)
        steps.add(CompiledStep.builder()
                .name("explode-bom-for-mrp")
                .type(StepType.BOM_EXPLODE)
                .order(order++)
                .parameters(Map.of(
                        "modelCode", definition.getModelCode(),
                        "batchSize", batchSize
                ))
                .costWeight(3.0)
                .build());

        // 4. Calculate net requirements
        steps.add(CompiledStep.builder()
                .name("calculate-net-requirements")
                .type(StepType.MRP_CALCULATE)
                .order(order++)
                .parameters(Map.of(
                        "safetyStockPercent", safetyStockPct,
                        "batchSize", batchSize,
                        "materialCount", materialCount
                ))
                .costWeight(materialCount * 0.5)
                .build());

        // 5. Apply lot sizing
        steps.add(CompiledStep.builder()
                .name("apply-lot-sizing")
                .type(StepType.TRANSFORM)
                .order(order++)
                .parameters(Map.of("policy", lotPolicy))
                .costWeight(1.0)
                .build());

        // 6. Generate purchase suggestions
        steps.add(CompiledStep.builder()
                .name("generate-purchase-suggestions")
                .type(StepType.TRANSFORM)
                .order(order++)
                .parameters(Map.of("outputType", "purchase"))
                .costWeight(1.0)
                .build());

        // 7. Generate production plan
        steps.add(CompiledStep.builder()
                .name("generate-production-plan")
                .type(StepType.TRANSFORM)
                .order(order)
                .parameters(Map.of("outputType", "production"))
                .costWeight(1.0)
                .build());

        Map<String, Object> hints = new LinkedHashMap<>();
        hints.put("planningHorizonDays", horizonDays);
        hints.put("safetyStockPercent", safetyStockPct);
        hints.put("lotSizingPolicy", lotPolicy);
        hints.put("materialCount", materialCount);
        hints.put("recommendedBatchSize", batchSize);

        // Use BATCH strategy when there are many materials, PARALLEL for moderate, SEQUENTIAL for few
        ExecutionStrategy strategy;
        if (materialCount > 100) {
            strategy = ExecutionStrategy.BATCH;
        } else if (materialCount > 10) {
            strategy = ExecutionStrategy.PARALLEL;
        } else {
            strategy = ExecutionStrategy.SEQUENTIAL;
        }

        return CompiledPlan.builder()
                .planId("mrp-" + definition.getModelCode() + "-" + definition.getVersion())
                .compilerName(TYPE)
                .steps(steps)
                .optimizationHints(hints)
                .strategy(strategy)
                .compiledAt(Instant.now())
                .build();
    }

    // --- helpers ---

    /**
     * Count the total number of distinct materials in the definition tree.
     */
    private int countMaterials(DslDefinition def) {
        int count = 1; // this node
        if (def.getChildren() != null) {
            for (DslDefinition child : def.getChildren()) {
                count += countMaterials(child);
            }
        }
        return count;
    }

    private int toInt(Object value, int defaultValue) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return defaultValue;
    }
}
