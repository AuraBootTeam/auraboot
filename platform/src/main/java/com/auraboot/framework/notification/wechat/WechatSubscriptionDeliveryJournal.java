package com.auraboot.framework.notification.wechat;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.notification.entity.NotificationSendLog;
import com.auraboot.framework.notification.mapper.NotificationSendLogMapper;
import com.auraboot.framework.plugin.extension.integration.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Durable external intent and one-attempt fence. Integration receipts mean materialized intent, never external delivery. */
@Service
public class WechatSubscriptionDeliveryJournal implements ReliableEventConsumerExtension, com.auraboot.framework.integration.HostSerializableEventConsumer {
    public static final String EVENT_TYPE="notification.wechat.intent-requested.v1";
    public enum Status { BLOCKED, QUEUED, REPLAYED }
    public record QueueResult(Status status, WechatSubscriptionMessageSender.Reason reason, String deliveryKey) {}
    private final NotificationSendLogMapper ledger;
    private final ReliableIntegrationAccessor reliable;
    private final WechatSubscriptionMessageSender sender;
    private final TransactionTemplate isolated;
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    public WechatSubscriptionDeliveryJournal(NotificationSendLogMapper ledger,
            ReliableIntegrationAccessor reliable,WechatSubscriptionMessageSender sender,PlatformTransactionManager manager) {
        this.ledger=ledger;this.reliable=reliable;this.sender=sender;
        isolated=new TransactionTemplate(manager);isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public QueueResult enqueue(WechatSubscriptionMessageSender.Request request) {
        var preparation=sender.prepare(request);
        if(preparation.prepared()==null)return new QueueResult(Status.BLOCKED,preparation.result().reason(),null);
        var prepared=preparation.prepared();var verification=sender.verifyPrepared(prepared);
        if(verification!=null)return new QueueResult(Status.BLOCKED,verification.reason(),null);
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("NOTIFICATION_SOURCE_TRANSACTION_REQUIRED");
        String key=key(request.tenantId(),request.resolutionReference()),snapshot=encode(prepared),hash=hash(snapshot);
        if(prepared.grant().reservedAt()==null)return new QueueResult(Status.BLOCKED,WechatSubscriptionMessageSender.Reason.GRANT_INVALID,null);
        // Immutable host reservation time, not a per-call clock: concurrent first writers must create the same envelope.
        Instant time=prepared.grant().reservedAt().truncatedTo(ChronoUnit.MICROS);
        var envelope=envelope(request.tenantId(),request.resolutionReference(),key,time,snapshot,hash);
        boolean created=reliable.enqueueIdempotent(envelope);
        return new QueueResult(created?Status.QUEUED:Status.REPLAYED,null,key);
    }
    @Override public String consumerCode(){return "core.notification.wechat.intent.v1";}
    @Override public boolean requiresSerializableTransaction(IntegrationEventEnvelope event) {
        return event != null && EVENT_TYPE.equals(event.eventType());
    }
    @Override public Set<String> subscribedEventTypes(){return Set.of(EVENT_TYPE);}
    @Override public void consume(IntegrationEventEnvelope event) {
        requireTenant(event.tenantId());
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("NOTIFICATION_INTENT_TRANSACTION_REQUIRED");
        if(!EVENT_TYPE.equals(event.eventType()))throw new IllegalArgumentException("NOTIFICATION_EVENT_TYPE_INVALID");
        Object raw=event.payload().get("snapshot"),rawHash=event.payload().get("hash");
        if(!(raw instanceof String snapshot) || !(rawHash instanceof String digest) || !hash(snapshot).equals(digest))
            throw new IllegalArgumentException("NOTIFICATION_SNAPSHOT_INVALID");
        var prepared=decode(snapshot);var grant=prepared.grant();
        if(grant.reservedAt()==null || !grant.reservedAt().truncatedTo(ChronoUnit.MICROS).equals(event.occurredAt())
                || grant.tenantId()!=event.tenantId() || !key(event.tenantId(),grant.resolutionReference()).equals(event.eventId())
                || !envelope(event.tenantId(),grant.resolutionReference(),event.eventId(),event.occurredAt(),snapshot,digest).equals(event))
            throw new IllegalArgumentException("NOTIFICATION_ENVELOPE_CONFLICT");
        var validation=sender.verifyPrepared(prepared);
        if(validation!=null)throw new IllegalArgumentException("NOTIFICATION_RESERVED_FACT_UNAVAILABLE");
        NotificationSendLog intent=new NotificationSendLog();intent.setTenantId(event.tenantId());intent.setChannel("wechat_subscription");
        intent.setRecipient(grant.userPid());intent.setDeliveryKey(event.eventId());intent.setRequestHash(digest);
        intent.setResolutionReference(grant.resolutionReference());intent.setConfigurationHash(prepared.configurationHash());
        intent.setRequestSnapshot(snapshot);intent.setStatus("pending");
        int inserted=ledger.insertDurableIntent(intent);
        var stored=ledger.findDurable(event.tenantId(),event.eventId());
        if(inserted<0 || inserted>1 || stored==null || !digest.equals(stored.getRequestHash())
                || !prepared.equals(decode(stored.getRequestSnapshot())))throw new IllegalArgumentException("NOTIFICATION_INTENT_CONFLICT");
    }
    /** Dispatch an exact owned intent; never called inside the outbox consumer transaction. */
    public NotificationSendLog attempt(long tenant,String key) {
        requireTenant(tenant);
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("NOTIFICATION_NETWORK_OUTSIDE_TRANSACTION_REQUIRED");
        isolated.executeWithoutResult(status->ledger.expireDurable(tenant,key));
        var stored=ledger.findDurable(tenant,key);if(stored==null)throw new IllegalArgumentException("NOTIFICATION_INTENT_NOT_FOUND");
        if(!"pending".equals(stored.getStatus()))return stored;
        String fence=UUID.randomUUID().toString();
        int claimed=Objects.requireNonNull(isolated.execute(status->ledger.claimDurable(tenant,key,stored.getRequestHash(),fence)));
        if(claimed!=1)return ledger.findDurable(tenant,key);
        var prepared=decode(stored.getRequestSnapshot());
        var validation=sender.verifyPrepared(prepared);
        var result=validation==null?sender.sendPrepared(prepared):validation;
        String terminal=switch(result.status()) {
            case PROVIDER_ACCEPTED->"accepted";case PROVIDER_REJECTED->"rejected";
            case OUTCOME_UNKNOWN->"unknown";case BLOCKED,NOT_ATTEMPTED->"not_attempted";
        };
        int finished=Objects.requireNonNull(isolated.execute(status->ledger.finishDurable(tenant,key,stored.getRequestHash(),fence,
                terminal,result.providerCode(),result.reason()==null?null:result.reason().name())));
        if(finished!=1)throw new IllegalStateException("NOTIFICATION_ATTEMPT_FENCE_LOST");
        return ledger.findDurable(tenant,key);
    }
    boolean readyForDispatch(){return sender.readyForDispatch();}
    public void dispatchPending(long tenant,int limit) {
        requireTenant(tenant);if(!readyForDispatch())return;
        if(limit<1 || limit>100)throw new IllegalArgumentException("NOTIFICATION_BATCH_INVALID");
        for(String key:ledger.dueDurable(tenant,limit))attempt(tenant,key);
    }
    private static void requireTenant(long tenant){if(tenant<=0 || !Objects.equals(MetaContext.getCurrentTenantId(),tenant))
        throw new IllegalArgumentException("NOTIFICATION_TENANT_MISMATCH");}
    private static IntegrationEventEnvelope envelope(long tenant,String reference,String key,Instant time,String snapshot,String hash) {
        return new IntegrationEventEnvelope("1.0",key,EVENT_TYPE,"core.notification",reference,time,tenant,key,null,key,0,
                Map.of("snapshot",snapshot,"hash",hash),Map.of());
    }
    private String encode(WechatSubscriptionMessageSender.Prepared prepared) {
        try{return json.writeValueAsString(prepared);}catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IllegalArgumentException("NOTIFICATION_SNAPSHOT_INVALID");}
    }
    private WechatSubscriptionMessageSender.Prepared decode(String snapshot) {
        try{return json.readValue(snapshot,WechatSubscriptionMessageSender.Prepared.class);}catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IllegalArgumentException("NOTIFICATION_SNAPSHOT_INVALID");}
    }
    private static String key(long tenant,String ref){return "notification-"+hash(tenant+"\n"+ref+"\nwechat_subscription");}
    private static String hash(String value) {
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable");}
    }
}
