package com.auraboot.framework.plugin.pf4j;

import com.auraboot.module.meta.event.CommandCompletedEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
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
 * <p>Re-publishing in a child propagates the event back UP to the host
 * (Spring parent chain), which would loop. Each forwarded event instance is
 * tracked so the host-side listener ignores its own re-publications.
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
    private final Set<CommandCompletedEvent> forwarded =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    public ApplicationModuleEventForwarder(ApplicationModuleRegistry moduleRegistry) {
        this.moduleRegistry = moduleRegistry;
    }

    @Override
    public void onApplicationEvent(CommandCompletedEvent event) {
        org.slf4j.LoggerFactory.getLogger(getClass())
                .info("[SLA-PROBE] forwarder got event: command={}, record={}, forwardedSize={}",
                        event.getCommandCode(), event.getRecordId(), forwarded.size());
        if (forwarded.remove(event)) {
            return; // our own child re-publication bubbled back up — stop the cycle
        }
        forwarded.add(event);
        int i = 0;
        for (ApplicationContext child : moduleRegistry.childContexts()) {
            child.publishEvent(event);
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .info("[SLA-PROBE] forwarded to child #{}: {}", i++, event.getCommandCode());
        }
        if (i == 0) {
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .info("[SLA-PROBE] no child contexts registered");
        }
    }
}
