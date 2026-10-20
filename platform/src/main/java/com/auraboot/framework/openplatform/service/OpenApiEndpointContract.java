package com.auraboot.framework.openplatform.service;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** An external HTTP contract pins an actual internal route and an already governed data contract. */
public record OpenApiEndpointContract(String code, String candidateCode, String adapterCode, String bindingCode,
        int schemaVersion, String sourceFingerprint, Map<String, String> scopes, int timeoutMs, int maxConcurrent) {
    public OpenApiEndpointContract {
        require(code != null && code.matches("[a-z][a-z0-9.-]{1,90}"), "Invalid endpoint code");
        require(candidateCode != null && candidateCode.matches("[a-z][a-z0-9.-]{1,99}"), "Explicit Controller candidate required");
        OpenApiEndpointKind.fromCode(adapterCode);
        require(bindingCode != null && bindingCode.matches("[a-z][a-z0-9.-]{1,99}"), "Published business binding required");
        require(schemaVersion > 0 && sourceFingerprint != null && sourceFingerprint.matches("[a-f0-9]{64}"), "Pinned endpoint source required");
        require(scopes != null && scopes.keySet().equals(Set.of("invoke")) && scopes.get("invoke") != null
                && scopes.get("invoke").matches("[a-z][a-z0-9.:-]{1,159}"), "Exactly one endpoint invocation scope required");
        scopes = Map.copyOf(scopes);
        require(timeoutMs >= 1 && timeoutMs <= 30000 && maxConcurrent >= 1 && maxConcurrent <= 32, "Invalid endpoint execution budget");
    }
    public OpenApiEndpointKind kind() { return OpenApiEndpointKind.fromCode(adapterCode); }
    public String path() { return "/api/open/v1/endpoints/" + code + (kind().target() ? "/{recordPid}" : ""); }
    public String storageCode() { return "endpoint:" + code; }
    public void requireCompatibleWith(OpenApiEndpointContract previous) {
        require(code.equals(previous.code) && candidateCode.equals(previous.candidateCode) && adapterCode.equals(previous.adapterCode)
                && bindingCode.equals(previous.bindingCode) && sourceFingerprint.equals(previous.sourceFingerprint)
                && scopes.equals(previous.scopes) && timeoutMs >= previous.timeoutMs && maxConcurrent >= previous.maxConcurrent,
                "Endpoint semantics cannot change within a public address; publish a new endpoint");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
