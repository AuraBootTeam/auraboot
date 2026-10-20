package com.auraboot.framework.meta.ai;

/** Consumer-owned field generation boundary; model protocols stay in the adapter. */
@FunctionalInterface
public interface AiFieldGenerator {
    AiFieldProcessor.AiGenerationResult generate(AiFieldProcessor.AiGenerationRequest request);
}
