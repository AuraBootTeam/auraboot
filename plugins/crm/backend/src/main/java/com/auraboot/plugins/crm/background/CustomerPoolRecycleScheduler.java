package com.auraboot.plugins.crm.background;

import com.auraboot.framework.plugin.extension.BackgroundComponentExtension;
import com.auraboot.framework.plugin.extension.BackgroundDataAccessor;
import com.auraboot.framework.plugin.extension.BackgroundTenantAccessor;
import com.auraboot.framework.plugin.extension.DataAccessor;
import com.auraboot.framework.plugin.extension.RecordShareAccessor;
import com.auraboot.plugins.crm.handler.CustomerPoolCommandHandler;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Periodically applies enabled customer-pool recycle policies across active tenants. */
@Extension
public class CustomerPoolRecycleScheduler implements BackgroundComponentExtension {

    private static final Logger log = LoggerFactory.getLogger(CustomerPoolRecycleScheduler.class);

    @Autowired
    private BackgroundDataAccessor data;

    @Autowired
    private BackgroundTenantAccessor tenants;

    @Autowired
    private RecordShareAccessor shares;

    @Value("${aura.crm.customer-pool.recycle-lease-timeout-ms:900000}")
    private long recycleLeaseTimeoutMs;

    @Scheduled(
            fixedDelayString = "${aura.crm.customer-pool.recycle-interval-ms:300000}",
            initialDelayString = "${aura.crm.customer-pool.recycle-initial-delay-ms:60000}")
    public void recycleDueCustomers() {
        for (Long tenantId : tenants.listActiveTenantIds()) {
            try {
                CustomerPoolCommandHandler.RecycleResult result = CustomerPoolCommandHandler.recycleDetailed(
                        new TenantDataAccessor(data, tenantId), shares, tenantId, "system", Instant.now(),
                        Duration.ofMillis(recycleLeaseTimeoutMs));
                if (result.recycled() > 0 || result.recovered() > 0) {
                    log.info("Customer-pool recycle finished for tenant {}: recycled={}, recovered={}, activeLeases={}",
                            tenantId, result.recycled(), result.recovered(), result.activeLeases());
                }
                if (result.failed() > 0) {
                    log.error("Customer-pool recycle completed with {} failed item(s) for tenant {}",
                            result.failed(), tenantId);
                }
            } catch (RuntimeException error) {
                if (isCrmModelAbsent(error)) {
                    log.debug("Skipping customer-pool recycle for tenant {} because CRM is not installed", tenantId);
                } else {
                    log.error("Customer-pool recycle failed for tenant {}", tenantId, error);
                }
            }
        }
    }

    static boolean isCrmModelAbsent(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if ("Model not found: crm_customer_pool".equals(current.getMessage())) return true;
        }
        return false;
    }

    record TenantDataAccessor(BackgroundDataAccessor delegate, long tenantId) implements DataAccessor {
        @Override public Map<String, Object> getById(String modelCode, String recordId) {
            return delegate.getById(tenantId, modelCode, recordId);
        }
        @Override public List<Map<String, Object>> query(String modelCode, Map<String, Object> filters) {
            return delegate.query(tenantId, modelCode, filters);
        }
        @Override public Map<String, Object> create(String modelCode, Map<String, Object> values) {
            return delegate.create(tenantId, modelCode, values);
        }
        @Override public Optional<Map<String, Object>> tryCreate(String modelCode, Map<String, Object> values) {
            return delegate.tryCreate(tenantId, modelCode, values);
        }
        @Override public Map<String, Object> update(String modelCode, String recordId, Map<String, Object> values) {
            return delegate.update(tenantId, modelCode, recordId, values);
        }
        @Override public boolean compareAndSet(String modelCode, String recordId, String fieldCode,
                                               Object expectedValue, Object nextValue) {
            return delegate.compareAndSet(tenantId, modelCode, recordId, fieldCode, expectedValue, nextValue);
        }
        @Override public boolean compareAndSet(String modelCode, String recordId, String fieldCode,
                                               Object expectedValue, Map<String, Object> nextValues) {
            return delegate.compareAndSet(tenantId, modelCode, recordId, fieldCode, expectedValue, nextValues);
        }
        @Override public List<Map<String, Object>> batchCreate(String modelCode, List<Map<String, Object>> values) {
            return values.stream().map(value -> create(modelCode, value)).toList();
        }
        @Override public void delete(String modelCode, String recordId) {
            delegate.delete(tenantId, modelCode, recordId);
        }
        @Override public Optional<Long> incrementWithinCap(String modelCode, String recordId,
                                                           String counterCode, long delta, String capCode) {
            return delegate.incrementWithinCap(tenantId, modelCode, recordId, counterCode, delta, capCode);
        }
        @Override public List<Map<String, Object>> queryIn(String modelCode, String fieldName, Collection<?> values) {
            return delegate.queryIn(tenantId, modelCode, fieldName, values);
        }
    }
}
