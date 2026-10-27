package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal;
import com.auraboot.framework.plugin.extension.integration.*;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OpenApiSynchronousIntegrationServiceTest {
    final AuraPluginManager plugins=mock(AuraPluginManager.class);
    final InstalledIntegrationRegistry registry=mock(InstalledIntegrationRegistry.class);
    final InstalledIntegrationService installed=mock(InstalledIntegrationService.class);
    final SynchronousIntegrationEndpointExtension extension=mock(SynchronousIntegrationEndpointExtension.class);
    final String source="sample-orders",event="external.sample-orders.created.v1",code="sample-order-create";
    final String digest="sha256:"+"a".repeat(64);
    final MachinePrincipal principal=new MachinePrincipal(42L,"token","machine",Set.of("orders.write"),"app","inst","test","token");
    final Map<String,Object> input=Map.of("orderId","10"),output=Map.of("requestId","request-123","status","completed");
    final Map<String,Object> schema=Map.of("type","object","required",List.of("orderId"),"additionalProperties",false,
            "properties",Map.of("orderId",Map.of("type","string","pattern","^[1-9][0-9]*$")));
    final OpenApiSynchronousIntegrationService service=new OpenApiSynchronousIntegrationService(plugins,registry,installed,
            new OpenApiProtocolHash(new ObjectMapper(),new OpenPlatformSecretCodec()),new ObjectMapper());
    @BeforeEach void setup() {
        when(plugins.getExtensionsOfType(SynchronousIntegrationEndpointExtension.class)).thenReturn(List.of(extension));
        when(plugins.observeLoadedArtifact(extension.getClass())).thenReturn(Optional.of(new AuraPluginManager.LoadedPluginArtifact("provider",Path.of("plugin.jar"),digest)));
        when(registry.registration(source)).thenReturn(Optional.of(Map.entry(new ExternalEventIngressExtension.Registration(source,"orders.ingress",Set.of(event),
                "orders.write","orders.ingress","provider","owner","order_notice:accept","order_notice:process","order_notice:result"),digest)));
        when(extension.definitions()).thenReturn(List.of(new SynchronousIntegrationEndpointExtension.Definition(code,"Create order",source,event,1,30000,schema,
                Map.of("type","object","required",List.of("requestId","status"),"properties",Map.of("status",Map.of("const","completed"),"requestId",Map.of("type","string"))))));
        when(extension.prepare(code,input)).thenReturn(new SynchronousIntegrationEndpointExtension.Request("purchase-order","10",Instant.EPOCH,0,input));
        when(installed.executeSynchronouslyWithReceipt(eq(principal),eq(source),any())).thenReturn(new InstalledIntegrationService.SynchronousResult(Map.of("state","completed"),false));
        when(extension.project(eq(code),eq("request-123"),anyMap())).thenReturn(output);
    }
    @Test void publishedInvocationBuildsOnlyHostIdentityAndReturnsTheBusinessResult() {
        var response=service.execute(principal,code,"published-create","request-123",input);
        assertEquals(200,response.status());assertEquals(output,response.body());assertFalse(response.replay());
        var capture=ArgumentCaptor.forClass(IntegrationEventEnvelope.class);
        verify(installed).executeSynchronouslyWithReceipt(eq(principal),eq(source),capture.capture());
        var envelope=capture.getValue();assertEquals(42,envelope.tenantId());assertEquals("inst",envelope.headers().get("installationPid"));
        assertEquals("purchase-order/10",envelope.subject());assertEquals(input,envelope.payload());assertEquals(Instant.EPOCH,envelope.occurredAt());
        assertNotEquals("request-123",envelope.headers().get("externalEventId"));
    }
    @Test void retriesKeepTheEnvelopeStableWhileAnotherPublicationHasADifferentRequestIdentity() {
        service.execute(principal,code,"first","request-123",input);service.execute(principal,code,"first","request-123",input);
        service.execute(principal,code,"second","request-123",input);
        var capture=ArgumentCaptor.forClass(IntegrationEventEnvelope.class);verify(installed,times(3)).executeSynchronouslyWithReceipt(eq(principal),eq(source),capture.capture());
        var events=capture.getAllValues();assertEquals(events.get(0),events.get(1));assertNotEquals(events.get(0).eventId(),events.get(2).eventId());
    }
    @Test void hostReplayFlagIsPreserved() {
        when(installed.executeSynchronouslyWithReceipt(eq(principal),eq(source),any())).thenReturn(new InstalledIntegrationService.SynchronousResult(Map.of("state","completed"),true));
        assertTrue(service.execute(principal,code,"publication","request-123",input).replay());
    }
    @Test void malformedInputsAndKeysNeverReachTheBusinessBoundary() {
        for(var bad:List.<Map<String,Object>>of(Map.of(),Map.of("orderId",10),Map.of("orderId","10","tenantId",2)))
            assertThrows(IllegalArgumentException.class,()->service.execute(principal,code,"publication","request-123",bad));
        assertThrows(IllegalArgumentException.class,()->service.execute(principal,code,"publication","short",input));verifyNoInteractions(installed);
    }
    @Test void foreignArtifactAndDuplicateDefinitionsAreRejected() {
        when(plugins.observeLoadedArtifact(extension.getClass())).thenReturn(Optional.of(new AuraPluginManager.LoadedPluginArtifact("foreign",Path.of("plugin.jar"),digest)));
        assertThrows(SecurityException.class,()->service.execute(principal,code,"publication","request-123",input));verifyNoInteractions(installed);
    }
    @Test void earlyAcceptanceCannotBeReturnedAsASynchronousResult() {
        when(extension.project(eq(code),eq("request-123"),anyMap())).thenReturn(Map.of("requestId","request-123","status","accepted"));
        assertThrows(IllegalArgumentException.class,()->service.execute(principal,code,"publication","request-123",input));
    }
}
