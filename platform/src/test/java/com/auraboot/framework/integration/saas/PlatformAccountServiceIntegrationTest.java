package com.auraboot.framework.integration.saas;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.saas.account.entity.PlatformAccountEntity;
import com.auraboot.framework.saas.account.service.PlatformAccountService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlatformAccountServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PlatformAccountService platformAccountService;

    private final long ts = System.currentTimeMillis();

    @Test
    @Order(1)
    void create_shouldCreateAccount() {
        String email = "test-create-" + ts + "@example.com";
        PlatformAccountEntity account = platformAccountService.create("Test Corp", email, "pro");

        assertThat(account).isNotNull();
        assertThat(account.getPid()).isNotBlank();
        assertThat(account.getName()).isEqualTo("Test Corp");
        assertThat(account.getContactEmail()).isEqualTo(email);
        assertThat(account.getEdition()).isEqualTo("pro");
        assertThat(account.getStatus()).isEqualTo("active");
        // pro defaults: 3 tenants, 50 users, 100 GB
        assertThat(account.getMaxTenants()).isEqualTo(3);
        assertThat(account.getMaxUsersPerTenant()).isEqualTo(50);
        assertThat(account.getMaxStorageGb()).isEqualTo(100);
    }

    @Test
    @Order(2)
    void create_shouldDefaultToCommunityEdition() {
        String email = "test-community-" + ts + "@example.com";
        PlatformAccountEntity account = platformAccountService.create("Community Corp", email, null);

        assertThat(account.getEdition()).isEqualTo("community");
        // community defaults: 1 tenant, 5 users, 10 GB
        assertThat(account.getMaxTenants()).isEqualTo(1);
        assertThat(account.getMaxUsersPerTenant()).isEqualTo(5);
        assertThat(account.getMaxStorageGb()).isEqualTo(10);
    }

    @Test
    @Order(3)
    void findByPid_shouldReturnAccount() {
        String email = "test-findpid-" + ts + "@example.com";
        PlatformAccountEntity created = platformAccountService.create("FindPid Corp", email, "pro");

        Optional<PlatformAccountEntity> found = platformAccountService.findByPid(created.getPid());

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("FindPid Corp");
        assertThat(found.get().getContactEmail()).isEqualTo(email);
    }

    @Test
    @Order(4)
    void findByEmail_shouldReturnAccount() {
        String email = "test-findemail-" + ts + "@example.com";
        platformAccountService.create("FindEmail Corp", email, "enterprise");

        Optional<PlatformAccountEntity> found = platformAccountService.findByEmail(email);

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("FindEmail Corp");
        assertThat(found.get().getEdition()).isEqualTo("enterprise");
    }

    @Test
    @Order(5)
    void findByPid_shouldReturnEmptyForNonexistent() {
        Optional<PlatformAccountEntity> found = platformAccountService.findByPid("nonexistent-pid-" + ts);

        assertThat(found).isEmpty();
    }

    @Test
    @Order(6)
    void updateEdition_shouldUpdateQuotas() {
        String email = "test-update-" + ts + "@example.com";
        PlatformAccountEntity created = platformAccountService.create("Update Corp", email, "community");

        // Verify initial community quotas
        assertThat(created.getEdition()).isEqualTo("community");
        assertThat(created.getMaxTenants()).isEqualTo(1);

        // Update to pro with custom quotas
        platformAccountService.updateEdition(created.getPid(), "pro", 5, 100, 200);

        // Re-fetch and verify
        Optional<PlatformAccountEntity> updated = platformAccountService.findByPid(created.getPid());
        assertThat(updated).isPresent();
        assertThat(updated.get().getEdition()).isEqualTo("pro");
        assertThat(updated.get().getMaxTenants()).isEqualTo(5);
        assertThat(updated.get().getMaxUsersPerTenant()).isEqualTo(100);
        assertThat(updated.get().getMaxStorageGb()).isEqualTo(200);
    }
}
