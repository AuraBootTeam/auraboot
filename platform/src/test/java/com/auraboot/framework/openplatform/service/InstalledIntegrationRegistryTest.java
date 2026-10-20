package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.plugin.extension.integration.ExternalEventIngressExtension;
import com.auraboot.framework.plugin.extension.integration.ExternalEventIngressExtension.Registration;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InstalledIntegrationRegistryTest {
    final Registration registration=new Registration("kingdee-procurement","procurement.inquiry.ingress",
        Set.of("external.kingdee-procurement.inquiry.requested.v1"),"procurement.inquiries.write","qo.procurement.ingress",
        "com.auraboot.quote-engine","com.auraboot.quote-procurement","qo_request:accept","qo_request:process","qo_request:result","ownerUserPid");
    final ExternalEventIngressExtension extension=()->registration;
    final AuraPluginManager plugins=mock(AuraPluginManager.class);
    InstalledIntegrationRegistry registry() {
        when(plugins.getExtensionsOfType(ExternalEventIngressExtension.class)).thenReturn(List.of(extension));
        when(plugins.observeLoadedArtifact(extension.getClass())).thenReturn(Optional.of(
            new AuraPluginManager.LoadedPluginArtifact("com.auraboot.quote-engine",Path.of("owner.jar"),"sha256:"+"a".repeat(64))));
        return new InstalledIntegrationRegistry(plugins);
    }
    @Test void exactRegisteredRoutesUseBusinessScopeAndExposeItToOfficialScopeGrantCatalog() {
        var registry=registry();var capabilities=new OpenApiCapabilityRegistry(List.of());
        ReflectionTestUtils.setField(capabilities,"installedIntegrations",registry);
        assertEquals("procurement.inquiries.write",capabilities.resolve("POST","/api/open/v1/event-sources/kingdee-procurement/events").orElseThrow().requiredScope());
        assertEquals("procurement.inquiries.write",capabilities.resolve("GET","/api/open/v1/event-sources/kingdee-procurement/events/event-1").orElseThrow().requiredScope());
        assertEquals("automation.events.write",capabilities.resolve("POST","/api/open/v1/event-sources/other/events").orElseThrow().requiredScope());
        assertTrue(capabilities.resolve("GET","/api/open/v1/event-sources/other/events/event-1").isEmpty());
        assertEquals(2,capabilities.list().stream().filter(c->c.requiredScope().equals("procurement.inquiries.write")).count());
    }
    @Test void wrongOrStoppedProviderAndDuplicateSourceAreRejected() {
        var registry=registry();when(plugins.observeLoadedArtifact(extension.getClass())).thenReturn(Optional.empty());
        assertThrows(SecurityException.class,()->registry.registration("kingdee-procurement"));
        when(plugins.observeLoadedArtifact(extension.getClass())).thenReturn(Optional.of(
            new AuraPluginManager.LoadedPluginArtifact("foreign-owner",Path.of("owner.jar"),"sha256:"+"a".repeat(64))));
        assertThrows(SecurityException.class,()->registry.registration("kingdee-procurement"));
        final var duplicateRegistry=registry();
        when(plugins.getExtensionsOfType(ExternalEventIngressExtension.class)).thenReturn(List.of(extension,extension));
        assertThrows(SecurityException.class,()->duplicateRegistry.registration("kingdee-procurement"));
    }
    @Test void historicalEventSetsCannotChangeAuthorityOrInventUnregisteredEvents() {
        var expanded=new Registration(registration.sourceCode(),registration.capability(),
                Set.of("external.kingdee-procurement.inquiry.requested.v1","external.kingdee-procurement.inquiry.changed.v1"),
                registration.requiredScope(),registration.commandPermission(),registration.providerPluginId(),registration.commandOwnerPluginId(),
                registration.acceptCommand(),registration.processCommand(),registration.resultCommand(),registration.serverActorParameter());
        var provider=mock(ExternalEventIngressExtension.class);when(provider.registration()).thenReturn(expanded);
        when(provider.completedReceiptEventTypeSets()).thenReturn(Set.of(registration.eventTypes()));
        when(plugins.getExtensionsOfType(ExternalEventIngressExtension.class)).thenReturn(List.of(provider));
        when(plugins.observeLoadedArtifact(provider.getClass())).thenReturn(Optional.of(
                new AuraPluginManager.LoadedPluginArtifact(registration.providerPluginId(),Path.of("provider.jar"),"sha256:"+"a".repeat(64))));
        var registry=new InstalledIntegrationRegistry(plugins);
        assertEquals(Set.of(registration),registry.completedReceiptRegistrations(registration.sourceCode()));
        when(provider.completedReceiptEventTypeSets()).thenReturn(Set.of(Set.of("external.kingdee-procurement.forged.v1")));
        assertThrows(SecurityException.class,()->registry.completedReceiptRegistrations(registration.sourceCode()));
        when(provider.completedReceiptEventTypeSets()).thenReturn(Set.of(expanded.eventTypes()));
        assertThrows(SecurityException.class,()->registry.completedReceiptRegistrations(registration.sourceCode()));
    }
    @Test void registrationRejectsUnrelatedEventFamilyOrArbitraryCommand() {
        assertThrows(IllegalArgumentException.class,()->new Registration("kingdee-procurement","procurement.inquiry.ingress",
            Set.of("external.other.inquiry.requested.v1"),"scope","permission","provider","owner","qo_request:accept","qo_request:process","qo_request:result"));
        assertThrows(IllegalArgumentException.class,()->new Registration("kingdee-procurement","procurement.inquiry.ingress",
            registration.eventTypes(),"scope","permission","provider","owner","arbitrary command","qo_request:process","qo_request:result"));
    }
}
