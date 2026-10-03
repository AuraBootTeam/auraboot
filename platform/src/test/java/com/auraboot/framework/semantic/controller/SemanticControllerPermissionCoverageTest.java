package com.auraboot.framework.semantic.controller;

import com.auraboot.framework.semantic.parser.SemanticYamlParser;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Hermetic contract check: every real controller mapping/media variant has an IT request. */
class SemanticControllerPermissionCoverageTest {
    @Test
    void coversEveryControllerMethodAndDeclaredMediaType() {
        var cases = SemanticControllerPermissionIT.endpoints().toList();
        Set<String> covered = cases.stream().map(e -> e.controller().getName() + "#" + e.handler()).collect(Collectors.toSet());
        Set<String> declared = List.of(SemanticController.class, SemanticUsageController.class,
                        SemanticMetricAlertController.class, SemanticPreaggController.class).stream()
                .flatMap(c -> Arrays.stream(c.getDeclaredMethods()))
                .filter(m -> verb(m) != null)
                .map(m -> m.getDeclaringClass().getName() + "#" + m.getName()).collect(Collectors.toSet());
        assertThat(covered).containsExactlyInAnyOrderElementsOf(declared).hasSize(18);
        assertThat(cases).hasSize(22);
        for (Class<?> controller : List.of(SemanticController.class, SemanticUsageController.class,
                SemanticMetricAlertController.class, SemanticPreaggController.class)) {
            String base = controller.getAnnotation(RequestMapping.class).value()[0];
            for (Method method : controller.getDeclaredMethods()) {
                if (verb(method) == null) continue;
                var requests = cases.stream().filter(e -> e.controller() == controller && e.handler().equals(method.getName())).toList();
                String path = base + suffix(method);
                String expectedPathPattern = path.replace("{pid}", "[^/]+");
                for (var request : requests) {
                    assertThat(request.method()).isEqualTo(verb(method));
                    assertThat(request.path().split("\\?", 2)[0]).matches(expectedPathPattern);
                }
                String[] media = consumes(method);
                if (media.length > 0) {
                    assertThat(requests.stream().map(SemanticControllerPermissionIT.Endpoint::mediaType).toList())
                            .containsExactlyInAnyOrder(media);
                }
            }
        }
    }

    @Test
    void denialYamlIsSchemaValidAndReferencesTheSeededModel() {
        var parsed = new SemanticYamlParser().parse(SemanticControllerPermissionIT.YAML);
        assertThat(parsed.getSemanticModel().getCode()).isEqualTo("permission_fixture");
        assertThat(parsed.getSemanticModel().getModelRef()).isEqualTo("ab_object_alias");
        assertThat(parsed.getMetrics()).extracting(m -> m.getCode()).containsExactly("count");
    }

    private static String verb(Method method) {
        if (method.isAnnotationPresent(GetMapping.class)) return "GET";
        if (method.isAnnotationPresent(PostMapping.class)) return "POST";
        if (method.isAnnotationPresent(PutMapping.class)) return "PUT";
        if (method.isAnnotationPresent(DeleteMapping.class)) return "DELETE";
        return null;
    }

    private static String suffix(Method method) {
        String[] paths;
        if (method.isAnnotationPresent(GetMapping.class)) paths = method.getAnnotation(GetMapping.class).value();
        else if (method.isAnnotationPresent(PostMapping.class)) paths = method.getAnnotation(PostMapping.class).value();
        else if (method.isAnnotationPresent(PutMapping.class)) paths = method.getAnnotation(PutMapping.class).value();
        else paths = method.getAnnotation(DeleteMapping.class).value();
        return paths.length == 0 ? "" : paths[0];
    }

    private static String[] consumes(Method method) {
        if (method.isAnnotationPresent(PostMapping.class)) return method.getAnnotation(PostMapping.class).consumes();
        return new String[0];
    }
}
