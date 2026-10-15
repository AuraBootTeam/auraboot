package com.auraboot.framework.plugin.pf4j;

import com.auraboot.module.meta.event.CommandCompletedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.BeanNotOfRequiredTypeException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ApplicationModuleEventForwarderTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void hostEventReachesEachContextOnceWithTheSameObjectAndId(int childCount) {
        try (var host = new AnnotationConfigApplicationContext()) {
            host.refresh();
            List<AnnotationConfigApplicationContext> children = children(host, childCount, false);
            try {
                var registry = mock(ApplicationModuleRegistry.class);
                when(registry.childContexts()).thenReturn(new ArrayList<ApplicationContext>(children));
                host.addApplicationListener(new ApplicationModuleEventForwarder(registry));
                List<CommandCompletedEvent> hostEvents = new ArrayList<>();
                host.addApplicationListener(listener(hostEvents::add));
                List<List<CommandCompletedEvent>> childEvents = new ArrayList<>();
                for (var child : children) {
                    List<CommandCompletedEvent> seen = new ArrayList<>();
                    childEvents.add(seen);
                    child.addApplicationListener(listener(seen::add));
                }
                CommandCompletedEvent event = event();

                host.publishEvent(event);

                assertThat(hostEvents).containsExactly(event);
                for (var seen : childEvents) {
                    assertThat(seen).containsExactly(event);
                    assertThat(seen.getFirst()).isSameAs(event);
                    assertThat(seen.getFirst().getEventId()).isEqualTo(event.getEventId());
                }
            } finally {
                children.forEach(AnnotationConfigApplicationContext::close);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void childTransactionalListenersWaitForCommitAndIgnoreRollback(int childCount) {
        try (var host = new AnnotationConfigApplicationContext()) {
            host.refresh();
            List<AnnotationConfigApplicationContext> children = children(host, childCount, true);
            try {
                var registry = mock(ApplicationModuleRegistry.class);
                when(registry.childContexts()).thenReturn(new ArrayList<ApplicationContext>(children));
                host.addApplicationListener(new ApplicationModuleEventForwarder(registry));
                var transaction = new TransactionTemplate(new LocalTransactionManager());
                CommandCompletedEvent committed = event();
                transaction.executeWithoutResult(status -> {
                    host.publishEvent(committed);
                    for (var child : children) assertThat(child.getBean(AfterCommitProbe.class).events).isEmpty();
                });
                for (var child : children) {
                    assertThat(child.getBean(AfterCommitProbe.class).events).containsExactly(committed);
                }
                transaction.executeWithoutResult(status -> {
                    host.publishEvent(event());
                    status.setRollbackOnly();
                });
                for (var child : children) {
                    assertThat(child.getBean(AfterCommitProbe.class).events).containsExactly(committed);
                }
            } finally {
                children.forEach(AnnotationConfigApplicationContext::close);
            }
        }
    }

    @Test
    void missingLocalMulticasterCannotFallBackToTheParent() {
        ApplicationContext child = mock(ApplicationContext.class);
        var registry = mock(ApplicationModuleRegistry.class);
        when(registry.childContexts()).thenReturn(List.of(child));
        assertThatThrownBy(() -> new ApplicationModuleEventForwarder(registry).onApplicationEvent(event()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("local event multicaster");
        verify(child, never()).publishEvent(any());
    }

    @Test
    void incompatibleMulticasterTypeFailsWithoutParentPublication() {
        ApplicationContext child = mock(ApplicationContext.class);
        String name = AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME;
        when(child.containsLocalBean(name)).thenReturn(true);
        when(child.getBean(name, ApplicationEventMulticaster.class))
                .thenThrow(new BeanNotOfRequiredTypeException(name, ApplicationEventMulticaster.class, String.class));
        var registry = mock(ApplicationModuleRegistry.class);
        when(registry.childContexts()).thenReturn(List.of(child));
        assertThatThrownBy(() -> new ApplicationModuleEventForwarder(registry).onApplicationEvent(event()))
                .isInstanceOf(BeanNotOfRequiredTypeException.class);
        verify(child, never()).publishEvent(any());
    }

    @Test
    void childListenerFailureIsNotSwallowed() {
        try (var host = new AnnotationConfigApplicationContext(); var child = new AnnotationConfigApplicationContext()) {
            host.refresh();
            child.setParent(host);
            child.refresh();
            var failure = new IllegalStateException("child rejected event");
            child.addApplicationListener(listener(event -> { throw failure; }));
            var registry = mock(ApplicationModuleRegistry.class);
            when(registry.childContexts()).thenReturn(List.of(child));
            assertThatThrownBy(() -> new ApplicationModuleEventForwarder(registry).onApplicationEvent(event()))
                    .isSameAs(failure);
        }
    }

    private List<AnnotationConfigApplicationContext> children(ApplicationContext host, int count, boolean transactional) {
        List<AnnotationConfigApplicationContext> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            var child = new AnnotationConfigApplicationContext();
            child.setParent(host);
            if (transactional) child.register(TransactionListeners.class);
            child.refresh();
            result.add(child);
        }
        return result;
    }

    private ApplicationListener<CommandCompletedEvent> listener(Consumer<CommandCompletedEvent> consumer) {
        return new ApplicationListener<>() {
            @Override public void onApplicationEvent(CommandCompletedEvent event) { consumer.accept(event); }
        };
    }

    private CommandCompletedEvent event() {
        return new CommandCompletedEvent(99L, "owned-record", "orders", Map.of(), "orders:complete", "UPDATE");
    }

    @Configuration(proxyBeanMethods = false)
    static class TransactionListeners {
        @Bean static TransactionalEventListenerFactory transactionalEventListenerFactory() {
            return new TransactionalEventListenerFactory();
        }
        @Bean AfterCommitProbe afterCommitProbe() { return new AfterCommitProbe(); }
    }

    static class AfterCommitProbe {
        final List<CommandCompletedEvent> events = new ArrayList<>();
        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void completed(CommandCompletedEvent event) { events.add(event); }
    }

    static class LocalTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
