package com.auraboot.framework.billing.catalog;

import com.auraboot.framework.billing.catalog.model.MeteringMode;
import com.auraboot.framework.billing.catalog.model.QuotaMode;
import com.auraboot.framework.billing.catalog.model.ResourceCatalog;
import com.auraboot.framework.billing.catalog.model.ResourceCategory;
import com.auraboot.framework.billing.catalog.spi.ResourceCatalogService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration test for {@link ResourceCatalogService} — verifies that all 13 seed
 * resources are present, findByCode works, isRegistered works, and conversionFactor
 * returns BigDecimal.ONE for resources with no explicit factor.
 *
 * <p>Runs against the real test DB (migration-applied schema via
 * {@code @ActiveProfiles("integration-test")} in {@link BaseIntegrationTest}).
 * Inherits {@code @Transactional @Rollback(true)} for isolation.
 */
class ResourceCatalogServiceIntegrationTest extends BaseIntegrationTest {

    /** All 13 standard resource codes defined in the seed migration. */
    private static final Set<String> EXPECTED_CODES = Set.of(
            "APP_COUNT",
            "FORM_COUNT",
            "WORKFLOW_EXECUTION",
            "AI_TOKEN",
            "AI_COPILOT_CALL",
            "KNOWLEDGE_RETRIEVAL",
            "API_CALL",
            "STORAGE_GB",
            "SEAT",
            "AUDIT_RETENTION_DAY",
            "PLUGIN_CALL",
            "INSTANCE_COUNT",
            "NODE_COUNT"
    );

    @Autowired
    private ResourceCatalogService resourceCatalogService;

    @Test
    void listActive_returns_all_13_seed_resources() {
        List<ResourceCatalog> active = resourceCatalogService.listActive();
        Set<String> actualCodes = active.stream()
                .map(ResourceCatalog::getResourceCode)
                .collect(Collectors.toSet());

        assertThat(actualCodes).containsAll(EXPECTED_CODES);
        assertThat(active.size()).isGreaterThanOrEqualTo(13);
    }

    @Test
    void findByCode_returns_correct_entry_for_AI_TOKEN() {
        Optional<ResourceCatalog> result = resourceCatalogService.findByCode("AI_TOKEN");

        assertThat(result).isPresent();
        ResourceCatalog entry = result.get();
        assertThat(entry.getResourceCode()).isEqualTo("AI_TOKEN");
        assertThat(entry.getUnit()).isEqualTo("TOKEN");
        assertThat(entry.getCategory()).isEqualTo(ResourceCategory.AI.name());
        assertThat(entry.getMeteringMode()).isEqualTo(MeteringMode.EVENT.name());
        assertThat(entry.getQuotaMode()).isEqualTo(QuotaMode.PERIODIC.name());
        assertThat(entry.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void findByCode_returns_empty_for_unknown_code() {
        Optional<ResourceCatalog> result = resourceCatalogService.findByCode("NONEXISTENT_XYZ");
        assertThat(result).isEmpty();
    }

    @Test
    void isRegistered_returns_true_for_all_seed_resources() {
        for (String code : EXPECTED_CODES) {
            assertThat(resourceCatalogService.isRegistered(code))
                    .as("isRegistered(%s)", code)
                    .isTrue();
        }
    }

    @Test
    void isRegistered_returns_false_for_unknown_code() {
        assertThat(resourceCatalogService.isRegistered("NOT_A_REAL_RESOURCE")).isFalse();
    }

    @Test
    void conversionFactor_returns_ONE_when_null_in_db() {
        // All seed resources have NULL conversion_factor → must return BigDecimal.ONE
        for (String code : EXPECTED_CODES) {
            BigDecimal factor = resourceCatalogService.conversionFactor(code);
            assertThat(factor)
                    .as("conversionFactor(%s)", code)
                    .isEqualByComparingTo(BigDecimal.ONE);
        }
    }

    @Test
    void conversionFactor_throws_for_unknown_code() {
        assertThatThrownBy(() -> resourceCatalogService.conversionFactor("NO_SUCH_RESOURCE"))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessageContaining("NO_SUCH_RESOURCE");
    }

    @Test
    void all_seed_resources_have_valid_category_matching_enum() {
        Set<String> validCategories = java.util.Arrays.stream(ResourceCategory.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        List<ResourceCatalog> active = resourceCatalogService.listActive();
        for (ResourceCatalog entry : active) {
            if (EXPECTED_CODES.contains(entry.getResourceCode())) {
                assertThat(validCategories)
                        .as("category of %s must be a valid ResourceCategory", entry.getResourceCode())
                        .contains(entry.getCategory());
            }
        }
    }

    @Test
    void all_seed_resources_have_valid_metering_mode_matching_enum() {
        Set<String> validModes = java.util.Arrays.stream(MeteringMode.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        List<ResourceCatalog> active = resourceCatalogService.listActive();
        for (ResourceCatalog entry : active) {
            if (EXPECTED_CODES.contains(entry.getResourceCode())) {
                assertThat(validModes)
                        .as("metering_mode of %s must be a valid MeteringMode", entry.getResourceCode())
                        .contains(entry.getMeteringMode());
            }
        }
    }

    @Test
    void all_seed_resources_have_valid_quota_mode_matching_enum() {
        Set<String> validModes = java.util.Arrays.stream(QuotaMode.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        List<ResourceCatalog> active = resourceCatalogService.listActive();
        for (ResourceCatalog entry : active) {
            if (EXPECTED_CODES.contains(entry.getResourceCode())) {
                assertThat(validModes)
                        .as("quota_mode of %s must be a valid QuotaMode", entry.getResourceCode())
                        .contains(entry.getQuotaMode());
            }
        }
    }

    @Test
    void SEAT_is_in_ACCOUNT_category_SNAPSHOT_STOCK() {
        ResourceCatalog seat = resourceCatalogService.findByCode("SEAT").orElseThrow();
        assertThat(seat.getCategory()).isEqualTo(ResourceCategory.ACCOUNT.name());
        assertThat(seat.getMeteringMode()).isEqualTo(MeteringMode.SNAPSHOT.name());
        assertThat(seat.getQuotaMode()).isEqualTo(QuotaMode.STOCK.name());
        assertThat(seat.getUnit()).isEqualTo("COUNT");
    }

    @Test
    void INSTANCE_COUNT_is_in_LICENSE_category_HEARTBEAT_LICENSE() {
        ResourceCatalog instance = resourceCatalogService.findByCode("INSTANCE_COUNT").orElseThrow();
        assertThat(instance.getCategory()).isEqualTo(ResourceCategory.LICENSE.name());
        assertThat(instance.getMeteringMode()).isEqualTo(MeteringMode.HEARTBEAT.name());
        assertThat(instance.getQuotaMode()).isEqualTo(QuotaMode.LICENSE.name());
    }
}
