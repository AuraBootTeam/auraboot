package com.auraboot.framework.cloudconfig.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.cloudconfig.dto.CloudConfigResponse;
import com.auraboot.framework.cloudconfig.dto.CloudConfigSaveRequest;
import com.auraboot.framework.cloudconfig.entity.CloudConfig;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.auraboot.framework.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack coverage IT for {@link CloudConfigServiceImpl} — tenant-level cloud-config CRUD
 * (save create+update, list, getConfigMasked, getByPidDecrypted, getEnabledProviders,
 * getAllByServiceType, getEffectiveConfig, delete). Dedicated synthetic tenant; raw teardown.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("CloudConfigServiceImpl Coverage IT — cloud-config CRUD")
class CloudConfigServiceImplCoverageIT {

    private static final long TENANT_ID = 990_800_001L;
    private final AtomicLong seq = new AtomicLong();

    @Autowired
    private CloudConfigService cloudConfigService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String serviceType;
    private String providerCode;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, 990_000_007L, "cc-test-pid", "cc-test-user");
        long n = seq.incrementAndGet();
        serviceType = "cctype_" + n;
        providerCode = "ccprov_" + n;
    }

    @AfterAll
    void cleanup() {
        try {
            jdbcTemplate.update("DELETE FROM ab_cloud_config WHERE tenant_id = ?", TENANT_ID);
        } finally {
            MetaContext.clear();
        }
    }

    private CloudConfigSaveRequest saveReq(String pid) {
        CloudConfigSaveRequest r = new CloudConfigSaveRequest();
        r.setPid(pid);
        r.setConfigLevel("tenant");
        r.setServiceType(serviceType);
        r.setProviderCode(providerCode);
        r.setConfig("{\"apiKey\":\"sk-secret-123\"}");
        r.setEnabled(true);
        r.setPriority(1);
        return r;
    }

    @Test
    @DisplayName("save (create) -> list -> masked/decrypted -> effective/enabled/byServiceType -> update -> delete")
    void crud() {
        cloudConfigService.saveConfig(saveReq(null));

        List<CloudConfigResponse> tenantConfigs = cloudConfigService.listConfigs("tenant");
        CloudConfigResponse mine = tenantConfigs.stream()
                .filter(c -> serviceType.equals(c.getServiceType())).findFirst().orElseThrow();
        assertNotNull(mine.getPid());

        // masked vs decrypted
        CloudConfigResponse masked = cloudConfigService.getConfigMasked(mine.getPid());
        assertNotNull(masked);
        CloudConfig decrypted = cloudConfigService.getByPidDecrypted(mine.getPid());
        assertEquals(providerCode, decrypted.getProviderCode());

        // lookups
        assertTrue(cloudConfigService.getEnabledProviders(TENANT_ID, serviceType).stream()
                .anyMatch(c -> providerCode.equals(c.getProviderCode())));
        assertFalse(cloudConfigService.getAllByServiceType(serviceType).isEmpty());
        assertNotNull(cloudConfigService.getEffectiveConfig(TENANT_ID, serviceType, providerCode));

        // update via pid
        CloudConfigSaveRequest upd = saveReq(mine.getPid());
        upd.setPriority(9);
        cloudConfigService.saveConfig(upd);
        assertEquals(9, cloudConfigService.getByPidDecrypted(mine.getPid()).getPriority());

        cloudConfigService.deleteConfig(mine.getPid());
        assertTrue(cloudConfigService.listConfigs("tenant").stream().noneMatch(c -> mine.getPid().equals(c.getPid())));
    }

    @Test
    @DisplayName("save with an unknown pid is rejected")
    void updateUnknownPidRejected() {
        assertThrows(BusinessException.class, () -> cloudConfigService.saveConfig(saveReq("no-such-pid")));
    }
}
