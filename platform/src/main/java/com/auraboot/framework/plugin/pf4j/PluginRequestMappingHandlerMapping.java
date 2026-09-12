package com.auraboot.framework.plugin.pf4j;

import org.springframework.stereotype.Component;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Runtime request mapping owned by dynamically activated application modules. */
@Component
public final class PluginRequestMappingHandlerMapping extends RequestMappingHandlerMapping {

    private final Map<Object, List<RequestMappingInfo>> registrations = new IdentityHashMap<>();

    public PluginRequestMappingHandlerMapping() {
        setOrder(-1);
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
            if (mapping != null) {
                registerMapping(mapping, controller, method);
                mappings.add(mapping);
            }
        }
        registrations.put(controller, List.copyOf(mappings));
    }

    public synchronized void unregisterController(Object controller) {
        List<RequestMappingInfo> mappings = registrations.remove(controller);
        for (RequestMappingInfo mapping : mappings == null ? List.<RequestMappingInfo>of() : mappings) {
            unregisterMapping(mapping);
        }
    }
}
