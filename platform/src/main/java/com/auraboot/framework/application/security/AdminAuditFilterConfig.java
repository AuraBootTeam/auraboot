package com.auraboot.framework.application.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;

/**
 * Wraps {@code /api/admin/*} requests in {@link ContentCachingRequestWrapper}
 * so {@code AdminRoleInterceptor.afterCompletion} can re-read the request body
 * for {@link RequestBodySummarizer}. Without this filter, the body is consumed
 * by Spring's argument resolvers before the interceptor sees it.
 */
@Configuration
public class AdminAuditFilterConfig {

    @Bean
    public FilterRegistrationBean<AdminBodyCacheFilter> adminBodyCacheFilter() {
        FilterRegistrationBean<AdminBodyCacheFilter> reg =
                new FilterRegistrationBean<>(new AdminBodyCacheFilter());
        reg.addUrlPatterns("/api/admin/*");
        reg.setOrder(0);
        return reg;
    }

    static class AdminBodyCacheFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                        FilterChain chain) throws ServletException, IOException {
            ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(req);
            chain.doFilter(wrapped, resp);
        }
    }
}
