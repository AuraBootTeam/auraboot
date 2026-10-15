package com.auraboot.framework.openplatform.service;

import java.util.*;

/** Public business interface bound to one published resource and an explicit immutable source. */
public record OpenApiActionContract(String code, String sourceKind, String sourceCode, String resourceCode,
        int schemaVersion, String sourceFingerprint, String permission, Map<String, String> scopes,
        Map<String, OpenApiQueryContract.Parameter> parameters, Map<String, String> inputFields,
        Set<String> effectFields, Map<String, OpenApiResourceContract.Type> outputFields, int timeoutMs, int maxConcurrent) {
    public OpenApiActionContract {
        require(code != null && code.matches("[a-z][a-z0-9.-]{1,92}"), "Invalid public business API code");
        require(sourceKind != null && Set.of("command", "handler").contains(sourceKind), "Explicit business API source kind required");
        require(sourceCode != null && sourceCode.matches("[A-Za-z][A-Za-z0-9_.:-]{0,127}"), "Invalid business API source");
        require(resourceCode != null && resourceCode.matches("[a-z][a-z0-9.-]{1,99}"), "Bound public resource required");
        require(schemaVersion > 0 && sourceFingerprint != null && sourceFingerprint.matches("[a-f0-9]{64}"), "Pinned business API source required");
        scopes = Map.copyOf(scopes); parameters = Map.copyOf(parameters); inputFields = Map.copyOf(inputFields);
        effectFields = Set.copyOf(effectFields); outputFields = Map.copyOf(outputFields);
        require(scopes.keySet().equals(Set.of("execute")) && scopes.get("execute").matches("[a-z][a-z0-9.:-]{1,159}"), "Exactly one execute scope required");
        require(parameters.size() <= 32 && effectFields.size() <= 64 && outputFields.size() <= 32, "Business API schema budget exceeded");
        parameters.keySet().forEach(key -> require(key.matches("[A-Za-z][A-Za-z0-9]{0,99}")
                && !Set.of("tenantId", "currentUserId", "currentUserPid").contains(key), "Reserved business API input"));
        if ("command".equals(sourceKind)) require(permission != null && permission.matches("[A-Za-z][A-Za-z0-9._:-]{1,159}")
                && inputFields.keySet().equals(parameters.keySet()) && outputFields.isEmpty(), "Command permission, mapping and resource result required");
        else require(permission == null && inputFields.isEmpty() && effectFields.isEmpty() && !outputFields.isEmpty(), "Read-only handler contract required");
        require(timeoutMs >= 1 && timeoutMs <= 30000 && maxConcurrent >= 1 && maxConcurrent <= 32, "Invalid business API budget");
    }
    public Map<String, Object> validateInput(Map<String, Object> input) {
        require(input != null && parameters.keySet().containsAll(input.keySet()), "Unknown business API input");
        Map<String, Object> result = new LinkedHashMap<>();
        parameters.forEach((key, parameter) -> {
            require(!parameter.required() || input.containsKey(key), "Required business API input missing");
            if (input.containsKey(key)) result.put(key, parameter.validate(key, input.get(key)));
        });
        return Collections.unmodifiableMap(result);
    }
    public String storageCode() { return "action:" + code; }
    public void requireCompatibleWith(OpenApiActionContract previous) {
        require(code.equals(previous.code()) && sourceKind.equals(previous.sourceKind()) && sourceCode.equals(previous.sourceCode())
                && resourceCode.equals(previous.resourceCode()) && sourceFingerprint.equals(previous.sourceFingerprint())
                && Objects.equals(permission, previous.permission()) && scopes.equals(previous.scopes()) && parameters.equals(previous.parameters())
                && inputFields.equals(previous.inputFields()) && effectFields.equals(previous.effectFields()) && outputFields.equals(previous.outputFields())
                && timeoutMs >= previous.timeoutMs() && maxConcurrent >= previous.maxConcurrent(), "Business API semantics cannot change within a major version");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
