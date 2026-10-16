package com.auraboot.framework.openplatform.service;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Fail-closed registry for externally published routes. */
@Component
public class OpenApiCapabilityRegistry {
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final List<Capability> capabilities;
    private OpenApiPublicationService dynamicPublications;
    private OpenApiQueryPublicationService dynamicQueries;
    private OpenApiActionPublicationService dynamicActions;
    private OpenApiEndpointPublicationService dynamicEndpoints;

    @org.springframework.beans.factory.annotation.Autowired
    public void bindEndpoints(OpenApiEndpointPublicationService endpoints) { this.dynamicEndpoints = endpoints; }

    @org.springframework.beans.factory.annotation.Autowired
    public OpenApiCapabilityRegistry(List<OpenApiCapabilityContributor> contributors, OpenApiPublicationService publications,
                                     OpenApiQueryPublicationService queries, OpenApiActionPublicationService actions) {
        this(contributors);
        this.dynamicPublications = publications;
        this.dynamicQueries = queries;
        this.dynamicActions = actions;
    }

    public OpenApiCapabilityRegistry(List<OpenApiCapabilityContributor> contributors, OpenApiPublicationService publications,
                                     OpenApiQueryPublicationService queries) { this(contributors, publications, queries, null); }

    public OpenApiCapabilityRegistry(List<OpenApiCapabilityContributor> contributors, OpenApiPublicationService publications) {
        this(contributors, publications, null);
    }

    public OpenApiCapabilityRegistry(List<OpenApiCapabilityContributor> contributors) {
        List<Capability> published = new ArrayList<>();
        published.add(new Capability("platform.whoami", HttpMethod.GET,
                "/api/open/v1/whoami", "openapi.profile.read", "tenant-wide", 1));
        published.add(new Capability("automation.external-event.publish", HttpMethod.POST,
                "/api/open/v1/event-sources/{sourceCode}/events", "automation.events.write",
                "tenant-wide", 1));
        published.add(new Capability("platform.event-catalog.list", HttpMethod.GET,
                "/api/open/v1/event-catalog", "openapi.events.read", "tenant-wide", 1));
        published.add(new Capability("assets.get", HttpMethod.GET,
                "/api/open/v1/resources/assets/{recordPid}", "assets.read", "tenant-wide", 1));
        published.add(new Capability("assets.list", HttpMethod.GET,
                "/api/open/v1/resources/assets", "assets.read", "tenant-wide", 1));
        published.add(new Capability("assets.assign", HttpMethod.POST,
                "/api/open/v1/commands/assets.assign:execute", "assets.manage", "tenant-wide", 1));
        published.add(new Capability("inventory.stock-ins.get", HttpMethod.GET,
                "/api/open/v1/resources/inventory.stock-ins/{recordPid}",
                "inventory.stock-ins.read", "tenant-wide", 1));
        published.add(new Capability("inventory.stock-ins.list", HttpMethod.GET,
                "/api/open/v1/resources/inventory.stock-ins", "inventory.stock-ins.read", "tenant-wide", 1));
        published.add(new Capability("inventory.stock-ins.confirm", HttpMethod.POST,
                "/api/open/v1/commands/inventory.stock-ins.confirm:execute",
                "inventory.stock-ins.manage", "tenant-wide", 1));
        contributors.forEach(contributor -> published.addAll(contributor.capabilities()));
        this.capabilities = published.stream()
                .sorted(Comparator.comparing(Capability::code))
                .toList();
        assertUnique(published);
    }

    public Optional<Capability> resolve(String method, String path) {
        return capabilities.stream()
                .filter(capability -> capability.method().matches(method)
                        && pathMatcher.match(capability.pathPattern(), path))
                .findFirst();
    }

    public List<Capability> list() {
        if (dynamicPublications == null || !com.auraboot.framework.application.tenant.MetaContext.exists()) return capabilities;
        Long tenantId = com.auraboot.framework.application.tenant.MetaContext.getCurrentTenantId();
        if (dynamicPublications == null || tenantId == null) return capabilities;
        List<Capability> published = new ArrayList<>(capabilities);
        dynamicPublications.publishedContracts(tenantId).forEach(contract -> published.addAll(resourceCapabilities(contract)));
        if (dynamicQueries != null) dynamicQueries.published(tenantId).forEach(contract -> published.add(queryCapability(contract)));
        if (dynamicActions != null) dynamicActions.published(tenantId).forEach(contract -> published.add(actionCapability(contract)));
        if (dynamicEndpoints != null) dynamicEndpoints.published(tenantId).forEach(contract -> published.add(endpointCapability(contract)));
        assertUnique(published);
        return List.copyOf(published);
    }

    /** Uses the fixed contribution registry as the single source of reserved public routes. */
    public void requireAvailableResourceRoutes(OpenApiResourceContract contract) {
        for (Capability candidate : resourceCapabilities(contract)) {
            if (resolve(candidate.method().name(), candidate.pathPattern()).isPresent()) {
                throw new IllegalArgumentException("Publication route is already reserved: " + candidate.pathPattern());
            }
        }
    }

    public Optional<Capability> resolve(Long tenantId, String method, String path) {
        Optional<Capability> fixed = resolve(method, path);
        if (fixed.isPresent() || dynamicPublications == null) return fixed;
        String[] segments = path.split("/", -1);
        if (dynamicEndpoints != null && (segments.length == 6 || segments.length == 7) && "endpoints".equals(segments[4])) {
            return dynamicEndpoints.active(tenantId, segments[5]).filter(contract -> contract.kind().target() == (segments.length == 7))
                    .map(this::endpointCapability).filter(capability -> capability.method().matches(method) && pathMatcher.match(capability.pathPattern(), path));
        }
        if (dynamicActions != null && segments.length == 6 && "actions".equals(segments[4]) && segments[5].endsWith(":execute")) {
            String code = segments[5].substring(0, segments[5].length() - ":execute".length());
            return dynamicActions.active(tenantId, code).map(this::actionCapability).filter(capability -> capability.method().matches(method));
        }
        if (dynamicQueries != null && segments.length == 6 && "queries".equals(segments[4]) && segments[5].endsWith(":execute")) {
            String code = segments[5].substring(0, segments[5].length() - ":execute".length());
            return dynamicQueries.active(tenantId, code).map(this::queryCapability).filter(capability -> capability.method().matches(method));
        }
        if (segments.length < 6 || segments.length > 7 || !"resources".equals(segments[4])) return Optional.empty();
        return dynamicPublications.active(tenantId, segments[5]).stream().flatMap(contract -> resourceCapabilities(contract).stream())
                .filter(capability -> capability.method().matches(method) && pathMatcher.match(capability.pathPattern(), path)).findFirst();
    }

    public void requireAvailableQueryRoute(OpenApiQueryContract contract) {
        var candidate = queryCapability(contract);
        if (resolve(candidate.method().name(), candidate.pathPattern()).isPresent()) throw new IllegalArgumentException("Query route is already reserved");
    }

    private Capability queryCapability(OpenApiQueryContract contract) {
        return new Capability("query." + contract.code() + ".execute", HttpMethod.POST,
                "/api/open/v1/queries/" + contract.code() + ":execute", contract.scopes().get("execute"), "source-grants", contract.schemaVersion());
    }

    private Capability endpointCapability(OpenApiEndpointContract contract) {
        return new Capability("endpoint." + contract.code() + ".invoke", HttpMethod.valueOf(contract.kind().method()), contract.path(),
                contract.scopes().get("invoke"), "explicit-endpoint-and-bound-business-grants", contract.schemaVersion());
    }

    private Capability actionCapability(OpenApiActionContract contract) {
        return new Capability("action." + contract.code() + ".execute", HttpMethod.POST,
                "/api/open/v1/actions/" + contract.code() + ":execute", contract.scopes().get("execute"), "bound-resource-grants", contract.schemaVersion());
    }

    private List<Capability> resourceCapabilities(OpenApiResourceContract contract) {
        return contract.operations().stream().map(operation -> {
            String path = "/api/open/v1/resources/" + contract.code();
            HttpMethod method = switch (operation) {
                case LIST, GET -> HttpMethod.GET;
                case CREATE -> HttpMethod.POST;
                case UPDATE -> HttpMethod.PATCH;
                case DELETE -> HttpMethod.DELETE;
            };
            if (Set.of(OpenApiResourceContract.Operation.GET, OpenApiResourceContract.Operation.UPDATE,
                    OpenApiResourceContract.Operation.DELETE).contains(operation)) path += "/{recordPid}";
            return new Capability("resource." + contract.code() + "." + operation.code(), method, path,
                    contract.scopes().get(operation), contract.dataPolicy().code(), contract.schemaVersion());
        }).toList();
    }

    private void assertUnique(List<Capability> published) {
        long distinctCodes = published.stream().map(Capability::code).distinct().count();
        long distinctRoutes = published.stream()
                .map(item -> item.method() + " " + item.pathPattern()).distinct().count();
        if (distinctCodes != published.size() || distinctRoutes != published.size()) {
            throw new IllegalStateException("Open API capability codes and routes must be unique");
        }
    }

    public record Capability(String code,
                             @JsonSerialize(using = ToStringSerializer.class) HttpMethod method,
                             String pathPattern,
                             String requiredScope, String dataPolicy, int schemaVersion) {
        public Capability {
            if (code == null || code.isBlank() || method == null || pathPattern == null
                    || !pathPattern.startsWith("/api/open/v1/")
                    || requiredScope == null || requiredScope.isBlank()
                    || dataPolicy == null || dataPolicy.isBlank() || schemaVersion < 1) {
                throw new IllegalArgumentException("Incomplete public capability declaration");
            }
        }
    }
}
