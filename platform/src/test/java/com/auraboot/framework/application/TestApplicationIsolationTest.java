package com.auraboot.framework.application;

import java.util.Set;
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
        var factory = new DefaultListableBeanFactory();
        var environment = new MockEnvironment();
        environment.setActiveProfiles("integration-test");
        var reader = new AnnotatedBeanDefinitionReader(factory, environment);
        reader.register(TestApplication.class, ExplicitFixture.class);
        var processor = new ConfigurationClassPostProcessor();
        processor.setEnvironment(environment);
        processor.postProcessBeanDefinitionRegistry(factory);
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
}
