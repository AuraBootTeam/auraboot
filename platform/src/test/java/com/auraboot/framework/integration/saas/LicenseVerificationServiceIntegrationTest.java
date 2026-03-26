package com.auraboot.framework.integration.saas;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.saas.account.service.PlatformAccountService;
import com.auraboot.framework.saas.license.service.LicenseVerificationService;
import com.auraboot.framework.saas.license.service.LicenseVerificationService.EditionQuotas;
import com.auraboot.framework.saas.license.service.LicenseVerificationService.LicenseInfo;
import com.auraboot.framework.saas.license.service.impl.LicenseVerificationServiceImpl;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LicenseVerificationServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private LicenseVerificationService licenseVerificationService;

    @Autowired
    private PlatformAccountService platformAccountService;

    private final long ts = System.currentTimeMillis();

    @BeforeEach
    void clearLicenseCache() {
        if (licenseVerificationService instanceof LicenseVerificationServiceImpl impl) {
            ReflectionTestUtils.setField(impl, "cacheExpiry", 0L);
            ReflectionTestUtils.setField(impl, "cachedInfo", null);
        }
    }

    @Test
    @Order(1)
    void verify_shouldReturnCommunityWhenNoAccount() {
        // No platform account exists (rollback isolates each test)
        LicenseInfo info = licenseVerificationService.verify();

        assertThat(info).isNotNull();
        assertThat(info.edition()).isEqualTo("community");
        assertThat(info.valid()).isTrue();
        assertThat(info.error()).isNull();
    }

    @Test
    @Order(2)
    void verify_shouldReturnEditionFromAccount() {
        String email = "test-license-verify-" + ts + "@example.com";
        platformAccountService.create("License Corp", email, "pro");

        LicenseInfo info = licenseVerificationService.verify();

        assertThat(info.edition()).isEqualTo("pro");
        assertThat(info.valid()).isTrue();
    }

    @Test
    @Order(3)
    void getQuotas_shouldReturnCommunityDefaults() {
        // No platform account
        EditionQuotas quotas = licenseVerificationService.getQuotas();

        assertThat(quotas.maxTenants()).isEqualTo(1);
        assertThat(quotas.maxUsersPerTenant()).isEqualTo(5);
        assertThat(quotas.maxStorageGb()).isEqualTo(10);
    }

    @Test
    @Order(4)
    void getQuotas_shouldReturnAccountQuotas() {
        String email = "test-license-quotas-" + ts + "@example.com";
        platformAccountService.create("Quotas Corp", email, "pro");

        EditionQuotas quotas = licenseVerificationService.getQuotas();

        // pro defaults: 3 tenants, 50 users, 100 GB
        assertThat(quotas.maxTenants()).isEqualTo(3);
        assertThat(quotas.maxUsersPerTenant()).isEqualTo(50);
        assertThat(quotas.maxStorageGb()).isEqualTo(100);
    }

    @Test
    @Order(5)
    void hasFeature_shouldBeFalseForCommunity() {
        // No account = community
        assertThat(licenseVerificationService.hasFeature("im")).isFalse();
        assertThat(licenseVerificationService.hasFeature("agent_custom")).isFalse();
    }

    @Test
    @Order(6)
    void hasFeature_shouldBeTrueForPro() {
        String email = "test-license-feature-" + ts + "@example.com";
        platformAccountService.create("Feature Corp", email, "pro");

        assertThat(licenseVerificationService.hasFeature("im")).isTrue();
        assertThat(licenseVerificationService.hasFeature("agent_custom")).isTrue();
    }
}
