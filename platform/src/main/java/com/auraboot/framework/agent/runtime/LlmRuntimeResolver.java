package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared LLM provider/model resolution rules for agent and chat runtimes.
 */
public final class LlmRuntimeResolver {

    private static final ObjectMapper FALLBACK_MAPPER = new ObjectMapper();

    private LlmRuntimeResolver() {
    }

    @SuppressWarnings("unchecked")
    public static String resolveAgentProviderCode(ObjectMapper objectMapper,
                                                  LlmProviderFactory providerFactory,
                                                  Map<String, Object> agentDef) {
        if (agentDef == null) {
            return null;
        }

        String guardrailsJson = nonBlankString(agentDef.get("guardrails"));
        if (guardrailsJson != null) {
            try {
                Map<String, Object> guardrails = mapper(objectMapper).readValue(guardrailsJson, Map.class);
                String provider = firstNonBlank(
                        guardrails.get("provider"),
                        guardrails.get("preferredProvider"),
                        guardrails.get("preferred_provider"));
                if (provider != null) {
                    return provider;
                }
            } catch (Exception ignored) {
                // Invalid guardrails must not block model-based provider inference.
            }
        }

        String model = nonBlankString(agentDef.get("model"));
        if (model != null && providerFactory != null) {
            String matched = providerFactory.resolveProviderByModel(model);
            if (matched != null && !matched.isBlank()) {
                return matched;
            }
        }

        return null;
    }

    public static String resolveChatProviderCode(LlmProviderFactory providerFactory,
                                                 String explicitProvider,
                                                 String explicitModel) {
        String provider = nonBlankString(explicitProvider);
        if (provider != null) {
            return provider;
        }

        String model = nonBlankString(explicitModel);
        if (model != null && providerFactory != null) {
            String matched = providerFactory.resolveProviderByModel(model);
            if (matched != null && !matched.isBlank()) {
                return matched;
            }
        }

        return null;
    }

    public static String resolveAgentModel(LlmProviderFactory providerFactory,
                                           Map<String, Object> agentDef,
                                           String providerCode) {
        return resolveAgentModel(providerFactory, agentDef, providerCode, false);
    }

    public static String resolveAgentModel(LlmProviderFactory providerFactory,
                                           Map<String, Object> agentDef,
                                           String providerCode,
                                           boolean forceFallback) {
        if (!forceFallback && agentDef != null) {
            String model = nonBlankString(agentDef.get("model"));
            if (model != null) {
                return model;
            }
        }
        if (providerFactory == null || providerCode == null || providerCode.isBlank()) {
            return null;
        }
        return providerFactory.getDefaultModel(providerCode);
    }

    public static String resolveAgentModelForProvider(LlmProviderFactory providerFactory,
                                                      Map<String, Object> agentDef,
                                                      String providerCode) {
        if (agentDef != null && providerFactory != null) {
            String model = nonBlankString(agentDef.get("model"));
            if (model != null) {
                String inferredProvider = providerFactory.resolveProviderByModel(model);
                if (providerCode != null && providerCode.equals(inferredProvider)) {
                    return model;
                }
            }
        }
        if (providerFactory == null || providerCode == null || providerCode.isBlank()) {
            return null;
        }
        return providerFactory.getDefaultModel(providerCode);
    }

    @SuppressWarnings("unchecked")
    public static List<String> resolveAgentProviderChain(ObjectMapper objectMapper,
                                                         Map<String, Object> agentDef,
                                                         String preferredProvider) {
        List<String> chain = new ArrayList<>();
        addUniqueProvider(chain, preferredProvider);

        String guardrailsJson = agentDef != null ? nonBlankString(agentDef.get("guardrails")) : null;
        if (guardrailsJson != null) {
            try {
                Map<String, Object> guardrails = mapper(objectMapper).readValue(guardrailsJson, Map.class);
                Object fallbacks = guardrails.get("fallbackProviders");
                if (fallbacks == null) {
                    fallbacks = guardrails.get("fallback_providers");
                }
                if (fallbacks instanceof List<?> list) {
                    for (Object item : list) {
                        addUniqueProvider(chain, item);
                    }
                }
            } catch (Exception ignored) {
                // Invalid guardrails must not erase the preferred provider.
            }
        }

        return List.copyOf(chain);
    }

    private static ObjectMapper mapper(ObjectMapper objectMapper) {
        return objectMapper != null ? objectMapper : FALLBACK_MAPPER;
    }

    private static String nonBlankString(Object value) {
        if (value == null) {
            return null;
        }
        String stringValue = String.valueOf(value);
        return stringValue.isBlank() ? null : stringValue;
    }

    private static String firstNonBlank(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            String candidate = nonBlankString(value);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private static void addUniqueProvider(List<String> chain, Object value) {
        String provider = nonBlankString(value);
        if (provider != null && !chain.contains(provider)) {
            chain.add(provider);
        }
    }
}
