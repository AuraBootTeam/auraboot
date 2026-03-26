package com.auraboot.framework.cloudconfig.service;

import com.auraboot.framework.cloudconfig.dto.CloudConfigResponse;
import com.auraboot.framework.cloudconfig.dto.CloudConfigSaveRequest;
import com.auraboot.framework.cloudconfig.entity.CloudConfig;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Integration tests for CloudConfigService.
 *
 * <p>Tests CRUD lifecycle, encryption/masking of sensitive fields,
 * and config level filtering.
 *
 * <ul>
 *   <li>CC-01: saveConfig creates TENANT-level config with encrypted secretKey</li>
 *   <li>CC-02: getConfigMasked returns masked response with *** for secretKey</li>
 *   <li>CC-03: getByPidDecrypted returns decrypted config</li>
 *   <li>CC-04: listConfigs returns only configs for the current tenant level</li>
 *   <li>CC-05: saveConfig updates existing config when pid is provided</li>
 *   <li>CC-06: saveConfig with invalid pid throws BusinessException</li>
 *   <li>CC-07: deleteConfig removes the config</li>
 *   <li>CC-08: deleteConfig with invalid pid throws BusinessException</li>
 *   <li>CC-09: getEnabledProviders returns only enabled configs for service type</li>
 *   <li>CC-10: getAllByServiceType returns all configs for a given service type</li>
 * </ul>
 */
@Slf4j
@DisplayName("CloudConfigService Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CloudConfigServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private CloudConfigService cloudConfigService;

    // Keep runId short: provider_code and service_type columns are VARCHAR(32)
    private final String runId = "cc" + (System.currentTimeMillis() % 10_000_000L);

    // State shared across ordered tests
    private String createdPid;

    // ==================== CC-01: save TENANT-level config ====================

    @Test
    @Order(1)
    @DisplayName("CC-01: saveConfig creates TENANT-level SMS config with secretKey")
    void CC_01_saveConfig_createsTenantLevelConfig() {
        CloudConfigSaveRequest request = new CloudConfigSaveRequest();
        request.setConfigLevel("tenant");
        request.setServiceType("SMS-" + runId);
        request.setProviderCode("test_provider-" + runId);
        request.setConfig("{\"appId\":\"test123\",\"secretKey\":\"my-secret-" + runId + "\"}");
        request.setEnabled(true);
        request.setPriority(10);

        assertDoesNotThrow(() -> cloudConfigService.saveConfig(request));

        // Retrieve the config to confirm creation
        List<CloudConfigResponse> configs = cloudConfigService.listConfigs("tenant");
        assertThat(configs).isNotNull();
        CloudConfigResponse found = configs.stream()
                .filter(c -> ("test_provider-" + runId).equals(c.getProviderCode()))
                .findFirst()
                .orElse(null);
        assertThat(found).as("Config with providerCode=test_provider-" + runId + " should exist").isNotNull();
        assertThat(found.getPid()).isNotBlank();
        assertThat(found.getEnabled()).isTrue();
        assertThat(found.getPriority()).isEqualTo(10);

        this.createdPid = found.getPid();
        log.info("CC-01: created config pid={}", createdPid);
    }

    // ==================== CC-02: getConfigMasked masks secretKey ====================

    @Test
    @Order(2)
    @DisplayName("CC-02: getConfigMasked returns masked response with *** for secretKey")
    void CC_02_getConfigMasked_masksSensitiveFields() {
        assertThat(createdPid).as("createdPid from CC-01").isNotBlank();

        CloudConfigResponse response = cloudConfigService.getConfigMasked(createdPid);

        assertThat(response).isNotNull();
        assertThat(response.getPid()).isEqualTo(createdPid);
        assertThat(response.getConfig()).isNotNull();
        // secretKey should be masked
        assertThat(response.getConfig()).contains("***");
        // appId should NOT be masked (non-sensitive)
        assertThat(response.getConfig()).contains("test123");
    }

    // ==================== CC-03: getByPidDecrypted returns real value ====================

    @Test
    @Order(3)
    @DisplayName("CC-03: getByPidDecrypted returns decrypted config with real secretKey value")
    void CC_03_getByPidDecrypted_returnsDecryptedConfig() {
        assertThat(createdPid).as("createdPid from CC-01").isNotBlank();

        CloudConfig config = cloudConfigService.getByPidDecrypted(createdPid);

        assertThat(config).isNotNull();
        assertThat(config.getPid()).isEqualTo(createdPid);
        assertThat(config.getConfig()).isNotNull();
        // The decrypted config should contain the original secretKey value
        assertThat(config.getConfig()).contains("my-secret-" + runId);
    }

    // ==================== CC-04: listConfigs returns TENANT configs ====================

    @Test
    @Order(4)
    @DisplayName("CC-04: listConfigs returns only configs at the specified level")
    void CC_04_listConfigs_returnsConfigsForLevel() {
        assertThat(createdPid).as("createdPid from CC-01").isNotBlank();

        List<CloudConfigResponse> tenantConfigs = cloudConfigService.listConfigs("tenant");

        assertThat(tenantConfigs).isNotNull().isNotEmpty();
        // All returned configs should be TENANT level
        assertThat(tenantConfigs).allSatisfy(c -> assertThat(c.getConfigLevel()).isEqualTo("tenant"));
        // Our created config should be in the list
        assertThat(tenantConfigs).anyMatch(c -> c.getPid().equals(createdPid));
    }

    // ==================== CC-05: saveConfig updates existing config ====================

    @Test
    @Order(5)
    @DisplayName("CC-05: saveConfig with existing pid updates the config")
    void CC_05_saveConfig_withPid_updatesExistingConfig() {
        assertThat(createdPid).as("createdPid from CC-01").isNotBlank();

        CloudConfigSaveRequest updateRequest = new CloudConfigSaveRequest();
        updateRequest.setPid(createdPid);
        updateRequest.setConfigLevel("tenant");
        updateRequest.setServiceType("SMS-" + runId);
        updateRequest.setProviderCode("test_prov-" + runId + "-upd");
        updateRequest.setConfig("{\"appId\":\"updated123\",\"secretKey\":\"updated-secret-" + runId + "\"}");
        updateRequest.setEnabled(false);
        updateRequest.setPriority(20);

        assertDoesNotThrow(() -> cloudConfigService.saveConfig(updateRequest));

        // Verify update
        CloudConfigResponse updated = cloudConfigService.getConfigMasked(createdPid);
        assertThat(updated).isNotNull();
        assertThat(updated.getProviderCode()).isEqualTo("test_prov-" + runId + "-upd");
        assertThat(updated.getEnabled()).isFalse();
        assertThat(updated.getPriority()).isEqualTo(20);
    }

    // ==================== CC-06: saveConfig with invalid pid throws ====================

    @Test
    @Order(6)
    @DisplayName("CC-06: saveConfig with non-existent pid throws BusinessException")
    void CC_06_saveConfig_invalidPid_throwsBusinessException() {
        CloudConfigSaveRequest request = new CloudConfigSaveRequest();
        request.setPid("nonexistent-pid-" + runId);
        request.setConfigLevel("tenant");
        request.setServiceType("sms");
        request.setProviderCode("test");
        request.setConfig("{\"key\":\"value\"}");
        request.setEnabled(true);

        assertThatThrownBy(() -> cloudConfigService.saveConfig(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not found");
    }

    // ==================== CC-07: deleteConfig removes config ====================

    @Test
    @Order(7)
    @DisplayName("CC-07: deleteConfig removes the config so getConfigMasked returns null")
    void CC_07_deleteConfig_removesConfig() {
        assertThat(createdPid).as("createdPid from CC-01").isNotBlank();

        cloudConfigService.deleteConfig(createdPid);

        CloudConfigResponse afterDelete = cloudConfigService.getConfigMasked(createdPid);
        assertThat(afterDelete).isNull();
    }

    // ==================== CC-08: deleteConfig invalid pid throws ====================

    @Test
    @Order(8)
    @DisplayName("CC-08: deleteConfig with non-existent pid throws BusinessException")
    void CC_08_deleteConfig_invalidPid_throwsBusinessException() {
        assertThatThrownBy(() -> cloudConfigService.deleteConfig("ghost-pid-" + runId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not found");
    }

    // ==================== CC-09: getEnabledProviders ====================

    @Test
    @Order(9)
    @DisplayName("CC-09: getEnabledProviders returns only enabled configs for service type")
    void CC_09_getEnabledProviders_returnsEnabledOnly() {
        String serviceType = "EMAIL-" + runId;

        // Create enabled config
        CloudConfigSaveRequest enabledReq = new CloudConfigSaveRequest();
        enabledReq.setConfigLevel("tenant");
        enabledReq.setServiceType(serviceType);
        enabledReq.setProviderCode("smtp-enabled-" + runId);
        enabledReq.setConfig("{\"host\":\"smtp.test.com\",\"password\":\"secret123\"}");
        enabledReq.setEnabled(true);
        cloudConfigService.saveConfig(enabledReq);

        // Create disabled config for same service type
        CloudConfigSaveRequest disabledReq = new CloudConfigSaveRequest();
        disabledReq.setConfigLevel("tenant");
        disabledReq.setServiceType(serviceType);
        disabledReq.setProviderCode("smtp-disabled-" + runId);
        disabledReq.setConfig("{\"host\":\"smtp.disabled.com\"}");
        disabledReq.setEnabled(false);
        cloudConfigService.saveConfig(disabledReq);

        List<CloudConfig> enabled = cloudConfigService.getEnabledProviders(
                getTestTenant().getId(), serviceType);

        assertThat(enabled).isNotNull();
        // Only enabled configs should be returned
        assertThat(enabled).allSatisfy(c -> assertThat(c.getEnabled()).isTrue());
        // At least our enabled smtp config
        assertThat(enabled).anyMatch(c -> ("smtp-enabled-" + runId).equals(c.getProviderCode()));
        // Disabled config should not appear
        assertThat(enabled).noneMatch(c -> ("smtp-disabled-" + runId).equals(c.getProviderCode()));
    }

    // ==================== CC-10: getAllByServiceType ====================

    @Test
    @Order(10)
    @DisplayName("CC-10: getAllByServiceType returns all configs (enabled+disabled) for service type")
    void CC_10_getAllByServiceType_returnsAll() {
        String serviceType = "CDN-" + runId;

        CloudConfigSaveRequest r1 = new CloudConfigSaveRequest();
        r1.setConfigLevel("tenant");
        r1.setServiceType(serviceType);
        r1.setProviderCode("cdn-a-" + runId);
        r1.setConfig("{\"endpoint\":\"cdn-a.test.com\"}");
        r1.setEnabled(true);
        cloudConfigService.saveConfig(r1);

        CloudConfigSaveRequest r2 = new CloudConfigSaveRequest();
        r2.setConfigLevel("tenant");
        r2.setServiceType(serviceType);
        r2.setProviderCode("cdn-b-" + runId);
        r2.setConfig("{\"endpoint\":\"cdn-b.test.com\"}");
        r2.setEnabled(false);
        cloudConfigService.saveConfig(r2);

        List<CloudConfig> all = cloudConfigService.getAllByServiceType(serviceType);

        assertThat(all).isNotNull();
        assertThat(all).hasSizeGreaterThanOrEqualTo(2);
        assertThat(all).anyMatch(c -> ("cdn-a-" + runId).equals(c.getProviderCode()));
        assertThat(all).anyMatch(c -> ("cdn-b-" + runId).equals(c.getProviderCode()));
    }
}
