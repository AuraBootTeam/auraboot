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
import org.springframework.beans.factory.annotation.Autowired;
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
    @Autowired
    RegisteredSourceWorker(RegisteredSourcePlanResolver planner, ImportedNotificationMetadataReader reader, PluginNotificationDeliveryAccessorImpl delivery, PlatformTransactionManager manager) {
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
    record SourceFailure(String sourcePid, String exceptionType, SourceFailureCode code) {
        SourceFailure(String sourcePid, String exceptionType) { this(sourcePid, exceptionType, SourceFailureCode.PROCESSING_FAILED); }
    }
    record BatchResult(int processedPlans, long matchedSources, int selectedSources, int nextPage,
                       List<SourceFailure> failures) {
        BatchResult {
            failures = List.copyOf(failures);
        }
    }
    private record Selection(List<String> pids, long total, int nextPage) {}

    /** One explicitly bounded page, never presented as a complete source inventory. */
    BatchResult runPage(long tenant, String code, int page, int size) {
        SourceBindingTypes.require(page > 0 && size > 0 && size <= 1000);
        var binding = planner.bindings(tenant).stream().filter(b -> b.code().equals(code)).findFirst();
        SourceBindingTypes.require(binding.isPresent());
        Selection selection = scoped(tenant, () -> transaction.execute(status -> selectPage(binding.get(), page, size)));
        int plans = 0;
        List<SourceFailure> failures = new ArrayList<>();
        for (String pid : selection.pids()) {
            try {
                plans += process(tenant, code, pid);
            } catch (RuntimeException failure) {
                failures.add(new SourceFailure(pid, failure.getClass().getName(), SourceFailureCode.classify(failure)));
            }
        }
        return new BatchResult(plans, selection.total(), selection.pids().size(), selection.nextPage(), failures);
    }
    private Selection selectPage(SourceBindingTypes.Binding binding, int page, int size) {
        planner.validate(binding);
        DataPage result = reader.page(binding, binding.source().entity().modelCode(),
                RegisteredSourcePlanResolver.filters(binding.source().entity().equals()), page, size);
        SourceBindingTypes.require(result.page() == page && result.size() == size && result.total() >= 0);
        long expected = Math.min(size, Math.max(0, result.total() - (long) (page - 1) * size));
        SourceBindingTypes.require(result.records().size() == expected);
        Set<String> seen = new HashSet<>();
        List<String> pids = new ArrayList<>();
        String previous = null;
        for (var row : result.records()) {
            String pid = SourceBindingTypes.pid((String) row.get("pid"));
            SourceBindingTypes.require(seen.add(pid) && (previous == null || previous.compareTo(pid) < 0));
            pids.add(pid);
            previous = pid;
        }
        SourceBindingTypes.require(result.total() <= (long) Integer.MAX_VALUE * size);
        long pages = (result.total() + size - 1) / size;
        SourceBindingTypes.require(pages <= Integer.MAX_VALUE);
        return new Selection(List.copyOf(pids), result.total(), page >= pages ? 1 : page + 1);
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
