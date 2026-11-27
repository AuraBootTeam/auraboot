package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.openplatform.mapper.InstalledIntegrationReceiptMapper;
import com.auraboot.framework.openplatform.mapper.InstalledIntegrationRecoveryMapper;
import com.auraboot.framework.openplatform.mapper.InstalledIntegrationRecoveryMapper.Audit;
import com.auraboot.framework.openplatform.mapper.InstalledIntegrationRecoveryMapper.Event;
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import static com.auraboot.framework.openplatform.service.InstalledIntegrationRecoveryContracts.*;

/** Authorizes one exact immutable failed intent; ordinary delivery remains strictly fingerprint-bound. */
@Service
@RequiredArgsConstructor
public class InstalledIntegrationRecoveryService {
    private final InstalledIntegrationService integration;
    private final InstalledIntegrationReceiptMapper receipts;
    private final InstalledIntegrationRecoveryMapper recovery;
    private final ObjectMapper json;

    @Transactional
    public Plan plan(long tenant,String installation,String source,String event) {
        validateScope(tenant,installation,source,event);
        var r=requireReceipt(tenant,installation,source,event);
        var e=recovery.event(tenant,r.durableEventId());
        requireFailed(e);
        if(!"accepted".equals(r.state()))throw conflict();
        var target=integration.recoveryTarget(r);
        verify(r,e,target);
        if(r.commandSha256().equals(target.commandHash()) && r.providerSha256().equals(target.artifact()))throw conflict();
        return new Plan(r.requestPid(),r.envelopeSha256(),r.bindingSha256(),r.commandSha256(),r.providerSha256(),
                target.commandHash(),target.artifact(),e.replayCount());
    }

    @Transactional
    public Result authorize(long tenant,String installation,String source,String event,String actor,Decision d) {
        validateScope(tenant,installation,source,event);validateDecision(d);
        if(actor==null || !actor.matches("[A-Za-z0-9_-]{1,100}"))throw new SecurityException("Human operator identity required");
        // Match the worker's lock order. A failed row cannot acquire a delivery lease while authorized.
        String durable="ext:"+installation+":"+source+":"+event;
        var e=recovery.lockEvent(tenant,durable);
        if(e==null)throw missing();
        var r=requireReceipt(tenant,installation,source,event);
        if(!durable.equals(r.durableEventId()))throw conflict();
        var target=integration.recoveryTarget(r);verify(r,e,target);
        if(!d.requestPid().equals(r.requestPid()) || !d.envelopeSha256().equals(r.envelopeSha256())
                || !d.bindingSha256().equals(r.bindingSha256()) || !d.commandSha256().equals(target.commandHash())
                || !d.providerSha256().equals(target.artifact()))throw conflict();
        var prior=recovery.audit(tenant,installation,source,event,d.previousCommandSha256(),
                d.previousProviderSha256(),d.expectedReplayCount());
        if(prior!=null) {
            if(!actor.equals(prior.requestedBy()) || !d.reason().equals(prior.reason())
                    || !d.requestPid().equals(prior.requestPid()) || !d.envelopeSha256().equals(prior.envelopeSha256())
                    || !d.bindingSha256().equals(prior.bindingSha256()) || !d.commandSha256().equals(prior.commandSha256())
                    || !d.providerSha256().equals(prior.providerSha256()) || !prior.commandSha256().equals(r.commandSha256())
                    || !prior.providerSha256().equals(r.providerSha256()) || !Set.of("accepted","completed").contains(r.state()))throw conflict();
            return result(prior);
        }
        requireFailed(e);
        if(!"accepted".equals(r.state()) || e.replayCount()!=d.expectedReplayCount()
                || !d.previousCommandSha256().equals(r.commandSha256()) || !d.previousProviderSha256().equals(r.providerSha256())
                || (d.previousCommandSha256().equals(d.commandSha256()) && d.previousProviderSha256().equals(d.providerSha256())))throw conflict();
        var audit=new Audit(UlidGenerator.generate(),tenant,installation,source,event,durable,r.requestPid(),
                r.envelopeSha256(),r.bindingSha256(),r.commandSha256(),r.providerSha256(),target.commandHash(),
                target.artifact(),e.replayCount(),actor,d.reason(),Instant.now());
        if(receipts.recover(tenant,installation,source,event,d)!=1)throw conflict();
        if(recovery.insert(audit)!=1)throw new IllegalStateException("Recovery audit was not recorded");
        // No enqueue or business execution: the attributed DLQ replay remains a separate explicit action.
        // Return the persisted representation, including PostgreSQL timestamp precision.
        // Returning the pre-insert Instant can differ on a lost-response retry on Linux.
        var recorded=recovery.audit(tenant,installation,source,event,d.previousCommandSha256(),
                d.previousProviderSha256(),d.expectedReplayCount());
        if(recorded==null)throw new IllegalStateException("Recovery audit could not be read back");
        return result(recorded);
    }
    private void verify(InstalledIntegrationReceiptMapper.Receipt r,Event e,InstalledIntegrationService.Resolved target) {
        if(e==null || e.tenantId()!=r.tenantId() || !target.bindingHash().equals(r.bindingSha256())
                || r.requestPid()==null || !r.requestPid().matches("[A-Za-z0-9_-]{1,100}"))throw conflict();
        try {
            var parser=json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            var envelope=new IntegrationEventEnvelope(e.schemaVersion(),e.eventId(),e.eventType(),e.eventSource(),e.subject(),
                    e.occurredAt(),e.tenantId(),e.correlationId(),e.causationId(),e.orderingKey(),e.eventSequence(),
                    parser.readValue(e.payload(),new TypeReference<Map<String,Object>>(){}),
                    parser.readValue(e.headers(),new TypeReference<Map<String,String>>(){}));
            integration.verifyRecoveryEnvelope(target,envelope,r);
        } catch(com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Stored integration envelope is invalid");
        }
    }
    private InstalledIntegrationReceiptMapper.Receipt requireReceipt(long tenant,String installation,String source,String event) {
        var r=receipts.find(tenant,installation,source,event);
        if(r==null || r.tenantId()!=tenant || !installation.equals(r.installationPid())
                || !source.equals(r.sourceCode()) || !event.equals(r.externalEventId()))throw missing();
        return r;
    }
    private static void requireFailed(Event e) {
        if(e==null || !"failed".equals(e.status()) || !e.openDeadLetter())throw conflict();
    }
    private static void validateScope(long tenant,String installation,String source,String event) {
        if(tenant<=0 || installation==null || !installation.matches("[A-Za-z0-9_-]{1,100}")
                || source==null || !source.matches("[a-z][a-z0-9-]{1,63}") || event==null || event.isBlank() || event.length()>160)
            throw new IllegalArgumentException("Invalid recovery identity");
    }
    private static void validateDecision(Decision d) {
        if(d==null || d.requestPid()==null || !d.requestPid().matches("[A-Za-z0-9_-]{1,100}")
                || !digest(d.envelopeSha256(),false) || !digest(d.bindingSha256(),false)
                || !digest(d.previousCommandSha256(),false) || !digest(d.commandSha256(),false)
                || !digest(d.previousProviderSha256(),true) || !digest(d.providerSha256(),true)
                || d.expectedReplayCount()<0 || d.reason()==null || d.reason().isBlank() || d.reason().length()>512)
            throw new IllegalArgumentException("Recovery requires exact fingerprints, version and a bounded reason");
    }
    private static boolean digest(String value,boolean provider) {
        return value!=null && value.matches((provider?"sha256:":"")+"[a-f0-9]{64}");
    }
    private static Result result(Audit a) {
        return new Result(a.pid(),a.externalEventId(),a.requestPid(),"authorized",a.replayCount(),a.requestedBy(),a.reason(),a.requestedAt());
    }
    private static IllegalStateException conflict(){return new IllegalStateException("Receipt, envelope, failed delivery or recovery decision changed");}
    private static NoSuchElementException missing(){return new NoSuchElementException("Installed integration receipt not found");}
}
