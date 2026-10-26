package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.inbox.model.InboxItem;
import com.auraboot.framework.inbox.service.InboxService;
import com.auraboot.framework.notification.service.impl.StrictInAppDeliveryWriter;
import com.auraboot.framework.plugin.extension.integration.*;
import com.auraboot.framework.workbench.mapper.AnnouncementDeliveryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

/** Receipt-transaction consumer: bounded expansion, persistent notification and independent acknowledgement projection. */
@Component
@RequiredArgsConstructor
public class AnnouncementDeliveryConsumer implements ReliableEventConsumerExtension {
    public static final String EXPAND="announcement.delivery.expand.v1";
    public static final String ACK="announcement.ack.project.v1";
    public static final String DELIVER="announcement.delivery.requested.v1";
    private final AnnouncementDeliveryMapper deliveries;
    private final AnnouncementDeliveryPlanResolver plans;
    private final AnnouncementCodec codec;
    private final AnnouncementReminderService reminders;
    private final StrictInAppDeliveryWriter writer;
    private final ReliableIntegrationAccessor outbox;
    private final InboxService inbox;
    @Value("${aura.notification.plugin-delivery.enabled:false}") private boolean enabled;
    public String consumerCode(){return "core.announcement.delivery.v1";}
    public Set<String> subscribedEventTypes(){return Set.of(AnnouncementReleaseService.RELEASED_EVENT,EXPAND,DELIVER,ACK,AnnouncementReminderService.EXPAND);}

    public void consume(IntegrationEventEnvelope event) {
        if(!enabled) throw new IllegalStateException("Announcement notification bridge is disabled");
        if(!Objects.equals(MetaContext.getCurrentTenantId(),event.tenantId()) || !"auraboot.announcement".equals(event.source()))
            throw new SecurityException("Announcement delivery context is invalid");
        if(AnnouncementReminderService.EXPAND.equals(event.eventType())) {reminders.expand(event);return;}
        String version=required(event,"versionPid"),batch=required(event,"batchPid");
        if(!DELIVER.equals(event.eventType()) && !ACK.equals(event.eventType())) {
            if(!"succeeded".equals(deliveries.releaseState(event.tenantId(),batch,version)))
                throw new SecurityException("Announcement delivery has no committed release");
            String after=Objects.toString(event.payload().get("after"),"");
            List<Map<String,Object>> rows=deliveries.recipients(event.tenantId(),version,after);
            for(Map<String,Object> row:rows) {
                String user=(String)row.get("user_pid");
                deliveries.intent(event.tenantId(),batch,version,user,"publication");
                Map<String,Object> payload=Map.of("versionPid",version,"batchPid",batch,"userPid",user);
                outbox.enqueueIdempotent(envelope(event,batch+":"+user,DELIVER,payload));
                outbox.enqueueIdempotent(envelope(event,"ack:"+batch+":"+user,ACK,payload));
            }
            if(rows.size()==500) {
                String cursor=(String)rows.get(rows.size()-1).get("user_pid");
                outbox.enqueueIdempotent(envelope(event,"expand:"+batch+":"+cursor,EXPAND,Map.of("versionPid",version,"batchPid",batch,"after",cursor)));
            }
            return;
        }
        String user=required(event,"userPid");
        if(deliveries.lockRoot(event.tenantId(),version)==null) throw new IllegalStateException("Delivery version root is missing");
        Map<String,Object> row=deliveries.plan(event.tenantId(),batch,user);
        if(row==null) {deliveries.complete(event.tenantId(),batch,user,"canceled","RECIPIENT_OR_VERSION_INACTIVE");return;}
        if(!version.equals(row.get("version_pid"))) throw new SecurityException("Announcement delivery binding changed");
        if("reminder".equals(row.get("kind"))) {
            if(ACK.equals(event.eventType())) throw new SecurityException("Reminders cannot create acknowledgement tasks");
            String reason=reminders.deliveryEligibility(event.tenantId(),batch,version,row);
            if(reason!=null) {deliveries.complete(event.tenantId(),batch,user,"skipped",reason);return;}
        }
        if(!ACK.equals(event.eventType()) && !"queued".equals(row.get("status"))) return;
        var plan=plans.resolve(event.tenantId(),"ann1."+batch+"."+user).orElseThrow();
        if(DELIVER.equals(event.eventType())) {
            writer.persist(plan);
            if(deliveries.complete(event.tenantId(),batch,user,"completed",null)!=1) throw new IllegalStateException("Announcement delivery completion lost");
            return;
        }
        var snapshot=codec.object(row.get("snapshot").toString());
        @SuppressWarnings("unchecked") Map<String,Object> policy=(Map<String,Object>)snapshot.get("policy");
        if(Boolean.TRUE.equals(policy.get("acknowledgementRequired")) && row.get("acknowledged_at")==null) {
            InboxItem item=new InboxItem();item.setTenantId(event.tenantId());item.setUserId(((Number)row.get("user_id")).longValue());
            item.setItemType("announcement_ack");item.setTitle(plan.title());item.setSubtitle(plan.title());
            item.setPriority("important".equals(snapshot.get("importance")) ? "high" : "normal");
            item.setStatus("pending");item.setSourceType("announcement");item.setSourceId(version);item.setModelCode("ab_announcement");
            item.setRecordPid((String)row.get("announcement_pid"));item.setClientItemId("announcement_ack:"+version+":"+user);
            item.setDeepLink("/p/announcement_reader/view/"+version);item.setIsRead(false);
            if(row.get("expires_at")!=null) item.setExpiresAt(AnnouncementDeliveryPlanResolver.instant(row.get("expires_at")));
            inbox.createItem(item);
        }
    }
    private IntegrationEventEnvelope envelope(IntegrationEventEnvelope source,String id,String type,Map<String,Object> payload) {
        return new IntegrationEventEnvelope("1.0",id,type,"auraboot.announcement",source.subject(),source.occurredAt(),source.tenantId(),
            source.correlationId(),source.eventId(),source.orderingKey(),source.sequence(),payload,Map.of());
    }
    private static String required(IntegrationEventEnvelope event,String key) {
        Object value=event.payload().get(key);
        if(!(value instanceof String s) || s.isBlank()) throw new IllegalArgumentException("Invalid announcement delivery reference");
        return (String)value;
    }
}
