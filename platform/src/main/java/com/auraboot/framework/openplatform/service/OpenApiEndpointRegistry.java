package com.auraboot.framework.openplatform.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import java.util.*;

/** Inventory is restricted to human administration; executable candidates require an explicit declaration. */
@Component
public class OpenApiEndpointRegistry {
    private final RequestMappingHandlerMapping mappings;
    private final OpenApiProtocolHash hashes;
    public OpenApiEndpointRegistry(@Qualifier("requestMappingHandlerMapping") @org.springframework.context.annotation.Lazy RequestMappingHandlerMapping mappings, OpenApiProtocolHash hashes) {
        this.mappings = mappings; this.hashes = hashes;
    }
    public record Candidate(String code, String name, Map<String, String> localizedDisplayNames, String internalMethod,
            String internalPath, String adapterCode, String actionSourceKind, String externalMethod, boolean targetRequired, String fingerprint,
            boolean available, String reason) { }

    public List<Candidate> candidates(String search) {
        if (search != null && search.length() > 200) throw new IllegalArgumentException("Candidate search exceeds budget");
        String query = search == null ? "" : search.toLowerCase(Locale.ROOT);
        List<Candidate> result = new ArrayList<>(); Set<String> codes = new HashSet<>();
        if (query.isBlank() || OpenApiSynchronousIntegrationService.CANDIDATE.contains(query)) {
            result.add(new Candidate(OpenApiSynchronousIntegrationService.CANDIDATE, "Installed synchronous integration",
                    Map.of("zh-CN", "已安装集成同步调用", "en-US", "Installed synchronous integration"),
                    "POST", "/api/open/v1/endpoints/{code}", OpenApiEndpointKind.INTEGRATION.code(), "", "POST", false,
                    hashes.fingerprint(Map.of("adapter", "integration.execute", "version", 1), false), true, null));
        }
        mappings.getHandlerMethods().forEach((mapping, handler) -> {
            var declaration = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), OpenApiEndpointCandidate.class);
            for (String path : new TreeSet<>(mapping.getPatternValues())) {
                if (!path.startsWith("/api/") || path.startsWith("/api/open/")) continue;
                for (var method : mapping.getMethodsCondition().getMethods()) {
                    if (declaration == null) {
                        if (query.isBlank() || (method.name() + " " + path).toLowerCase(Locale.ROOT).contains(query))
                            result.add(new Candidate(null, "Internal endpoint", Map.of("zh-CN", "内部接口", "en-US", "Internal endpoint"),
                                    method.name(), path, null, null, null, false, null, false, "machine_adapter_required"));
                        continue;
                    }
                    if (!declaration.code().matches("[a-z][a-z0-9.-]{1,99}") || !declaration.version().matches("[A-Za-z0-9._-]{1,64}")
                            || declaration.name().isBlank() || declaration.chineseName().isBlank())
                        throw new IllegalStateException("Incomplete Controller machine adaptation declaration");
                    if (!codes.add(declaration.code())) throw new IllegalStateException("Controller candidate must identify exactly one route and method");
                    var kind = declaration.adapter();
                    if (!(kind.method().equals(method.name()) || kind == OpenApiEndpointKind.RESOURCE_UPDATE && "PUT".equals(method.name()))
                            || kind == OpenApiEndpointKind.ACTION && !Set.of("command", "handler").contains(declaration.actionSourceKind())
                            || kind != OpenApiEndpointKind.ACTION && !declaration.actionSourceKind().isEmpty())
                        throw new IllegalStateException("Controller method or business implementation kind does not match its machine adapter");
                    String fingerprint = hashes.fingerprint(Map.of("code", declaration.code(), "version", declaration.version(),
                            "method", method.name(), "path", path, "handler", handler.getMethod().toGenericString(), "adapter", kind.code(), "actionSourceKind", declaration.actionSourceKind()), false);
                    if (query.isBlank() || (declaration.code() + " " + declaration.name() + " " + path).toLowerCase(Locale.ROOT).contains(query))
                        result.add(new Candidate(declaration.code(), declaration.name(), Map.of("zh-CN", declaration.chineseName(), "en-US", declaration.name()),
                                method.name(), path, kind.code(), declaration.actionSourceKind(), kind.method(), kind.target(), fingerprint, true, null));
                }
            }
        });
        if (result.size() > 5000) throw new IllegalStateException("Controller inventory exceeds its bounded catalog; narrow the search");
        return result.stream().sorted(Comparator.comparing(Candidate::available).reversed().thenComparing(Candidate::internalPath)
                .thenComparing(Candidate::internalMethod)).toList();
    }
    public Candidate require(String code) {
        return candidates(code).stream().filter(candidate -> code.equals(candidate.code())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Controller has no registered machine adaptation"));
    }
}
