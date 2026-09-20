package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.security.AdminRoleInterceptor;
import com.auraboot.framework.authoring.workspace.AuthoringBusinessWriteInterceptor;
import com.auraboot.framework.environment.web.EnvironmentResolverInterceptor;
import com.auraboot.framework.permission.interceptor.PermissionInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Runtime request mapping owned by dynamically activated application modules. */
@Component
public final class PluginRequestMappingHandlerMapping extends RequestMappingHandlerMapping {

    private static final Logger log = LoggerFactory.getLogger(PluginRequestMappingHandlerMapping.class);

    private static final String[] API_PATHS = {"/api/**"};
    private static final String[] PUBLIC_API_PATHS = {
            "/api/auth/**", "/api/public/**", "/actuator/**"
    };

    private final Map<Object, List<RequestMappingInfo>> registrations = new IdentityHashMap<>();

    public PluginRequestMappingHandlerMapping(AdminRoleInterceptor adminRoleInterceptor,
                                              EnvironmentResolverInterceptor environmentResolverInterceptor,
                                              AuthoringBusinessWriteInterceptor authoringBusinessWriteInterceptor,
                                              PermissionInterceptor permissionInterceptor) {
        setOrder(-1);
        // This is a standalone HandlerMapping, so WebMvcConfigurer interceptors are not
        // inherited from the host RequestMappingHandlerMapping. Keep the same ordered
        // authorization chain here; otherwise dynamically registered product controllers
        // bypass @RequirePermission even though ordinary core controllers are protected.
        setInterceptors(
                new MappedInterceptor(new String[]{"/api/admin/**"}, adminRoleInterceptor),
                new MappedInterceptor(API_PATHS, PUBLIC_API_PATHS, environmentResolverInterceptor),
                new MappedInterceptor(API_PATHS, PUBLIC_API_PATHS, authoringBusinessWriteInterceptor),
                new MappedInterceptor(API_PATHS, PUBLIC_API_PATHS, permissionInterceptor)
        );
    }

    /**
     * Controllers owned by application-phase product modules. Such a module IS
     * the application for its product domain: its controllers win over host
     * duplicates instead of being shadowed by the host-wins policy (which
     * exists for facet re-declarations).
     */
    private final Set<Class<?>> applicationModuleControllers =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Register a controller owned by an application-phase product module.
     * Identical to {@link #registerController(Object)} except that a host
     * mapping on the same path does NOT shadow it (the product owns its
     * routes). Non-application facets keep the host-wins semantics.
     */
    public synchronized void registerApplicationModuleController(Object controller) {
        applicationModuleControllers.add(AopUtils.getTargetClass(controller));
        registerController(controller);
    }

    public synchronized void registerController(Object controller) {
        Class<?> controllerType = AopUtils.getTargetClass(controller);
        if (!AnnotatedElementUtils.hasAnnotation(controllerType, RestController.class)) {
            return;
        }
        List<RequestMappingInfo> mappings = new ArrayList<>();
        Method[] methods = controllerType.getMethods();
        for (Method method : methods) {
            RequestMappingInfo mapping = getMappingForMethod(method, controllerType);
            if (mapping == null) {
                continue;
            }
            // Composition policy: the host owns canonical routes. When a plugin
            // facet re-declares a path the host already maps (e.g. the BPM
            // application's standalone-only mobile endpoints under an enterprise
            // host), the plugin method is skipped with a warning instead of
            // failing the whole module registration. Plugin-vs-plugin duplicates
            // still fail closed through registerMapping's ambiguity check.
            if (isHostOwned(mapping) && !applicationModuleControllers.contains(controllerType)) {
                log.warn("Plugin controller {} duplicates host mapping(s) {} — host wins, skipping",
                        controllerType.getName(), mapping.getDirectPaths());
                continue;
            }
            registerMapping(mapping, controller, method);
            mappings.add(mapping);
        }
        registrations.put(controller, List.copyOf(mappings));
    }

    /** True when an identical direct path is already mapped by a non-plugin handler. */
    private boolean isHostOwned(RequestMappingInfo candidate) {
        for (RequestMappingInfo existing : getHandlerMethods().keySet()) {
            if (isPluginRegistered(existing)) {
                continue;
            }
            for (String path : candidate.getDirectPaths()) {
                if (existing.getDirectPaths().contains(path)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isPluginRegistered(RequestMappingInfo info) {
        for (List<RequestMappingInfo> owned : registrations.values()) {
            if (owned.contains(info)) {
                return true;
            }
        }
        return false;
    }

    public synchronized void unregisterController(Object controller) {
        List<RequestMappingInfo> mappings = registrations.remove(controller);
        for (RequestMappingInfo mapping : mappings == null ? List.<RequestMappingInfo>of() : mappings) {
            unregisterMapping(mapping);
        }
    }
}
