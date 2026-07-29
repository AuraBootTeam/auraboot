package com.auraboot.framework.agent.provider;

/**
 * Provider-neutral capability negotiation result for one concrete model.
 *
 * <p>Business runtimes ask this contract instead of branching on provider or
 * model names. Adapter/catalog code owns any vendor-specific detection.
 */
public record ModelCapabilityProfile(
        boolean chat,
        boolean streaming,
        boolean toolCalling,
        boolean requiredToolChoice,
        boolean structuredOutput,
        boolean vision,
        boolean thinking
) {

    public static ModelCapabilityProfile conservative(boolean toolCalling) {
        return new ModelCapabilityProfile(
                true,
                true,
                toolCalling,
                false,
                false,
                false,
                false);
    }
}
