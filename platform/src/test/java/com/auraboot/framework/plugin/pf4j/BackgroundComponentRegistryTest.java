package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.plugin.extension.BackgroundComponentExtension;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the full Spring lifecycle treatment of plugin-contributed
 * background components: @Autowired wires from the host context,
 * @PostConstruct fires on register, @PreDestroy fires on unregister.
 */
class BackgroundComponentRegistryTest {

    private AnnotationConfigApplicationContext ctx;
    private AuraPluginManager pluginManager;
    private BackgroundComponentRegistry registry;

    @BeforeEach
    void setUp() {
        ctx = new AnnotationConfigApplicationContext();
        ctx.register(HostBean.class);
        ctx.refresh();
        pluginManager = mock(AuraPluginManager.class);
        registry = new BackgroundComponentRegistry(ctx, pluginManager);
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    @Test
    void register_runsPostConstruct_autowiresFromHost_andTracksBeanName() {
        TestExtension ext = new TestExtension();
        when(pluginManager.getExtensionsOfType(BackgroundComponentExtension.class, "p1"))
                .thenReturn(List.of(ext));

        registry.register("p1");

        assertThat(ext.postConstructCount).isEqualTo(1);
        assertThat(ext.hostBean).isNotNull();
        assertThat(ext.hostBean.greeting).isEqualTo("hello");
        assertThat(registry.registeredBeanNames("p1")).containsExactly("testExtension");
        assertThat(ctx.getBean("testExtension")).isSameAs(ext);
    }

    @Test
    void unregister_runsPreDestroy_andRemovesFromBeanFactory() {
        TestExtension ext = new TestExtension();
        when(pluginManager.getExtensionsOfType(BackgroundComponentExtension.class, "p1"))
                .thenReturn(List.of(ext));

        registry.register("p1");
        registry.unregister("p1");

        assertThat(ext.preDestroyCount).isEqualTo(1);
        assertThat(registry.registeredBeanNames("p1")).isEmpty();
        assertThat(ctx.containsBean("testExtension")).isFalse();
    }

    @Test
    void register_isIdempotent_withinSamePlugin() {
        TestExtension ext = new TestExtension();
        when(pluginManager.getExtensionsOfType(BackgroundComponentExtension.class, "p1"))
                .thenReturn(List.of(ext));

        registry.register("p1");
        registry.register("p1");

        // Second call must not re-run PostConstruct nor add duplicate bean entry.
        assertThat(ext.postConstructCount).isEqualTo(1);
        assertThat(registry.registeredBeanNames("p1")).containsExactly("testExtension");
    }

    @Test
    void register_honorsExplicitBeanName() {
        NamedExtension ext = new NamedExtension();
        when(pluginManager.getExtensionsOfType(BackgroundComponentExtension.class, "p1"))
                .thenReturn(List.of(ext));

        registry.register("p1");

        assertThat(registry.registeredBeanNames("p1")).containsExactly("custom.alias");
        assertThat(ctx.getBean("custom.alias")).isSameAs(ext);
    }

    @Test
    void register_rollsBackOnFailure() {
        TestExtension good = new TestExtension();
        FailingExtension bad = new FailingExtension();
        when(pluginManager.getExtensionsOfType(BackgroundComponentExtension.class, "p1"))
                .thenReturn(List.of(good, bad));

        assertThatThrownBy(() -> registry.register("p1"))
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("boom");

        // The good extension was registered then rolled back: its PreDestroy
        // ran and the bean is no longer in the context.
        assertThat(good.preDestroyCount).isEqualTo(1);
        assertThat(ctx.containsBean("testExtension")).isFalse();
        assertThat(registry.registeredBeanNames("p1")).isEmpty();
    }

    @Test
    void unregister_isNoOpForUnknownPlugin() {
        registry.unregister("never-registered");
        assertThat(registry.registeredBeanNames("never-registered")).isEmpty();
    }

    // ---- test fixtures ----

    @Component
    static class HostBean {
        final String greeting = "hello";
    }

    static class TestExtension implements BackgroundComponentExtension {
        @Autowired HostBean hostBean;
        int postConstructCount = 0;
        int preDestroyCount = 0;

        @PostConstruct
        void start() {
            postConstructCount++;
        }

        @PreDestroy
        void stop() {
            preDestroyCount++;
        }
    }

    static class NamedExtension implements BackgroundComponentExtension {
        @Override
        public String beanName() {
            return "custom.alias";
        }
    }

    static class FailingExtension implements BackgroundComponentExtension {
        @PostConstruct
        void boom() {
            throw new IllegalStateException("boom");
        }
    }
}
