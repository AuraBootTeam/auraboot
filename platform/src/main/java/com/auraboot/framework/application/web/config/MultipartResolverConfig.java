package com.auraboot.framework.application.web.config;

import com.auraboot.framework.application.web.resolver.SafeStandardServletMultipartResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;

/**
 * Explicit multipart configuration that installs a safer resolver which
 * suppresses repeated parsing attempts after client aborts.
 */
@Configuration
public class MultipartResolverConfig {

    @Bean(name = "multipartResolver")
    public StandardServletMultipartResolver multipartResolver() {
        return new SafeStandardServletMultipartResolver();
    }
}
