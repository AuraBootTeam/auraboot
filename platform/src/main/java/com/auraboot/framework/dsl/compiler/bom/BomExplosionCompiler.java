package com.auraboot.framework.dsl.compiler.bom;

import com.auraboot.framework.dsl.compiler.DslCompiler;
import com.auraboot.framework.dsl.compiler.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * Compiles a BOM (Bill of Materials) tree definition into a flattened,
 * level-ordered explosion plan.
 *
 * <p>Input config keys:
 * <ul>
 *   <li>{@code maxLevel} — maximum BOM depth to explode (default 10)</li>
 *   <li>{@code batchSize} — rows per batch when fetching child items (default 500)</li>
 *   <li>{@code includePhantom} — whether to include phantom assemblies (default false)</li>
 * </ul>
 *
 * <p>The compiler recursively walks {@link DslDefinition#getChildren()} to build
 * CACHE_LOOKUP → BOM_EXPLODE → AGGREGATE steps per level.
 */
@Slf4j
@Component
public class BomExplosionCompiler implements DslCompiler {

    public static final String TYPE = "bom";
    private static final int DEFAULT_MAX_LEVEL = 10;
    private static final int DEFAULT_BATCH_SIZE = 500;

    @Override
    public String supportedType() {
        return TYPE;
    }

    @Override
    public CompiledPlan compile(DslDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        if (definition.getModelCode() == null || definition.getModelCode().isBlank()) {
            throw new IllegalArgumentException("modelCode is required for BOM compilation");
        }

        Map<String, Object> cfg = definition.getConfig() != null ? definition.getConfig() : Map.of();
        int maxLevel = toInt(cfg.get("maxLevel"), DEFAULT_MAX_LEVEL);
        int batchSize = toInt(cfg.get("batchSize"), DEFAULT_BATCH_SIZE);
        boolean includePhantom = Boolean.TRUE.equals(cfg.get("includePhantom"));

        List<CompiledStep> steps = new ArrayList<>();
        int order = 0;

        // Step 1: cache lookup for the root BOM structure
        steps.add(CompiledStep.builder()
                .name("cache-lookup-bom-" + definition.getModelCode())
                .type(StepType.CACHE_LOOKUP)
                .order(order++)
                .parameters(Map.of("modelCode", definition.getModelCode()))
                .costWeight(0.1)
                .build());

        // Step 2..N: explode each BOM level
        int levels = countLevels(definition, maxLevel);
        for (int level = 1; level <= levels; level++) {
            steps.add(CompiledStep.builder()
                    .name("explode-bom-level-" + level)
                    .type(StepType.BOM_EXPLODE)
                    .order(order++)
                    .parameters(Map.of(
                            "level", level,
                            "batchSize", batchSize,
                            "includePhantom", includePhantom
                    ))
                    .costWeight(level * 1.5)
                    .build());
        }

        // Step N+1: aggregate totals (flatten + sum quantities)
        steps.add(CompiledStep.builder()
                .name("aggregate-bom-totals")
                .type(StepType.AGGREGATE)
                .order(order++)
                .parameters(Map.of("function", "sum", "field", "requiredQuantity"))
                .costWeight(1.0)
                .build());

        // Step N+2: cache store
        steps.add(CompiledStep.builder()
                .name("cache-store-bom-" + definition.getModelCode())
                .type(StepType.CACHE_STORE)
                .order(order)
                .parameters(Map.of("modelCode", definition.getModelCode()))
                .costWeight(0.1)
                .build());

        Map<String, Object> hints = new LinkedHashMap<>();
        hints.put("maxLevel", maxLevel);
        hints.put("batchSize", batchSize);
        hints.put("actualLevels", levels);
        hints.put("includePhantom", includePhantom);

        return CompiledPlan.builder()
                .planId("bom-" + definition.getModelCode() + "-" + definition.getVersion())
                .compilerName(TYPE)
                .steps(steps)
                .optimizationHints(hints)
                .strategy(levels > 3 ? ExecutionStrategy.BATCH : ExecutionStrategy.SEQUENTIAL)
                .compiledAt(Instant.now())
                .build();
    }

    // --- helpers ---

    /**
     * Count the actual depth of the definition tree (capped at maxLevel).
     */
    private int countLevels(DslDefinition def, int maxLevel) {
        if (def.getChildren() == null || def.getChildren().isEmpty()) {
            return 1;
        }
        int deepest = 0;
        for (DslDefinition child : def.getChildren()) {
            deepest = Math.max(deepest, countLevels(child, maxLevel));
        }
        return Math.min(1 + deepest, maxLevel);
    }

    private int toInt(Object value, int defaultValue) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return defaultValue;
    }
}
