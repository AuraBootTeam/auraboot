package com.auraboot.framework.integration.mobile;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.mobile.entity.MobileConfigEntity;
import com.auraboot.framework.mobile.mapper.MobileConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the mobile config endpoint.
 * <p>
 * Covers:
 * - Default config response structure and values
 * - ETag caching (304 Not Modified)
 * - Platform-specific overrides from DB
 * - DB override merging with defaults
 */
@Slf4j
@DisplayName("MobileConfigController Integration Tests")
class MobileConfigControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private MobileConfigMapper mobileConfigMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        // No auth filter needed — /api/mobile/config is a public endpoint
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .build();
    }

    @Test
    @Order(1)
    @DisplayName("MC-01: GET /api/mobile/config returns full config with all sections")
    void getConfig_returnsFullStructure() throws Exception {
        mockMvc.perform(get("/api/mobile/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                // top-level fields
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.minSupportedVersion").value("1.0.0"))
                .andExpect(jsonPath("$.data.latestVersion").value("1.2.0"))
                .andExpect(jsonPath("$.data.forceUpdate").value(false))
                // polling
                .andExpect(jsonPath("$.data.polling.inboxIntervalMs").value(30000))
                .andExpect(jsonPath("$.data.polling.chatIntervalMs").value(10000))
                .andExpect(jsonPath("$.data.polling.syncIntervalMs").value(300000))
                // limits
                .andExpect(jsonPath("$.data.limits.maxUploadMb").value(50))
                .andExpect(jsonPath("$.data.limits.maxAttachmentsPerRecord").value(20))
                .andExpect(jsonPath("$.data.limits.maxOfflineQueueSize").value(100))
                .andExpect(jsonPath("$.data.limits.maxSearchResults").value(50))
                // features
                .andExpect(jsonPath("$.data.features.offlineWrite").value(true))
                .andExpect(jsonPath("$.data.features.aiChat").value(true))
                .andExpect(jsonPath("$.data.features.checkin").value(true))
                .andExpect(jsonPath("$.data.features.signature").value(true))
                .andExpect(jsonPath("$.data.features.barcodeScan").value(true))
                .andExpect(jsonPath("$.data.features.voiceInput").value(false))
                .andExpect(jsonPath("$.data.features.biometricAuth").value(true))
                // ui
                .andExpect(jsonPath("$.data.ui.homeAutoRefreshMinutes").value(5))
                .andExpect(jsonPath("$.data.ui.listPageSize").value(20))
                .andExpect(jsonPath("$.data.ui.maxFavorites").value(10))
                .andExpect(jsonPath("$.data.ui.maxRecentViews").value(20))
                // endpoints (null by default)
                .andExpect(jsonPath("$.data.endpoints.wsUrl").doesNotExist())
                .andExpect(jsonPath("$.data.endpoints.cdnUrl").doesNotExist())
                // maintenance
                .andExpect(jsonPath("$.data.maintenance.enabled").value(false))
                .andExpect(jsonPath("$.data.maintenance.message").doesNotExist())
                .andExpect(jsonPath("$.data.maintenance.estimatedEndTime").doesNotExist());

        log.info("MC-01: full config structure verified with all default values");
    }

    @Test
    @Order(2)
    @DisplayName("MC-02: Response includes ETag header")
    void getConfig_includesETag() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/mobile/config"))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(header().exists(HttpHeaders.CACHE_CONTROL))
                .andReturn();

        String etag = result.getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(etag).isNotNull().startsWith("\"").endsWith("\"");

        log.info("MC-02: ETag header present: {}", etag);
    }

    @Test
    @Order(3)
    @DisplayName("MC-03: If-None-Match with matching ETag returns 304")
    void getConfig_etagMatch_returns304() throws Exception {
        // First request to get the ETag
        MvcResult first = mockMvc.perform(get("/api/mobile/config"))
                .andExpect(status().isOk())
                .andReturn();
        String etag = first.getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(etag).isNotNull();

        // Second request with If-None-Match
        mockMvc.perform(get("/api/mobile/config")
                        .header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified());

        log.info("MC-03: 304 Not Modified returned for matching ETag");
    }

    @Test
    @Order(4)
    @DisplayName("MC-04: If-None-Match with stale ETag returns 200 with new data")
    void getConfig_staleEtag_returns200() throws Exception {
        mockMvc.perform(get("/api/mobile/config")
                        .header(HttpHeaders.IF_NONE_MATCH, "\"stale-etag-value\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.version").isNumber());

        log.info("MC-04: 200 returned for stale ETag");
    }

    @Test
    @Order(5)
    @DisplayName("MC-05: DB override merges on top of defaults")
    void getConfig_dbOverride_mergesWithDefaults() throws Exception {
        // Insert a DB override for polling interval
        MobileConfigEntity override = new MobileConfigEntity();
        override.setConfigKey("polling.inboxIntervalMs");
        override.setConfigValue("60000");
        override.setDescription("Test override");
        override.setUpdatedAt(Instant.now());
        mobileConfigMapper.insert(override);

        mockMvc.perform(get("/api/mobile/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.polling.inboxIntervalMs").value(60000))
                // Other defaults remain unchanged
                .andExpect(jsonPath("$.data.polling.chatIntervalMs").value(10000))
                .andExpect(jsonPath("$.data.limits.maxUploadMb").value(50));

        log.info("MC-05: DB override merged correctly, other defaults preserved");
    }

    @Test
    @Order(6)
    @DisplayName("MC-06: Platform-specific override applies only for matching platform")
    void getConfig_platformOverride_appliesSelectively() throws Exception {
        // Insert iOS-specific override
        MobileConfigEntity iosOverride = new MobileConfigEntity();
        iosOverride.setConfigKey("features.voiceInput");
        iosOverride.setConfigValue("true");
        iosOverride.setPlatform("ios");
        iosOverride.setDescription("iOS voice input enabled");
        iosOverride.setUpdatedAt(Instant.now());
        mobileConfigMapper.insert(iosOverride);

        // iOS should see voiceInput=true
        mockMvc.perform(get("/api/mobile/config").param("platform", "ios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.features.voiceInput").value(true));

        // Android should see voiceInput=false (default)
        mockMvc.perform(get("/api/mobile/config").param("platform", "android"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.features.voiceInput").value(false));

        // No platform should see voiceInput=false (default)
        mockMvc.perform(get("/api/mobile/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.features.voiceInput").value(false));

        log.info("MC-06: platform-specific override applied selectively");
    }

    @Test
    @Order(7)
    @DisplayName("MC-07: ETag differs between platforms when overrides exist")
    void getConfig_differentPlatforms_differentEtags() throws Exception {
        // Insert iOS-specific override to create divergence
        MobileConfigEntity override = new MobileConfigEntity();
        override.setConfigKey("ui.listPageSize");
        override.setConfigValue("15");
        override.setPlatform("ios");
        override.setDescription("iOS smaller page size");
        override.setUpdatedAt(Instant.now());
        mobileConfigMapper.insert(override);

        MvcResult iosResult = mockMvc.perform(
                get("/api/mobile/config").param("platform", "ios"))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult androidResult = mockMvc.perform(
                get("/api/mobile/config").param("platform", "android"))
                .andExpect(status().isOk())
                .andReturn();

        String iosEtag = iosResult.getResponse().getHeader(HttpHeaders.ETAG);
        String androidEtag = androidResult.getResponse().getHeader(HttpHeaders.ETAG);

        assertThat(iosEtag).isNotEqualTo(androidEtag);

        log.info("MC-07: different ETags for ios={} vs android={}", iosEtag, androidEtag);
    }

    @Test
    @Order(8)
    @DisplayName("MC-08: Maintenance mode fields render correctly when enabled")
    void getConfig_maintenanceMode_rendersCorrectly() throws Exception {
        // Insert maintenance config overrides
        insertConfig("maintenance.enabled", "true", null);
        insertConfig("maintenance.message", "System upgrade in progress", null);
        insertConfig("maintenance.estimatedEndTime", "2026-03-25T18:00:00Z", null);

        mockMvc.perform(get("/api/mobile/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.maintenance.enabled").value(true))
                .andExpect(jsonPath("$.data.maintenance.message").value("System upgrade in progress"))
                .andExpect(jsonPath("$.data.maintenance.estimatedEndTime").value("2026-03-25T18:00:00Z"));

        log.info("MC-08: maintenance mode fields rendered correctly");
    }

    @Test
    @Order(9)
    @DisplayName("MC-09: Force update flag and version info")
    void getConfig_forceUpdate_rendersCorrectly() throws Exception {
        insertConfig("forceUpdate", "true", null);
        insertConfig("minSupportedVersion", "1.1.0", null);
        insertConfig("latestVersion", "1.3.0", null);

        mockMvc.perform(get("/api/mobile/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.forceUpdate").value(true))
                .andExpect(jsonPath("$.data.minSupportedVersion").value("1.1.0"))
                .andExpect(jsonPath("$.data.latestVersion").value("1.3.0"));

        log.info("MC-09: force update and version info rendered correctly");
    }

    @Test
    @Order(10)
    @DisplayName("MC-10: Endpoint URLs render as non-null when configured")
    void getConfig_endpoints_renderWhenConfigured() throws Exception {
        insertConfig("endpoints.wsUrl", "wss://ws.auraboot.com", null);
        insertConfig("endpoints.cdnUrl", "https://cdn.auraboot.com", null);

        mockMvc.perform(get("/api/mobile/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.endpoints.wsUrl").value("wss://ws.auraboot.com"))
                .andExpect(jsonPath("$.data.endpoints.cdnUrl").value("https://cdn.auraboot.com"));

        log.info("MC-10: endpoint URLs rendered when configured");
    }

    // ---- helper ----

    private void insertConfig(String key, String value, String platform) {
        MobileConfigEntity entity = new MobileConfigEntity();
        entity.setConfigKey(key);
        entity.setConfigValue(value);
        entity.setPlatform(platform);
        entity.setUpdatedAt(Instant.now());
        mobileConfigMapper.insert(entity);
    }
}
