package com.auraboot.framework.notification.sourcebinding;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import java.util.function.Supplier;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Default-off host poll adapter using the existing Spring scheduler and application lifecycle. */
@Component
public final class RegisteredSourcePollingAdapter {
    private static final Logger LOG = LoggerFactory.getLogger(RegisteredSourcePollingAdapter.class);
    private final HostSourceBindingProperties properties;
    private final RegisteredSourcePlanResolver planner;
    private final RegisteredSourceWorker worker;
    private final Supplier<ThreadPoolTaskScheduler> schedulerFactory;
    private final Map<ScopeKey, Integer> cursors = new HashMap<>();
    private ThreadPoolTaskScheduler scheduler;
    private final AtomicBoolean polling = new AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicReference<Long> progressAt = new java.util.concurrent.atomic.AtomicReference<>();
    private Settings settings;
    private List<SourceBindingTypes.Binding> scope = List.of();
    private ScheduledFuture<?> scheduled;

    public enum Status { DISABLED, BUSY, SUCCEEDED, FAILED }
    public record Failure(long tenantId, String bindingCode, String sourcePid, String exceptionType, SourceFailureCode code) {
        public Failure(long tenantId, String bindingCode, String sourcePid, String exceptionType) {
            this(tenantId, bindingCode, sourcePid, exceptionType, SourceFailureCode.PROCESSING_FAILED);
        }
    }
    public record TickResult(Status status, int bindingsAttempted, int processedPlans, long matchedSources, int selectedSources, List<Failure> failures) {
        public TickResult {
            failures = List.copyOf(failures);
        }
    }
    private record Settings(long intervalMs, int maxSources) {}
    private record ScopeKey(long tenantId, String bindingCode) {}

    @Autowired
    public RegisteredSourcePollingAdapter(HostSourceBindingProperties properties,
                                          RegisteredSourcePlanResolver planner, RegisteredSourceWorker worker) {
        this(properties, planner, worker, ThreadPoolTaskScheduler::new);
    }
    RegisteredSourcePollingAdapter(HostSourceBindingProperties properties,
                                   RegisteredSourcePlanResolver planner, RegisteredSourceWorker worker,
                                   Supplier<ThreadPoolTaskScheduler> schedulerFactory) {
        this.properties = properties;
        this.planner = planner;
        this.worker = worker;
        this.schedulerFactory = schedulerFactory;
    }

    @PostConstruct
    void initialize() {
        var config = properties.getWorker();
        if (!config.isEnabled()) return;
        SourceBindingTypes.require(properties.isEnabled()
                && config.getPollIntervalMs() != null && config.getPollIntervalMs() > 0
                && config.getPollIntervalMs() <= 86400000L
                && config.getMaxSourcesPerBinding() != null && config.getMaxSourcesPerBinding() > 0
                && config.getMaxSourcesPerBinding() <= 1000
                && config.getMaxBindings() != null && config.getMaxBindings() > 0
                && config.getMaxBindings() <= 128);
        scope = List.copyOf(planner.registeredBindings());
        SourceBindingTypes.require(!scope.isEmpty() && scope.size() <= config.getMaxBindings());
        settings = new Settings(config.getPollIntervalMs(), config.getMaxSourcesPerBinding());
    }

    @EventListener(ApplicationReadyEvent.class)
    synchronized void start() {
        if (settings == null || scheduled != null) return;
        scheduler = schedulerFactory.get();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("notification-source-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        try {
            scheduler.initialize();
            Duration interval = Duration.ofMillis(settings.intervalMs());
            scheduled = scheduler.scheduleWithFixedDelay(this::poll, Instant.now().plus(interval), interval);
            if (scheduled == null) throw new IllegalStateException("NOTIFICATION_SOURCE_SCHEDULE_UNAVAILABLE");
        } catch (RuntimeException failure) {
            scheduler.shutdown();
            scheduler = null;
            throw failure;
        }
    }

    @PreDestroy
    synchronized void stop() {
        if (scheduled != null) {
            scheduled.cancel(false);
            scheduled = null;
        }
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
    }

    /** Failures remain explicit; already committed independent source transactions are not rolled back together. */
    public TickResult tick() {
        if (settings == null) return new TickResult(Status.DISABLED, 0, 0, 0, 0, List.of());
        if (!polling.compareAndSet(false, true)) return new TickResult(Status.BUSY, 0, 0, 0, 0, List.of());
        try {
            int attempted = 0;
            int plans = 0;
            long matched = 0;
            int selected = 0;
            List<Failure> failures = new ArrayList<>();
            for (var binding : scope) {
                attempted++;
                try {
                    var key = new ScopeKey(binding.tenantId(), binding.code());
                    var result = worker.runPage(binding.tenantId(), binding.code(), cursors.getOrDefault(key, 1), settings.maxSources());
                    cursors.put(key, result.nextPage());
                    plans += result.processedPlans();
                    matched += result.matchedSources();
                    selected += result.selectedSources();
                    for (var failure : result.failures()) {
                        failures.add(new Failure(binding.tenantId(), binding.code(), failure.sourcePid(), failure.exceptionType(), failure.code()));
                    }
                } catch (RuntimeException failure) {
                    failures.add(new Failure(binding.tenantId(), binding.code(), null, failure.getClass().getName(), SourceFailureCode.classify(failure)));
                }
            }
            return new TickResult(failures.isEmpty() ? Status.SUCCEEDED : Status.FAILED,
                    attempted, plans, matched, selected, failures);
        } finally {
            polling.set(false);
        }
    }

    boolean shouldReportProgress(TickResult result,long nowNanos) {
        if(result.status()!=Status.SUCCEEDED || result.selectedSources()==0)return false;
        Long previous=progressAt.get();
        if(previous!=null && nowNanos-previous<java.util.concurrent.TimeUnit.MINUTES.toNanos(1))return false;
        return progressAt.compareAndSet(previous,nowNanos);
    }

    void poll() {
        TickResult result = tick();
        if(shouldReportProgress(result,System.nanoTime()))
            LOG.info("Registered notification source progress: bindings={}, selected={}, matched={}, plans={}",
                    result.bindingsAttempted(),result.selectedSources(),result.matchedSources(),result.processedPlans());
        if (result.status() == Status.FAILED) {
            LOG.error("Registered notification source poll failed: {}", result.failures());
            throw new IllegalStateException("NOTIFICATION_SOURCE_POLL_FAILED");
        }
    }
}
