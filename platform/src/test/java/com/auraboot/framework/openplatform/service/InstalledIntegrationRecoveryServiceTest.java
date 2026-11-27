package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.openplatform.mapper.*;
import com.auraboot.framework.openplatform.mapper.InstalledIntegrationRecoveryMapper.*;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.auraboot.framework.openplatform.service.InstalledIntegrationRecoveryContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class InstalledIntegrationRecoveryServiceTest {
    InstalledIntegrationServiceTest f;
    InstalledIntegrationRecoveryMapper mapper;
    InstalledIntegrationRecoveryService service;
    AtomicReference<InstalledIntegrationReceiptMapper.Receipt> current;
    AtomicReference<Audit> audit;
    Event stored;
    @BeforeEach void setup() throws Exception {
        f=new InstalledIntegrationServiceTest();f.setup();
        f.service.accept(f.principal,f.source,f.event());
        current=new AtomicReference<>(f.acceptedReceipt());audit=new AtomicReference<>();
        when(f.receipts.find(42L,"inst",f.source,"evt-1")).thenAnswer(call->current.get());
        f.upgradeProvider();
        var config=(com.fasterxml.jackson.databind.node.ObjectNode)f.json.readTree(f.metadata.get(f.process).getExecutionConfig());
        var product=((com.fasterxml.jackson.databind.node.ObjectNode)config.get("handlerParams")).putObject("installedMachineData").putObject("prod_product");
        product.putArray("read").add("pid").add("prod_code").add("prod_name").add("prod_type").add("prod_unit").add("prod_status");
        product.putArray("create").add("prod_code").add("prod_name").add("prod_type").add("prod_unit").add("prod_status");
        f.metadata.get(f.process).setExecutionConfig(config.toString());
        mapper=mock(InstalledIntegrationRecoveryMapper.class);
        service=new InstalledIntegrationRecoveryService(f.service,f.receipts,mapper,f.json);
        stored=event("failed",true,2,f.json.writeValueAsString(f.event().payload()));
        when(mapper.event(42L,f.event().eventId())).thenAnswer(call->stored);
        when(mapper.lockEvent(42L,f.event().eventId())).thenAnswer(call->stored);
        when(mapper.audit(eq(42L),eq("inst"),eq(f.source),eq("evt-1"),anyString(),anyString(),anyInt()))
                .thenAnswer(call->audit.get());
        when(mapper.insert(any())).thenAnswer(call->{
            InstalledIntegrationRecoveryMapper.Audit a=call.getArgument(0);
            // Force a storage round-trip precision change even on microsecond-clock hosts.
            audit.set(new InstalledIntegrationRecoveryMapper.Audit(a.pid(),a.tenantId(),a.installationPid(),a.sourceCode(),
                    a.externalEventId(),a.durableEventId(),a.requestPid(),a.envelopeSha256(),a.bindingSha256(),
                    a.previousCommandSha256(),a.previousProviderSha256(),a.commandSha256(),a.providerSha256(),
                    a.replayCount(),a.requestedBy(),a.reason(),a.requestedAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)));
            return 1;
        });
        when(f.receipts.recover(eq(42L),eq("inst"),eq(f.source),eq("evt-1"),any())).thenAnswer(call->{
            Decision d=call.getArgument(4);var r=current.get();current.set(new InstalledIntegrationReceiptMapper.Receipt(
                    r.tenantId(),r.installationPid(),r.sourceCode(),r.externalEventId(),r.durableEventId(),r.envelopeSha256(),
                    r.bindingSha256(),d.commandSha256(),d.providerSha256(),r.requestPid(),r.state()));return 1;
        });
        clearInvocations(f.executor);
    }
    @AfterEach void clear(){f.clear();}
    Event event(String state,boolean open,int count,String payload) throws Exception {
        var e=f.event();return new Event(42,e.eventId(),e.eventType(),e.source(),e.subject(),e.occurredAt(),
                e.correlationId(),e.causationId(),e.orderingKey(),e.sequence(),e.schemaVersion(),payload,
                f.json.writeValueAsString(e.headers()),state,count,open);
    }
    Decision decision(){var p=service.plan(42,"inst",f.source,"evt-1");return decision(p,"Reviewed fixed provider and exact capability addition");}
    Decision decision(Plan p,String reason){return new Decision(p.requestPid(),p.envelopeSha256(),p.bindingSha256(),
            p.previousCommandSha256(),p.previousProviderSha256(),p.commandSha256(),p.providerSha256(),p.expectedReplayCount(),reason);}
    Result authorize(Decision d){return service.authorize(42,"inst",f.source,"evt-1","operator",d);}
    void noWrite(){verify(f.receipts,never()).recover(anyLong(),anyString(),anyString(),anyString(),any());verify(mapper,never()).insert(any());verifyNoInteractions(f.executor);}

    @Test void ordinaryDeliveryRemainsDeniedUntilExplicitExactAuthorization() {
        assertThrows(SecurityException.class,()->f.service.consume(f.event()));
        assertThrows(SecurityException.class,()->f.service.result(f.principal,f.source,"evt-1"));
        var old=current.get();var d=decision();var result=authorize(d);
        assertEquals("authorized",result.status());assertEquals(2,result.replayCount());assertEquals("operator",result.requestedBy());
        assertEquals(old.envelopeSha256(),current.get().envelopeSha256());assertEquals(old.bindingSha256(),current.get().bindingSha256());
        assertEquals(old.requestPid(),current.get().requestPid());assertEquals("accepted",current.get().state());
        assertEquals(old.commandSha256(),audit.get().previousCommandSha256());assertEquals(d.commandSha256(),audit.get().commandSha256());
        verifyNoInteractions(f.executor);
        when(f.executor.execute(eq(f.process),any())).thenReturn(CommandExecuteResult.builder().phaseReached("completed").data(Map.of("state","completed")).build());
        f.service.consume(f.event());verify(f.executor,times(1)).execute(eq(f.process),any());
    }
    @Test void exactLostResponseRetryReturnsOriginalAuditEvenAfterDelivery() throws Exception {
        var d=decision();var first=authorize(d);var r=current.get();
        current.set(new InstalledIntegrationReceiptMapper.Receipt(r.tenantId(),r.installationPid(),r.sourceCode(),r.externalEventId(),
                r.durableEventId(),r.envelopeSha256(),r.bindingSha256(),r.commandSha256(),r.providerSha256(),r.requestPid(),"completed"));
        stored=event("delivered",false,3,stored.payload());
        assertEquals(first,authorize(d));verify(mapper,times(1)).insert(any());verify(f.receipts,times(1)).recover(anyLong(),anyString(),anyString(),anyString(),any());
    }
    @Test void alteredReasonOrActorCannotReuseTheSameDecision() {
        var d=decision();authorize(d);
        var changed=new Decision(d.requestPid(),d.envelopeSha256(),d.bindingSha256(),d.previousCommandSha256(),d.previousProviderSha256(),
                d.commandSha256(),d.providerSha256(),d.expectedReplayCount(),"different reason");
        assertThrows(IllegalStateException.class,()->authorize(changed));
        assertThrows(IllegalStateException.class,()->service.authorize(42,"inst",f.source,"evt-1","other-operator",d));
        verify(mapper,times(1)).insert(any());
    }
    @Test void tamperedPayloadAndChangedInstallationBindingAreNeverAdopted() throws Exception {
        var first=decision();stored=event("failed",true,2,"{\"title\":\"forged\"}");
        assertThrows(SecurityException.class,()->authorize(first));noWrite();
        setup();var d=decision();
        for(var command:f.metadata.values()) {
            var tree=(com.fasterxml.jackson.databind.node.ObjectNode)f.json.readTree(command.getExecutionConfig());
            ((com.fasterxml.jackson.databind.node.ObjectNode)tree.at("/handlerParams/installedIntegration/bindings/0/parameters")).put("connectionPid","foreign");
            command.setExecutionConfig(tree.toString());
        }
        var captured=d;assertThrows(IllegalStateException.class,()->authorize(captured));noWrite();
    }
    @Test void staleDeliveryVersionOrChangedFingerprintsAreRejected() throws Exception {
        var d=decision();stored=event("failed",true,3,stored.payload());
        assertThrows(IllegalStateException.class,()->authorize(d));noWrite();
        setup();var p=service.plan(42,"inst",f.source,"evt-1");
        var stale=new Decision(p.requestPid(),p.envelopeSha256(),p.bindingSha256(),"c".repeat(64),p.previousProviderSha256(),
                p.commandSha256(),p.providerSha256(),2,"reviewed");
        assertThrows(IllegalStateException.class,()->authorize(stale));noWrite();
    }
    @Test void activeQuarantinedOrCompletedIntentsCannotReceiveNewAuthorization() throws Exception {
        for(String state:List.of("pending","processing","delivered")) {
            setup();var d=decision();stored=event(state,true,2,stored.payload());assertThrows(IllegalStateException.class,()->authorize(d));noWrite();
        }
        setup();var d=decision();stored=event("failed",false,2,stored.payload());assertThrows(IllegalStateException.class,()->authorize(d));noWrite();
    }
    @Test void foreignOrMissingReceiptIsIndistinguishableAndCannotWrite() {
        assertThrows(NoSuchElementException.class,()->service.plan(43,"inst",f.source,"evt-1"));
        var r=current.get();current.set(new InstalledIntegrationReceiptMapper.Receipt(43,r.installationPid(),r.sourceCode(),r.externalEventId(),
                r.durableEventId(),r.envelopeSha256(),r.bindingSha256(),r.commandSha256(),r.providerSha256(),r.requestPid(),r.state()));
        assertThrows(NoSuchElementException.class,()->service.plan(42,"inst",f.source,"evt-1"));noWrite();
    }
    @Test void revokedGrantAndMachineOperatorRemainDenied() {
        var d=decision();when(f.scopes.findScopes(42L,9L)).thenReturn(Set.of());
        assertThrows(SecurityException.class,()->authorize(d));noWrite();
        assertThrows(SecurityException.class,()->service.authorize(42,"inst",f.source,"evt-1","installation:inst",d));
    }
    @Test void compareAndSwapFailureAndAuditFailureDoNotReturnSuccess() throws Exception {
        var d=decision();when(f.receipts.recover(anyLong(),anyString(),anyString(),anyString(),any())).thenReturn(0);
        assertThrows(IllegalStateException.class,()->authorize(d));verify(mapper,never()).insert(any());
        setup();var next=decision();doThrow(new IllegalStateException("audit unavailable")).when(mapper).insert(any());
        assertThrows(IllegalStateException.class,()->authorize(next));
    }
}
