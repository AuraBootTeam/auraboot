package com.auraboot.framework.meta.service.executor;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Registry of ModelDataExecutor beans, keyed by sourceType.
 *
 * Physical models have no registered executor here — they're handled by
 * the inline legacy path in DynamicDataServiceImpl. This registry only
 * fires for sourceType != 'physical' (namedQuery / endpoint / sqlView).
 */
@Component
public class ExecutorRegistry {

    private final Map<String, ModelDataExecutor> executorsBySourceType;

    public ExecutorRegistry(List<ModelDataExecutor> executors) {
        this.executorsBySourceType = executors.stream()
            .collect(Collectors.toUnmodifiableMap(
                ModelDataExecutor::sourceType,
                e -> e,
                (a, b) -> {
                    throw new IllegalStateException(
                        "Duplicate ModelDataExecutor for sourceType=" + a.sourceType());
                }));
    }

    public Optional<ModelDataExecutor> resolve(String sourceType) {
        if (sourceType == null || "physical".equals(sourceType)) {
            return Optional.empty();
        }
        return Optional.ofNullable(executorsBySourceType.get(sourceType));
    }
}
