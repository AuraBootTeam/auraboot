package com.auraboot.framework.entitlement.spi;

import java.util.Map;

public interface EntitlementSnapshotService {
    Map<String, Object> getSnapshot();
}
