package com.auraboot.framework.entitlement.spi;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;

@Service
@ConditionalOnMissingBean(value = EntitlementSnapshotService.class, ignored = NoOpEntitlementSnapshotService.class)
public class NoOpEntitlementSnapshotService implements EntitlementSnapshotService {
    private final EntitlementChecker entitlementChecker;

    public NoOpEntitlementSnapshotService(EntitlementChecker entitlementChecker) {
        this.entitlementChecker = entitlementChecker;
    }

    @Override
    public Map<String, Object> getSnapshot() {
        return Map.of(
                "enabled", entitlementChecker.isEnabled(),
                "entitlements", Collections.emptyList()
        );
    }
}
