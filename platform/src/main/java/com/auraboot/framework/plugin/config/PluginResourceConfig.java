package com.auraboot.framework.plugin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Configuration for serving plugin frontend resources.
 *
 * <p>Maps the /plugins/** URL pattern to serve static assets from the
 * frontend-plugins directory. This enables Module Federation remote
 * loading of plugin frontend bundles.
 *
 * <p>Resource Paths:
 * <ul>
 *   <li>/plugins/{namespace}/** - Plugin frontend assets</li>
 *   <li>/plugins/{namespace}/remoteEntry.js - Module Federation entry point</li>
 *   <li>/plugins/{namespace}/assets/** - Plugin static assets (JS, CSS, images)</li>
 * </ul>
 *
 * <p>Cache Strategy:
 * <ul>
 *   <li>remoteEntry.js - No cache (always fetch latest)</li>
 *   <li>Other assets - 1 day cache (hashed filenames for cache busting)</li>
 * </ul>
 *
 * @author AuraBoot Platform
 * @since 1.0.0
 */
@Slf4j
@Configuration
public class PluginResourceConfig implements WebMvcConfigurer {

    @Value("${aura.plugins.frontend.dir:frontend-plugins}")
    private String frontendPluginsDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path frontendPath = resolvePath(frontendPluginsDir);
        String resourceLocation = "file:" + frontendPath.toAbsolutePath() + "/";

        log.info("Configuring plugin resource handler: /plugins/** -> {}", resourceLocation);

        // Handler for plugin frontend assets
        // Note: Spring Boot 3 PathPatternParser doesn't support patterns after **
        // So we use a single handler with reasonable cache settings
        registry.addResourceHandler("/plugins/**")
                .addResourceLocations(resourceLocation)
                .setCacheControl(CacheControl.maxAge(1, TimeUnit.HOURS)
                        .cachePublic());

        log.info("Plugin resource handlers registered successfully");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Allow CORS for plugin resources (needed for Module Federation)
        registry.addMapping("/plugins/**")
                .allowedOriginPatterns("*")
                .allowedMethods("get", "head", "options")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }

    /**
     * Resolve path, handling both relative and absolute paths.
     */
    private Path resolvePath(String pathStr) {
        Path path = Paths.get(pathStr);
        if (!path.isAbsolute()) {
            path = Paths.get(System.getProperty("user.dir"), pathStr);
        }
        return path;
    }
}
