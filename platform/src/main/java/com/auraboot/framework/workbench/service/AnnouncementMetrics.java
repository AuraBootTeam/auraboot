package com.auraboot.framework.workbench.service;

import com.auraboot.framework.workbench.mapper.AnnouncementMetricsMapper;
import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Component;
import java.util.function.DoubleSupplier;
import java.util.Map;

/** Persisted queue facts survive process restarts; transport retry/DLQ metrics remain owned by reliable integration. */
@Component
public class AnnouncementMetrics {
    public AnnouncementMetrics(MeterRegistry registry,AnnouncementMetricsMapper facts) {
        register(registry,"auraboot_announcement_pending","publication",facts::publicationPending);
        register(registry,"auraboot_announcement_pending","approval",facts::approvalPending);
        register(registry,"auraboot_announcement_pending","delivery",facts::deliveryPending);
        register(registry,"auraboot_announcement_failures","publication",facts::publicationFailures);
        register(registry,"auraboot_announcement_failures","approval",facts::approvalFailures);
        register(registry,"auraboot_announcement_failures","delivery",facts::deliveryFailures);
        register(registry,"auraboot_announcement_failures","reminder",facts::reminderFailures);
        register(registry,"auraboot_announcement_oldest_pending_age_seconds","publication",facts::publicationAge);
        register(registry,"auraboot_announcement_oldest_pending_age_seconds","delivery",facts::deliveryAge);
        register(registry,"auraboot_announcement_publication_delay_24h_seconds","publication",facts::publicationDelay);
        register(registry,"auraboot_announcement_oldest_pending_age_seconds","approval",facts::approvalAge);
        register(registry,"auraboot_announcement_pending","export",facts::exportPending);
        register(registry,"auraboot_announcement_failures","export",facts::exportFailures);
        register(registry,"auraboot_announcement_oldest_pending_age_seconds","export",facts::exportAge);
        register(registry,"auraboot_announcement_failures","export_cleanup",facts::exportCleanupFailures);
        Map.of("publication_expansion",AnnouncementReleaseService.RELEASED_EVENT,"recipient_expansion",AnnouncementDeliveryConsumer.EXPAND,
            "notification_projection",AnnouncementDeliveryConsumer.DELIVER,"inbox_projection",AnnouncementDeliveryConsumer.ACK,
            "reminder_expansion",AnnouncementReminderService.EXPAND).forEach((stage,event)->{
                register(registry,"auraboot_announcement_pending",stage,()->facts.projectionPending(event));
                register(registry,"auraboot_announcement_failures",stage,()->facts.projectionFailures(event));
                register(registry,"auraboot_announcement_oldest_pending_age_seconds",stage,()->facts.projectionAge(event));
            });
    }
    private static void register(MeterRegistry registry,String name,String stage,DoubleSupplier value) {
        Gauge.builder(name,value,AnnouncementMetrics::read).strongReference(true).tag("stage",stage).register(registry);
    }
    private static double read(DoubleSupplier value) {
        try {return value.getAsDouble();}
        catch(RuntimeException unavailable) {
            // CATCH: telemetry must expose a missing datasource as NaN rather than a healthy-looking zero.
            return Double.NaN;
        }
    }
}
