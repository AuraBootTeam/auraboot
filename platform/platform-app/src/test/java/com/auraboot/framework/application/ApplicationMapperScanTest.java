package com.auraboot.framework.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.mapper.ClassPathMapperScanner;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class ApplicationMapperScanTest {

    @ParameterizedTest
    @ValueSource(classes = {MetaApplication.class, CoreOnlyApplication.class})
    void applicationDefinitionMapperIsRegisteredForEveryEntryPoint(Class<?> application) {
        var registry = new DefaultListableBeanFactory();
        var scanner = new ClassPathMapperScanner(registry);
        scanner.registerFilters();
        scanner.scan(application.getAnnotation(MapperScan.class).value());

        assertThat(registry.containsBeanDefinition("applicationDefinitionMapper"))
            .as("application release catalog dependency in %s", application.getSimpleName())
            .isTrue();
    }
}
