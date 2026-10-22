package com.auraboot.framework.notification.sourcebinding;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Hermetic test-configuration admission; no database or application startup. */
class RegisteredSourceBindingPollerIsolationTest {
    @Test
    void broadClasspathScanDoesNotActivateNativeIsolationWithoutExplicitSelection() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            assertThat(scanNativeConfiguration(context)).isZero();
            context.refresh();
            assertThat(context.getBeansOfType(RegisteredSourceBindingPostgresIT.PollerIsolation.class)).isEmpty();
        }
    }

    @Test
    void explicitSelectionScansIsolationAndRemovesOnlySchedulingProcessor() throws ClassNotFoundException {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "native-fixture-selection", Map.of("s06.native.source-binding.fixture", "true")));
            context.registerBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME,
                    ScheduledAnnotationBeanPostProcessor.class);
            context.registerBean("unrelatedFixtureBean", String.class, () -> "retained");
            // Reproduce the actual colliding configuration without starting its database test.
            context.register(Class.forName(
                    "com.auraboot.framework.notification.service.impl.PluginNotificationDeliveryPostgresIT$PollerIsolation"));
            assertThat(scanNativeConfiguration(context)).isEqualTo(1);
            context.refresh();
            assertThat(context.getBeansOfType(RegisteredSourceBindingPostgresIT.PollerIsolation.class)).hasSize(1);
            assertThat(context.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)).isFalse();
            assertThat(context.getBean("unrelatedFixtureBean")).isEqualTo("retained");
            assertThat(context.containsBean("preventTestAppAutomaticPollers")).isTrue();
            assertThat(context.containsBean("preventRegisteredSourceFixtureAutomaticPollers")).isTrue();
        }
    }

    @Test
    void evenExplicitImportCannotActivateIsolationInOrdinaryContext() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(RegisteredSourceBindingPostgresIT.PollerIsolation.class);
            context.registerBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME,
                    ScheduledAnnotationBeanPostProcessor.class);
            context.refresh();
            assertThat(context.getBeansOfType(RegisteredSourceBindingPostgresIT.PollerIsolation.class)).isEmpty();
            assertThat(context.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)).isTrue();
        }
    }

    private int scanNativeConfiguration(AnnotationConfigApplicationContext context) {
        ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(context, false);
        scanner.addIncludeFilter(new AssignableTypeFilter(RegisteredSourceBindingPostgresIT.PollerIsolation.class));
        return scanner.scan("com.auraboot.framework.notification.sourcebinding");
    }
}
