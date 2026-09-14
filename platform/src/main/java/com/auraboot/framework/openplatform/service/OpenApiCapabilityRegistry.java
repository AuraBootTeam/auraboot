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

/** Fail-closed registry for externally published routes. */
@Component
public class OpenApiCapabilityRegistry {
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final List<Capability> capabilities;

    public OpenApiCapabilityRegistry(List<OpenApiCapabilityContributor> contributors) {
        List<Capability> published = new ArrayList<>();
        published.add(new Capability("platform.whoami", HttpMethod.GET,
                "/api/open/v1/whoami", "openapi.profile.read", "tenant-wide", 1));
        published.add(new Capability("automation.external-event.publish", HttpMethod.POST,
                "/api/open/v1/event-sources/{sourceCode}/events", "automation.events.write",
                "tenant-wide", 1));
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
        return capabilities;
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
