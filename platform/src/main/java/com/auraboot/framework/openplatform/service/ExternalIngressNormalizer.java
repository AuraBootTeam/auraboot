package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.openplatform.mapper.ExternalIngressDefaultsMapper;
import com.auraboot.framework.plugin.extension.integration.ExternalEventIngressExtension.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** Persists server defaults atomically with business acceptance and reliable enqueue. */
@Service
@RequiredArgsConstructor
public class ExternalIngressNormalizer {
    private final InstalledIntegrationRegistry registry;
    private final ExternalIngressDefaultsMapper records;
    private final OpenPlatformSecretCodec codec;
    private final ObjectMapper json;

    public boolean supports(String source) { return registry.requestNormalizer(source).isPresent(); }

    @Transactional(propagation = Propagation.MANDATORY)
    public String normalize(long tenant, long installation, String source, String header, String rawBody) {
        Map<String, Object> request;
        try {
            JsonNode node = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(rawBody);
            if (node == null || !node.isObject()) throw invalidJson();
            request = json.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (JsonProcessingException malformed) {
            throw invalidJson();
        }
        var policy = registry.requestNormalizer(source).orElseThrow(() -> new IllegalStateException("Ingress normalizer is unavailable"));
        var prepared = policy.normalize(request, new RequestDefaults(UUID.randomUUID().toString(), Instant.now()));
        String fingerprint = codec.sha256(canonical(request));
        var identities = new TreeMap<>(prepared.stableIdentities());
        if (header != null) identities.put("header", header);
        // A previously committed identity remains a conflict even when the changed body is invalid.
        for (var identity : identities.entrySet()) {
            var stored = records.find(tenant, installation, source, identity.getKey(), codec.sha256(identity.getValue()));
            if (stored != null && !fingerprint.equals(stored.requestHash()))
                throw new ExternalEventIngressService.IdempotencyConflictException();
        }
        if (!prepared.errors().isEmpty()) {
            boolean business = prepared.errors().stream().anyMatch(error -> error.field().startsWith("data."));
            throw new ExternalEventIngressService.InvalidExternalEventException(
                    business ? "invalid_business_request" : "invalid_event_body",
                    "Request validation failed. Correct the listed fields and retry.", prepared.errors());
        }
        String candidate = encode(prepared.envelope());
        String existing = null;
        var claimed = new ArrayList<Map.Entry<String, String>>();
        // All aliases use the same global lock order to avoid reversed-alias deadlocks.
        for (var identity : identities.entrySet()) {
            if (!identity.getKey().matches("[a-z][a-z0-9_-]{0,31}")) throw new IllegalStateException("Invalid ingress identity kind");
            String hashed = codec.sha256(identity.getValue());
            if (records.claim(tenant, installation, source, identity.getKey(), hashed, fingerprint, candidate) == 1) {
                claimed.add(Map.entry(identity.getKey(), hashed));
            } else {
                var stored = records.find(tenant, installation, source, identity.getKey(), hashed);
                if (stored == null || !fingerprint.equals(stored.requestHash()))
                    throw new ExternalEventIngressService.IdempotencyConflictException();
                if (existing != null && !canonicalTree(existing).equals(canonicalTree(stored.envelope())))
                    throw new ExternalEventIngressService.IdempotencyConflictException();
                existing = stored.envelope();
            }
        }
        String chosen = existing == null ? candidate : existing;
        for (var identity : claimed) {
            if (records.align(tenant, installation, source, identity.getKey(), identity.getValue(), fingerprint, chosen) != 1)
                throw new IllegalStateException("Ingress defaults were not persisted");
        }
        return chosen;
    }

    /** Canonical object ordering; array order, explicit nulls, types and omitted fields remain significant. */
    public String canonical(Map<String, Object> value) { return encode(sorted(value)); }
    private Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new TreeMap<String, Object>(); map.forEach((key, item) -> result.put((String)key, sorted(item))); return result;
        }
        if (value instanceof List<?> list) return list.stream().map(this::sorted).toList();
        return value;
    }
    private String canonicalTree(String value) {
        try { return canonical(json.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() { })); }
        catch (JsonProcessingException invalidStoredValue) { throw new IllegalStateException("Stored ingress defaults are invalid", invalidStoredValue); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException invalidValue) { throw new IllegalStateException("Could not serialize ingress defaults", invalidValue); }
    }
    private static ExternalEventIngressService.InvalidExternalEventException invalidJson() {
        return new ExternalEventIngressService.InvalidExternalEventException("invalid_event_body",
                "The request must be one JSON object with unique field names.",
                List.of(new FieldError("$", "invalid_json", "Check JSON syntax, duplicate fields and trailing content.")));
    }
}
