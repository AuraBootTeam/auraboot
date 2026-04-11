package com.auraboot.framework.application.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Adds standard security headers to all HTTP responses.
 * Defends against MIME sniffing, clickjacking, and XSS reflection.
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        // Prevent browsers from MIME-sniffing away from declared Content-Type
        response.setHeader("X-Content-Type-Options", "nosniff");

        // Block page from being embedded in iframe (clickjacking defense)
        response.setHeader("X-Frame-Options", "DENY");

        // Enable browser XSS filter (legacy but still useful for older browsers)
        response.setHeader("X-XSS-Protection", "1; mode=block");

        // Prevent referrer leakage
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");

        // Restrict browser features (camera, mic, geolocation) unless explicitly needed
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");

        filterChain.doFilter(request, response);
    }
}
