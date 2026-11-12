package com.auraboot.framework.notification.sourcebinding;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.notification.service.impl.PluginNotificationDeliveryAccessorImpl;
import com.auraboot.framework.notification.wechat.WechatSubscriptionReservationProperties;
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

/** Only host-registered sources and explicitly enabled channels; recipients remain owned by source policy. */
@Component
public final class RegisteredSourceWorker {
    private final RegisteredSourcePlanResolver planner;
    private final SourceBindingReader reader;
    private final NotificationDeliveryAccessor delivery;
    private final TransactionTemplate transaction;
    private final Set<String> wechatSourceTypes;
    @Autowired
    RegisteredSourceWorker(RegisteredSourcePlanResolver planner, ImportedNotificationMetadataReader reader,
            PluginNotificationDeliveryAccessorImpl delivery, PlatformTransactionManager manager,
            HostSourceBindingProperties properties, WechatSubscriptionReservationProperties reservations) {
        this(planner,(SourceBindingReader) reader,(NotificationDeliveryAccessor) delivery, manager, properties, reservations);
    }
    RegisteredSourceWorker(RegisteredSourcePlanResolver planner, SourceBindingReader reader, NotificationDeliveryAccessor delivery, PlatformTransactionManager manager) {
        this(planner, reader, delivery, manager, new HostSourceBindingProperties(), new WechatSubscriptionReservationProperties());
    }
    RegisteredSourceWorker(RegisteredSourcePlanResolver planner, SourceBindingReader reader, NotificationDeliveryAccessor delivery,
            PlatformTransactionManager manager, HostSourceBindingProperties properties, WechatSubscriptionReservationProperties reservations) {
        this.planner = planner;
        this.reader = reader;
        this.delivery = delivery;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        if (properties.getWorker().isWechatSubscriptionEnabled()) {
            SourceBindingTypes.require(properties.isEnabled() && reservations.isEnabled() && !reservations.getSourceTypes().isEmpty());
            Set<String> registered = planner.registeredBindings().stream().map(SourceBindingTypes.Binding::code)
                    .collect(java.util.stream.Collectors.toSet());
            SourceBindingTypes.require(registered.containsAll(reservations.getSourceTypes()));
            wechatSourceTypes = Set.copyOf(reservations.getSourceTypes());
        } else {
            wechatSourceTypes = Set.of();
        }
    }
    /** Returns source-plan count, not message count. A blocked optional channel is reported after commit. */
    public int process(long tenant, String bindingCode, String sourcePid) {
        var result = processChannels(tenant, bindingCode, sourcePid);
        if (!result.failures().isEmpty()) throw new IllegalStateException("NOTIFICATION_SOURCE_WECHAT_DELIVERY_BLOCKED");
        return result.processedPlans();
    }
    record ProcessResult(int processedPlans, List<SourceFailure> failures) {
        ProcessResult { failures = List.copyOf(failures); }
    }
    /** All validated users share one transaction. Missing WeChat consent must not discard valid in-app delivery. */
    ProcessResult processChannels(long tenant, String bindingCode, String sourcePid) {
        var b = planner.bindings(tenant).stream().filter(x -> x.code().equals(bindingCode)).findFirst();
        if (b.isEmpty()) return new ProcessResult(0, List.of());
        return scoped(tenant,() -> transaction.execute(status -> {
            var plans = planner.plan(b.get(), sourcePid);
            for (var p : plans) {
                var result = delivery.enqueue(new NotificationDeliveryAccessor.Request(tenant, p.reference(), NotificationDeliveryAccessor.Channel.IN_APP));
                if (result.status() == NotificationDeliveryAccessor.Status.BLOCKED) throw new IllegalStateException("NOTIFICATION_SOURCE_DELIVERY_BLOCKED");
            }
            boolean blocked = false;
            if (wechatSourceTypes.contains(bindingCode)) {
                for (var p : plans) {
                    var result = delivery.enqueue(new NotificationDeliveryAccessor.Request(tenant, p.reference(), NotificationDeliveryAccessor.Channel.WECHAT_SUBSCRIPTION));
                    blocked |= result.status() == NotificationDeliveryAccessor.Status.BLOCKED;
                }
            }
            return new ProcessResult(plans.size(), blocked
                    ? List.of(new SourceFailure(sourcePid, null, SourceFailureCode.WECHAT_DELIVERY_BLOCKED)) : List.of());
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
                var result = processChannels(tenant, code, pid);
                plans += result.processedPlans();
                failures.addAll(result.failures());
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
