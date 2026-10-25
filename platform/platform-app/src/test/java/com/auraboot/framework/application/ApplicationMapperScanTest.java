package com.auraboot.framework.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.mapper.ClassPathMapperScanner;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import java.util.stream.Collectors;

class ApplicationMapperScanTest {

    @ParameterizedTest
    @ValueSource(classes = {MetaApplication.class, CoreOnlyApplication.class, TestApplication.class})
    void applicationDefinitionMapperIsRegisteredForEveryEntryPoint(Class<?> application) {
        var registry = new DefaultListableBeanFactory();
        var scanner = new ClassPathMapperScanner(registry);
        scanner.registerFilters();
        scanner.scan(application.getAnnotation(MapperScan.class).value());

        assertThat(registry.containsBeanDefinition("applicationDefinitionMapper"))
            .as("application release catalog dependency in %s", application.getSimpleName())
            .isTrue();
        assertThat(registry.containsBeanDefinition("wechatSubscriptionConsentMapper"))
            .as("verified subscription receiver mapper in %s", application.getSimpleName())
            .isTrue();
    }
    @Test
    void sharedTestScanRetainsProductionComponentsAndExcludesUnrelatedTestConfigurations() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(true);
        var constructor = Class.forName("org.springframework.boot.test.context.filter.TestTypeExcludeFilter")
            .getDeclaredConstructor();
        constructor.setAccessible(true);
        var beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("actualTestTypeExcludeFilter", constructor.newInstance());
        for (var declaration : TestApplication.class.getAnnotation(ComponentScan.class).excludeFilters()) {
            for (var type : declaration.classes()) {
                if (declaration.type() == FilterType.CUSTOM) {
                    var filter = (TypeFilter) type.getConstructor().newInstance();
                    if (filter instanceof org.springframework.beans.factory.BeanFactoryAware aware) {
                        aware.setBeanFactory(beanFactory);
                    }
                    scanner.addExcludeFilter(filter);
                } else {
                    assertThat(declaration.type()).isEqualTo(FilterType.ANNOTATION);
                    scanner.addExcludeFilter(new AnnotationTypeFilter(type.asSubclass(java.lang.annotation.Annotation.class)));
                }
            }
        }
        var candidates = scanner.findCandidateComponents("com.auraboot.framework").stream()
            .map(definition -> definition.getBeanClassName()).collect(Collectors.toSet());
        assertThat(candidates).contains(
            "com.auraboot.framework.integration.ReliableEventConsumerRegistry",
            "com.auraboot.framework.meta.service.impl.OutboxWorkerImpl",
            "com.auraboot.framework.scheduler.service.impl.DatabaseSchedulerEngine"
        ).doesNotContain(
            "com.auraboot.framework.integration.ReliableIntegrationRuntimeIT$PilotConfiguration",
            "com.auraboot.framework.plugin.service.impl.ModelFieldRenameImportIT$NoBackgroundWork",
            "com.auraboot.framework.plugin.pf4j.ProcurementOrderChangeNativeDbIT$NoScheduling",
            "com.auraboot.framework.plugin.pf4j.ProcurementPublicSourceNativeFixture$IsolatedScheduling",
            "com.auraboot.framework.application.CoreOnlyApplication",
            "com.auraboot.framework.application.MetaApplication"
        );
    }
}
