package com.auraboot.framework.plugin.rest;

import com.auraboot.framework.plugin.extension.AuthPolicy;
import com.auraboot.framework.plugin.pf4j.RestEndpointRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

/** Exact credential-free browser origin policy for published plugin POST routes. */
@Component
public class RestPublicationCorsSource {
    private static final String PREFIX = "/api/ext/";
    private final RestEndpointRegistry registry;
    private final RestPublicationStore publications;

    public RestPublicationCorsSource(RestEndpointRegistry registry, RestPublicationStore publications) {
        this.registry = registry;
        this.publications = publications;
    }

    public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        String method = "OPTIONS".equals(request.getMethod())
                ? request.getHeader("Access-Control-Request-Method") : request.getMethod();
        String path = request.getRequestURI();
        if (origin == null || !"POST".equals(method) || !path.startsWith(PREFIX)) return null;
        int separator = path.indexOf('/', PREFIX.length());
        if (separator < 0 || !path.startsWith("/public/", separator)) return null;

        String namespace = path.substring(PREFIX.length(), separator);
        var match = registry.match(namespace, method, path.substring(separator)).orElse(null);
        if (match == null || match.route().authPolicy() != AuthPolicy.PUBLIC) return null;
        var declared = match.extension().publicationCors(match.route());
        if (declared == null) return null;

        var cors = new CorsConfiguration();
        cors.setAllowedMethods(List.of("POST"));
        cors.setAllowedHeaders(List.of("Content-Type"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(0L);
        if (publications.allowsOrigin(namespace, declared.kind(), match.pathVars().get(declared.pathVariable()), origin)) {
            cors.setAllowedOrigins(List.of(origin));
        }
        return cors;
    }
}
