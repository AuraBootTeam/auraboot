package com.auraboot.framework.plugin.pf4j;

import com.auraboot.module.meta.event.CommandCompletedEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Forward selected host-context application events into every registered
 * plugin application module (child context).
 *
 * <p>Spring does not propagate events published in a parent context down to
 * child-context listeners. Plugin modules (AnnotationConfigApplicationContext
 * children registered by {@link ApplicationModuleRegistry}) therefore never
 * see host-published domain events: their {@code @TransactionalEventListener}
 * beans silently never fire (observed: record-level SLA activation never ran).
 * This forwarder closes that gap for the events plugins are documented to
 * consume.
 *
 * <p>Dispatch directly through each child's local multicaster. Using
 * {@code child.publishEvent} would propagate back to the host and invoke its
 * business listeners again. A per-thread identity guard also protects against
 * child listeners explicitly re-publishing the same event; independent
 * publisher threads retain independent rounds.
 *
 * <p>Forwarding happens synchronously at publish time — inside the host
 * transaction when one is active — so child-side
 * {@code @TransactionalEventListener(AFTER_COMMIT)} registrations attach to
 * the active transaction and commit semantics are preserved (no fallback
 * execution).
 */
@Component
public class ApplicationModuleEventForwarder implements ApplicationListener<CommandCompletedEvent> {

    private final ApplicationModuleRegistry moduleRegistry;
    private final ThreadLocal<Set<CommandCompletedEvent>> forwarded =
            ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));

    public ApplicationModuleEventForwarder(ApplicationModuleRegistry moduleRegistry) {
        this.moduleRegistry = moduleRegistry;
    }

    @Override
    public void onApplicationEvent(CommandCompletedEvent event) {
        Set<CommandCompletedEvent> currentRound = forwarded.get();
        if (!currentRound.add(event)) {
            return; // this round's child re-publication bubbled back up
        }
        try {
            for (ApplicationContext child : moduleRegistry.childContexts()) {
                String beanName = AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME;
                if (!child.containsLocalBean(beanName)) {
                    throw new IllegalStateException("Application module requires a local event multicaster: "
                            + child.getId());
                }
                child.getBean(beanName, ApplicationEventMulticaster.class).multicastEvent(event);
            }
        } finally {
            currentRound.remove(event);
            if (currentRound.isEmpty()) {
                forwarded.remove();
            }
        }
    }
}
