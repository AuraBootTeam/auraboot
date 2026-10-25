package com.auraboot.framework.application;

import java.util.Set;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.MergedAnnotations;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ContextConfiguration;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

import static org.assertj.core.api.Assertions.assertThat;

class TestApplicationIsolationTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class ExplicitFixture {
        @Bean String explicitlyImportedTestSentinel() { return "owned fixture"; }
    }

    @Test
    void productionDiscoveryKeepsOneBootstrapAndSchedulingWithoutForeignTestConfiguration() throws Exception {
        var factory = definitions(TestApplication.class, ExplicitFixture.class);
        var metadata = new CachingMetadataReaderFactory(new PathMatchingResourcePatternResolver());
        Set<String> bootstraps = new TreeSet<>();
        Set<String> testComponents = new TreeSet<>();
        for (String name : factory.getBeanDefinitionNames()) {
            String type = factory.getBeanDefinition(name).getBeanClassName();
            if (type == null) continue;
            var annotations = metadata.getMetadataReader(type).getAnnotationMetadata();
            if (annotations.hasAnnotation(SpringBootConfiguration.class.getName())
                    || annotations.hasMetaAnnotation(SpringBootConfiguration.class.getName())) bootstraps.add(type);
            if (annotations.hasAnnotation(TestComponent.class.getName())
                    || annotations.hasMetaAnnotation(TestComponent.class.getName())) testComponents.add(type);
        }
        assertThat(bootstraps).containsExactly(TestApplication.class.getName());
        assertThat(testComponents).containsExactly(ExplicitFixture.class.getName());
        assertThat(factory.containsBeanDefinition(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME))
                .as("production scheduled annotation discovery remains enabled").isTrue();
        assertThat(factory.containsBeanDefinition("explicitlyImportedTestSentinel"))
                .as("explicit per-test configuration remains available").isTrue();
    }

    private record RequiredFixture(Class<?> owner, Class<?> component, String field) {}

    @TestFactory
    Stream<DynamicTest> requiredNestedComponentsHaveOwnedRegistration() throws Exception {
        Path classes = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        assertThat(classes).isDirectory();
        var metadata = new CachingMetadataReaderFactory();
        List<RequiredFixture> fixtures = new ArrayList<>();
        try (var paths = Files.walk(classes)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                var reader = metadata.getMetadataReader(new FileSystemResource(file));
                var annotations = reader.getAnnotationMetadata();
                if (!reader.getClassMetadata().isIndependent()
                        || reader.getClassMetadata().getEnclosingClassName() == null
                        || !(annotations.hasAnnotation(Component.class.getName())
                        || annotations.hasMetaAnnotation(Component.class.getName()))) continue;
                Class<?> component = Class.forName(reader.getClassMetadata().getClassName(), false, getClass().getClassLoader());
                Class<?> owner = component.getEnclosingClass();
                if (AnnotatedElementUtils.hasAnnotation(component, TestConfiguration.class)
                        && AnnotatedElementUtils.findMergedAnnotation(owner, SpringBootTest.class) != null)
                    fixtures.add(new RequiredFixture(owner, component, "configuration:" + component.getSimpleName()));
                for (var field : owner.getDeclaredFields()) {
                    var dependency = field.getAnnotation(Autowired.class);
                    if (dependency != null && dependency.required() && field.getType().equals(component))
                        fixtures.add(new RequiredFixture(owner, component, field.getName()));
                }
            }
        }
        assertThat(fixtures).as("discover the real required nested component fixtures").isNotEmpty();
        return fixtures.stream().sorted(Comparator.comparing(f -> f.owner().getName() + ":" + f.field()))
                .map(f -> DynamicTest.dynamicTest(f.owner().getSimpleName() + "." + f.field(), () -> {
                    List<Class<?>> sources = new ArrayList<>();
                    sources.add(f.owner());
                    var boot = AnnotatedElementUtils.findMergedAnnotation(f.owner(), SpringBootTest.class);
                    if (boot != null) addOwnedSources(sources, f.owner(), boot.classes());
                    var context = AnnotatedElementUtils.findMergedAnnotation(f.owner(), ContextConfiguration.class);
                    if (context != null) addOwnedSources(sources, f.owner(), context.classes());
                    if (boot != null && boot.classes().length == 0
                            && (context == null || context.classes().length == 0))
                        Arrays.stream(f.owner().getDeclaredClasses())
                                .filter(type -> AnnotatedElementUtils.hasAnnotation(type, TestConfiguration.class))
                                .forEach(sources::add);
                    if (AnnotatedElementUtils.hasAnnotation(f.component(), TestConfiguration.class)) {
                        assertThat(declaresFixture(sources, f.component()))
                                .as("%s must declare its configuration; activation conditions remain independent", f.owner().getName())
                                .isTrue();
                        return;
                    }
                    var factory = definitions(sources.stream().distinct().toArray(Class<?>[]::new));
                    try {
                        assertThat(factory.getBeanNamesForType(f.component(), false, false))
                                .as("%s must declare its fixture without the shared application scan", f.owner().getName())
                                .isNotEmpty();
                    } finally {
                        factory.destroySingletons();
                    }
                }));
    }

    private static boolean declaresFixture(List<Class<?>> sources, Class<?> required) {
        var pending = new ArrayList<>(sources);
        Set<Class<?>> visited = new HashSet<>();
        for (int index = 0; index < pending.size(); index++) {
            Class<?> source = pending.get(index);
            if (!visited.add(source)) continue;
            if (source.equals(required)) return true;
            MergedAnnotations.from(source, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY)
                    .stream(Import.class).forEach(annotation -> pending.addAll(Arrays.asList(annotation.synthesize().value())));
        }
        return false;
    }

    private static void addOwnedSources(List<Class<?>> sources, Class<?> owner, Class<?>[] selected) {
        Arrays.stream(selected).filter(type -> !AnnotatedElementUtils.hasAnnotation(type, SpringBootConfiguration.class)
                || type.getNestHost().equals(owner.getNestHost())).forEach(sources::add);
    }

    private static DefaultListableBeanFactory definitions(Class<?>... sources) {
        var factory = new DefaultListableBeanFactory();
        var environment = new MockEnvironment();
        environment.setActiveProfiles("integration-test");
        new AnnotatedBeanDefinitionReader(factory, environment).register(sources);
        var processor = new ConfigurationClassPostProcessor();
        processor.setEnvironment(environment);
        processor.postProcessBeanDefinitionRegistry(factory);
        return factory;
    }

}
