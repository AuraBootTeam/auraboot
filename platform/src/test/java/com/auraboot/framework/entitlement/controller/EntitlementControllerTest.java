package com.auraboot.framework.entitlement.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.entitlement.spi.EntitlementChecker;
import com.auraboot.framework.entitlement.spi.NoOpEntitlementSnapshotService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntitlementControllerTest {

    @Test
    void listEntitlementsReturnsDisabledSnapshotInNoOpMode() {
        EntitlementChecker checker = new EntitlementChecker() {
            @Override public boolean isEnabled() { return false; }
            @Override public boolean isPluginActive(String pluginId) { return true; }
            @Override public boolean isPluginActive(Long tenantId, String pluginId) { return true; }
            @Override public boolean hasFeature(String pluginId, String featureKey) { return true; }
            @Override public boolean hasFeature(Long tenantId, String pluginId, String featureKey) { return true; }
        };
        EntitlementController controller =
                new EntitlementController(new NoOpEntitlementSnapshotService(checker));

        ApiResponse<Map<String, Object>> response = controller.listEntitlements();

        assertTrue(response.isSuccess());
        Map<String, Object> data = response.getData();
        assertNotNull(data);
        assertEquals(Boolean.FALSE, data.get("enabled"));
        assertEquals(List.of(), data.get("entitlements"));
    }
}
