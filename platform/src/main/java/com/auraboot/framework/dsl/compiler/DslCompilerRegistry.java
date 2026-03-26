package com.auraboot.framework.dsl.compiler;

import com.auraboot.framework.dsl.compiler.model.CompiledPlan;
import com.auraboot.framework.dsl.compiler.model.DslDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Central registry that dispatches compilation requests to the appropriate {@link DslCompiler}.
 * Also manages a simple in-memory plan cache.
 */
@Slf4j
@Component
public class DslCompilerRegistry {

    private final Map<String, DslCompiler> compilers;
    private final Map<String, CompiledPlan> planCache = new ConcurrentHashMap<>();

    public DslCompilerRegistry(List<DslCompiler> compilerBeans) {
        this.compilers = compilerBeans.stream()
                .collect(Collectors.toMap(DslCompiler::supportedType, c -> c));
        log.info("Registered {} DSL compilers: {}", compilers.size(), compilers.keySet());
    }

    /**
     * Compile a definition, using the cache when possible.
     */
    public CompiledPlan compile(DslDefinition definition) {
        String type = definition.getType();
        DslCompiler compiler = compilers.get(type);
        if (compiler == null) {
            throw new IllegalArgumentException("No DSL compiler registered for type: " + type);
        }

        String cacheKey = buildCacheKey(definition);
        CompiledPlan cached = planCache.get(cacheKey);
        if (cached != null) {
            log.debug("Cache hit for plan {}", cacheKey);
            return CompiledPlan.builder()
                    .planId(cached.getPlanId())
                    .compilerName(cached.getCompilerName())
                    .steps(cached.getSteps())
                    .optimizationHints(cached.getOptimizationHints())
                    .strategy(cached.getStrategy())
                    .compiledAt(cached.getCompiledAt())
                    .cached(true)
                    .build();
        }

        CompiledPlan plan = compiler.compile(definition);
        planCache.put(cacheKey, plan);
        log.info("Compiled plan {} with {} steps (strategy={})",
                plan.getPlanId(), plan.getSteps().size(), plan.getStrategy());
        return plan;
    }

    /**
     * Return cache statistics.
     */
    public Map<String, Object> cacheStats() {
        return Map.of(
                "size", planCache.size(),
                "keys", planCache.keySet()
        );
    }

    /**
     * Clear the entire plan cache.
     */
    public void clearCache() {
        int size = planCache.size();
        planCache.clear();
        log.info("Cleared {} cached plans", size);
    }

    /**
     * Evict a single cache entry.
     */
    public boolean evict(String cacheKey) {
        return planCache.remove(cacheKey) != null;
    }

    /**
     * List registered compiler types.
     */
    public List<String> registeredTypes() {
        return List.copyOf(compilers.keySet());
    }

    // --- internal ---

    private String buildCacheKey(DslDefinition def) {
        return def.getType() + ":" + def.getModelCode() + ":" + def.getVersion();
    }
}
