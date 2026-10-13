package com.auraboot.framework.notification.sourcebinding;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.notification.service.impl.PluginNotificationDeliveryAccessorImpl;
import com.auraboot.framework.plugin.extension.DataPage;
import com.auraboot.framework.plugin.extension.NotificationDeliveryAccessor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** No automatic schedule or recipient policy. Only server-registered sources can be processed. */
@Component
public final class RegisteredSourceWorker {
    private final RegisteredSourcePlanResolver planner;
    private final SourceBindingReader reader;
    private final NotificationDeliveryAccessor delivery;
    private final TransactionTemplate transaction;
    @org
    .springframework.beans.factory.annotation.Autowired RegisteredSourceWorker(RegisteredSourcePlanResolver planner, ImportedNotificationMetadataReader reader, PluginNotificationDeliveryAccessorImpl delivery, PlatformTransactionManager manager) {
        this(planner,(SourceBindingReader) reader,(NotificationDeliveryAccessor) delivery, manager);
    }
    RegisteredSourceWorker(RegisteredSourcePlanResolver planner, SourceBindingReader reader, NotificationDeliveryAccessor delivery, PlatformTransactionManager manager) {
        this.planner = planner;
        this.reader = reader;
        this.delivery = delivery;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }
    /** One immutable source/all explicit users: any validation or enqueue failure rolls back every enqueue. */
    public int process(long tenant, String bindingCode, String sourcePid) {
        var b = planner.bindings(tenant).stream().filter(x -> x.code().equals(bindingCode)).findFirst();
        if (b.isEmpty()) return 0;
        return scoped(tenant,() -> transaction.execute(status -> {
            var plans = planner.plan(b.get(), sourcePid);
            for (var p : plans) {
                var result = delivery.enqueue(new NotificationDeliveryAccessor.Request(tenant, p.reference(), NotificationDeliveryAccessor.Channel.IN_APP));
                if (result.status() == NotificationDeliveryAccessor.Status.BLOCKED) throw new IllegalStateException("NOTIFICATION_SOURCE_DELIVERY_BLOCKED");
            }
            return plans.size();
        }));
    }
    /** Bounded complete scans, stable PID ordering and drift rejection. No broad tenant discovery. */
    public int runTenant(long tenant) {
        int count = 0;
        for (var b : planner.bindings(tenant)) {
            List<String> pids = scoped(tenant,() -> transaction.execute(status -> scan(b)));
            for (String pid : pids) count += process(tenant, b.code(), pid);
        }
        return count;
    }
    private List<String> scan(SourceBindingTypes.Binding b) {
        planner.validate(b);
        List<String> result = new ArrayList<>();
        long total = - 1;
        String prior = null;
        Set<String> seen = new HashSet<>();
        for (int page = 1; page <= 100; page++) {
            DataPage p = reader.page(b, b.source().entity().modelCode(), RegisteredSourcePlanResolver.filters(b.source().entity().equals()), page, 100);
            if (total<0) total = p.total();
            SourceBindingTypes.require(p.page() == page && p.size() == 100 && p.total() == total && total <= 10000 && p.records().size() == Math.min(100, Math.max(0, total -(long)(page - 1) * 100)));
            for (var row : p.records()) {
                String pid = SourceBindingTypes.pid((String) row.get("pid"));
                SourceBindingTypes.require(seen.add(pid) &&(prior == null || prior.compareTo(pid)<0));
                result.add(pid);
                prior = pid;
            }
            if (result.size() == total) return List.copyOf(result);
        }
        throw new IllegalArgumentException("NOTIFICATION_SOURCE_SCAN_INCOMPLETE");
    }
    private<T> T scoped(long tenant, Supplier<T> work) {
        MetaContext.Snapshot previous = MetaContext.snapshot();
        try {
            MetaContext.setContext(tenant, 0L, "notification-source-runtime", "notification-source-runtime");
            return work.get();
        } finally {
            MetaContext.clear();
            MetaContext.restore(previous);
        }
    }
}
