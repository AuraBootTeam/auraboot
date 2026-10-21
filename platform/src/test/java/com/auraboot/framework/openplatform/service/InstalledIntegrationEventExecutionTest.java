package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.plugin.extension.integration.InstalledIntegrationBinding;
import com.auraboot.framework.plugin.extension.integration.InstalledIntegrationEvent;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InstalledIntegrationEventExecutionTest {
    private final InstalledIntegrationBinding binding=new InstalledIntegrationBinding(1,"app","install","kingdee-orders",
            "event-1","ext:install:event-1",1,Map.of());
    private final InstalledIntegrationEvent event=new InstalledIntegrationEvent("external.kingdee-orders.order.changed.v1",12,
            Instant.parse("2026-10-20T03:00:00Z"),"purchase-order","300001");
    @Test void configuredMetadataIsRemovedOutsideVerifiedExecution() {
        var settings=new HashMap<String,Object>();settings.put(InstalledIntegrationEvent.SETTINGS_KEY,event);
        InstalledIntegrationExecution.install("order:accept",1L,settings);
        assertFalse(settings.containsKey(InstalledIntegrationEvent.SETTINGS_KEY));
    }
    @Test void hostEnvelopeReplacesConfiguredMetadataAndDoesNotLeakAfterExecution() {
        var settings=new HashMap<String,Object>();settings.put(InstalledIntegrationEvent.SETTINGS_KEY,"untrusted");
        InstalledIntegrationExecution.run("order:accept","config",binding,false,new Object(),null,event,()->{
            InstalledIntegrationExecution.install("order:accept",1L,settings);
            assertSame(event,settings.get(InstalledIntegrationEvent.SETTINGS_KEY));
            assertSame(binding,settings.get(InstalledIntegrationBinding.SETTINGS_KEY));return null;
        });
        InstalledIntegrationExecution.install("order:accept",1L,settings);
        assertFalse(settings.containsKey(InstalledIntegrationEvent.SETTINGS_KEY));
    }
    @Test void legacyAndQueryExecutionHaveNoInventedEventFacts() {
        var settings=new HashMap<String,Object>();settings.put(InstalledIntegrationEvent.SETTINGS_KEY,event);
        InstalledIntegrationExecution.run("order:result","config",binding,true,new Object(),()->{
            InstalledIntegrationExecution.install("order:result",1L,settings);
            assertFalse(settings.containsKey(InstalledIntegrationEvent.SETTINGS_KEY));return null;
        });
    }
    @Test void eventIdentityRejectsInvalidVersionAndUnqualifiedType() {
        assertThrows(IllegalArgumentException.class,()->new InstalledIntegrationEvent("order.changed",12,Instant.EPOCH,"purchase-order","1"));
        assertThrows(IllegalArgumentException.class,()->new InstalledIntegrationEvent(event.eventType(),-1,Instant.EPOCH,"purchase-order","1"));
    }
}
