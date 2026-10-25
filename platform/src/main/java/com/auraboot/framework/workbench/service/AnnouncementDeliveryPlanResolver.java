package com.auraboot.framework.workbench.service;

import com.auraboot.framework.notification.service.NotificationDeliveryPlanResolver;
import com.auraboot.framework.workbench.mapper.AnnouncementDeliveryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

/** Public channel adapters receive only this host-resolved, fixed-recipient plan. */
@Component
@RequiredArgsConstructor
public class AnnouncementDeliveryPlanResolver implements NotificationDeliveryPlanResolver {
    private final AnnouncementDeliveryMapper deliveries;
    private final AnnouncementCodec codec;
    private final AnnouncementReminderService reminders;
    @Override public Optional<Plan> resolve(long tenant,String reference) {
        if(reference==null || !reference.startsWith("ann1.")) return Optional.empty();
        String[] parts=reference.split("\\.");
        if(parts.length!=3 || !parts[1].matches("[A-Za-z0-9_-]{1,36}") || !parts[2].matches("[A-Za-z0-9_-]{1,36}")) return Optional.empty();
        Map<String,Object> row=deliveries.plan(tenant,parts[1],parts[2]);
        if(row==null) return Optional.empty();
        if("reminder".equals(row.get("kind")) && reminders.deliveryEligibility(tenant,parts[1],(String)row.get("version_pid"),row)!=null) return Optional.empty();
        var snapshot=codec.object(row.get("snapshot").toString());
        String title=(String)snapshot.get("title");
        // A bell entry is a navigation hint; protected content is fetched only by the version view.
        return Optional.of(new Plan(tenant,reference,parts[2],"announcement.v1","announcement",
            (String)row.get("version_pid"),instant(row.get("created_at")),title,title));
    }
    static Instant instant(Object value) {
        if(value instanceof Instant i) return i;
        if(value instanceof java.sql.Timestamp t) return t.toInstant();
        if(value instanceof OffsetDateTime o) return o.toInstant();
        throw new IllegalStateException("Announcement time was not mapped");
    }
}
