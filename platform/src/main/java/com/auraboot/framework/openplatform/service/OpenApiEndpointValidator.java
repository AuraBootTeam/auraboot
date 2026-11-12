package com.auraboot.framework.openplatform.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

/** Freezes both Controller adaptation and the complete delegated public business contract. */
@Component
@RequiredArgsConstructor
public class OpenApiEndpointValidator {
    private final OpenApiEndpointRegistry registry;
    private final OpenApiPublicationService resources;
    private final OpenApiActionPublicationService actions;
    private final OpenApiQueryPublicationService queries;
    private final OpenApiProtocolHash hashes;
    private final List<OpenApiCapabilityContributor> contributors;
    private final OpenApiSynchronousIntegrationService integrations;
    public record Description(OpenApiEndpointRegistry.Candidate candidate, String bindingCode, String fingerprint, int minTimeoutMs) { }
    public Description describe(Long tenant, String candidateCode, String bindingCode) {
        var candidate = registry.require(candidateCode);
        var kind = OpenApiEndpointKind.fromCode(candidate.adapterCode());
        Object binding; int budget;
        if (kind.resource()) {
            var resource = resource(tenant, bindingCode);
            if (resource.operations().stream().noneMatch(operation -> operation.code().equals(kind.operation())))
                throw new IllegalArgumentException("Bound resource does not publish this operation");
            binding = resource; budget = resource.timeoutMs();
        } else if (kind == OpenApiEndpointKind.ACTION) {
            var action = actions.active(tenant, bindingCode).orElseThrow(() -> new IllegalArgumentException("Published business action required"));
            if (!candidate.actionSourceKind().equals(action.sourceKind())) throw new IllegalArgumentException("Controller adaptation does not match this business implementation kind");
            binding = Map.of("action", action, "resource", resource(tenant, action.resourceCode())); budget = action.timeoutMs();
        } else if (kind == OpenApiEndpointKind.INTEGRATION) {
            binding = integrations.description(bindingCode); budget = integrations.timeout(bindingCode);
        } else {
            var query = queries.active(tenant, bindingCode).orElseThrow(() -> new IllegalArgumentException("Published named query required"));
            var sourceResources = new TreeMap<String, Object>();
            query.sourceResources().forEach((model, code) -> sourceResources.put(model, resource(tenant, code)));
            binding = Map.of("query", query, "resources", sourceResources); budget = query.timeoutMs();
        }
        return new Description(candidate, bindingCode, hashes.fingerprint(Map.of("candidate", candidate.fingerprint(),
                "adapter", candidate.adapterCode(), "binding", binding), true), budget);
    }
    public void validate(Long tenant, OpenApiEndpointContract contract) {
        if (new OpenApiCapabilityRegistry(contributors).resolve(contract.kind().method(), contract.path()).isPresent())
            throw new IllegalArgumentException("Endpoint route is reserved by a fixed public capability");
        var source = describe(tenant, contract.candidateCode(), contract.bindingCode());
        if (!source.candidate().adapterCode().equals(contract.adapterCode()) || !source.fingerprint().equals(contract.sourceFingerprint())
                || contract.timeoutMs() < source.minTimeoutMs())
            throw new IllegalArgumentException("Endpoint source, data contract or execution budget changed");
    }
    public Map<String, Object> document(Long tenant, OpenApiEndpointContract contract) {
        validate(tenant, contract);
        var kind = contract.kind(); Map<String, Object> base; String original;
        if (kind.resource()) {
            var resource = resource(tenant, contract.bindingCode()); base = OpenApiContractArtifacts.resource(resource);
            original = "/api/open/v1/resources/" + resource.code() + (kind.target() ? "/{recordPid}" : "");
        } else if (kind == OpenApiEndpointKind.ACTION) {
            var action = actions.active(tenant, contract.bindingCode()).orElseThrow();
            base = OpenApiContractArtifacts.action(action, resource(tenant, action.resourceCode()));
            original = "/api/open/v1/actions/" + action.code() + ":execute";
        } else if (kind == OpenApiEndpointKind.INTEGRATION) {
            base = integrations.document(contract.bindingCode(), contract.path()); original = contract.path();
        } else {
            var query = queries.active(tenant, contract.bindingCode()).orElseThrow(); base = OpenApiContractArtifacts.query(query);
            original = "/api/open/v1/queries/" + query.code() + ":execute";
        }
        @SuppressWarnings("unchecked") var paths = (Map<String, Map<String, Map<String, Object>>>) base.get("paths");
        var operation = new LinkedHashMap<>(paths.get(original).get(kind.method().toLowerCase(Locale.ROOT)));
        var requiredScopes = new TreeSet<String>(); requiredScopes.add(contract.scopes().get("invoke"));
        if (kind.resource()) requiredScopes.add(resource(tenant, contract.bindingCode()).scopes().get(OpenApiResourceContract.Operation.valueOf(kind.operation().toUpperCase(Locale.ROOT))));
        else if (kind == OpenApiEndpointKind.ACTION) {
            var action = actions.active(tenant, contract.bindingCode()).orElseThrow(); requiredScopes.add(action.scopes().get("execute"));
            var operationKind = "command".equals(action.sourceKind()) ? OpenApiResourceContract.Operation.UPDATE : OpenApiResourceContract.Operation.LIST;
            requiredScopes.add(resource(tenant, action.resourceCode()).scopes().get(operationKind));
        } else if (kind == OpenApiEndpointKind.INTEGRATION) {
            requiredScopes.add(integrations.scope(contract.bindingCode()));
        } else {
            var query = queries.active(tenant, contract.bindingCode()).orElseThrow(); requiredScopes.add(query.scopes().get("execute"));
            query.sourceResources().values().forEach(code -> requiredScopes.add(resource(tenant, code).scopes().get(OpenApiResourceContract.Operation.LIST)));
        }

        operation.put("operationId", "endpoint." + contract.code() + ".invoke");
        operation.put("security", List.of(Map.of("integrationToken", List.of()), Map.of("oauth2ClientCredentials", List.copyOf(requiredScopes))));
        operation.put("x-required-scope", contract.scopes().get("invoke"));
        operation.put("x-required-scopes", requiredScopes);
        operation.put("x-bound-contract", contract.bindingCode()); operation.put("x-timeout-ms", contract.timeoutMs());
        operation.put("x-max-concurrent", contract.maxConcurrent());
        var document = new LinkedHashMap<>(base); document.remove("webhooks");
        document.put("paths", Map.of(contract.path(), Map.of(kind.method().toLowerCase(Locale.ROOT), operation)));
        document.put("info", Map.of("title", "Public endpoint " + contract.code(), "version", String.valueOf(contract.schemaVersion())));
        @SuppressWarnings("unchecked") var components = new LinkedHashMap<>((Map<String, Object>) base.get("components"));
        @SuppressWarnings("unchecked") var schemes = new LinkedHashMap<>((Map<String, Object>) components.get("securitySchemes"));
        var scopeDescriptions = new TreeMap<String, String>(); requiredScopes.forEach(scope -> scopeDescriptions.put(scope, "Current endpoint or bound business authority required"));
        schemes.put("oauth2ClientCredentials", Map.of("type", "oauth2", "flows", Map.of("clientCredentials", Map.of("tokenUrl", "/oauth2/token",
                "scopes", scopeDescriptions))));
        components.put("securitySchemes", schemes); document.put("components", components);
        document.put("x-schema-version", contract.schemaVersion());
        return document;
    }
    public String types(Long tenant, OpenApiEndpointContract contract) {
        validate(tenant, contract); String base; String request; String result;
        if (contract.kind().resource()) {
            base = OpenApiContractArtifacts.resourceTypes(resource(tenant, contract.bindingCode()));
            request = switch (contract.kind()) {
                case RESOURCE_CREATE -> "CreateInput";
                case RESOURCE_UPDATE -> "UpdateInput";
                case RESOURCE_LIST -> "{ limit?: number; cursor?: string; filters?: string; sort?: string; direction?: 'ASC' | 'DESC' }";
                default -> "Record<string, never>";
            };
            result = contract.kind() == OpenApiEndpointKind.RESOURCE_DELETE ? "void" : contract.kind() == OpenApiEndpointKind.RESOURCE_LIST
                    ? "{ items: Resource[]; hasMore: boolean; nextCursor: string | null }" : "Resource";
        } else if (contract.kind() == OpenApiEndpointKind.ACTION) {
            var action = actions.active(tenant, contract.bindingCode()).orElseThrow();
            base = OpenApiContractArtifacts.actionTypes(action, resource(tenant, action.resourceCode())); request = "ActionRequest"; result = "ActionResult";
        } else if (contract.kind() == OpenApiEndpointKind.INTEGRATION) {
            // Exact JSON schemas are distributed in the OpenAPI artifact; do not invent structural types.
            base = ""; request = "{ input: Record<string, unknown> }"; result = "Record<string, unknown>";
        } else {
            var query = queries.active(tenant, contract.bindingCode()).orElseThrow();
            base = OpenApiContractArtifacts.queryTypes(query);
            request = "{ parameters" + (query.parameters().values().stream().anyMatch(OpenApiQueryContract.Parameter::required) ? "" : "?") + ": QueryParameters; limit?: number; cursor?: string }";
            result = "{ items: QueryItem[]; hasMore: boolean; nextCursor: string | null }";
        }
        return base + "\n// " + contract.kind().method() + " " + contract.path() + "\nexport type EndpointRequest = " + request
                + ";\nexport type EndpointResult = " + result + ";\n";
    }
    public int defaultPageSize(Long tenant, OpenApiEndpointContract contract) { return Math.min(20, resource(tenant, contract.bindingCode()).maxPageSize()); }
    private OpenApiResourceContract resource(Long tenant, String code) {
        return resources.active(tenant, code).orElseThrow(() -> new IllegalArgumentException("Published bound resource required"));
    }
}
