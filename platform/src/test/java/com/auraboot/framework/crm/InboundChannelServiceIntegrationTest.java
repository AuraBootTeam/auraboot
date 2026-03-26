package com.auraboot.framework.crm;

import com.auraboot.framework.crm.dto.InboundChannelCreateRequest;
import com.auraboot.framework.crm.entity.InboundChannel;
import com.auraboot.framework.crm.service.InboundChannelService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for InboundChannelService CRUD lifecycle.
 * Uses NOT_SUPPORTED propagation so data persists between ordered tests.
 *
 * @since 5.3.0
 */
@Slf4j
@DisplayName("Inbound Channel Service Integration Tests (IC-01~IC-07)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InboundChannelServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private InboundChannelService inboundChannelService;

    private final String runId = "ic-" + System.currentTimeMillis();

    // Cross-test state
    private String channelPid;
    private String originalApiKey;

    // ==================== IC-01 ====================

    @Test
    @Order(1)
    @DisplayName("IC-01: create channel persists with PID and API key")
    void ic01_createChannel() {
        InboundChannelCreateRequest request = new InboundChannelCreateRequest();
        request.setName(runId + "-wechat-channel");
        request.setChannelType("generic_webhook");
        request.setConfig(Map.of("hmacSecret", "secret-value-123", "url", "https://example.com"));
        request.setFieldMapping(Map.of("email", "crm_lead_email", "name", "crm_lead_name"));
        request.setRateLimit(120);

        InboundChannel result = inboundChannelService.create(request);

        assertThat(result).isNotNull();
        assertThat(result.getPid()).isNotNull().isNotBlank();
        assertThat(result.getName()).isEqualTo(runId + "-wechat-channel");
        assertThat(result.getChannelType()).isEqualTo("generic_webhook");
        assertThat(result.getApiKey()).isNotNull().isNotBlank();
        assertThat(result.getEnabled()).isTrue();
        assertThat(result.getDeletedFlag()).isFalse();
        assertThat(result.getRateLimit()).isEqualTo(120);

        channelPid = result.getPid();
        originalApiKey = result.getApiKey();
        log.info("IC-01: created channel pid={}, apiKey={}", channelPid, originalApiKey);
    }

    // ==================== IC-02 ====================

    @Test
    @Order(2)
    @DisplayName("IC-02: getByPid returns channel with decrypted config")
    void ic02_getByPid() {
        assertThat(channelPid).as("channelPid must be set by IC-01").isNotNull();

        InboundChannel result = inboundChannelService.getByPid(channelPid);

        assertThat(result).isNotNull();
        assertThat(result.getPid()).isEqualTo(channelPid);
        assertThat(result.getName()).isEqualTo(runId + "-wechat-channel");
        // Config must be present and the sensitive key must be decrypted (not ENC: prefix)
        assertThat(result.getConfig()).isNotNull();
        assertThat(result.getConfig()).doesNotContain("ENC:");
        log.info("IC-02: found channel name={}, config={}", result.getName(), result.getConfig());
    }

    // ==================== IC-03 ====================

    @Test
    @Order(3)
    @DisplayName("IC-03: update channel reflects new name")
    void ic03_updateChannel() {
        assertThat(channelPid).as("channelPid must be set by IC-01").isNotNull();

        InboundChannelCreateRequest updateRequest = new InboundChannelCreateRequest();
        updateRequest.setName(runId + "-updated-channel");
        updateRequest.setChannelType("generic_webhook");
        updateRequest.setConfig(Map.of("hmacSecret", "new-secret-value", "url", "https://example.com/v2"));

        InboundChannel updated = inboundChannelService.update(channelPid, updateRequest);

        assertThat(updated).isNotNull();
        assertThat(updated.getName()).isEqualTo(runId + "-updated-channel");
        // Verify persisted
        InboundChannel fetched = inboundChannelService.getByPid(channelPid);
        assertThat(fetched.getName()).isEqualTo(runId + "-updated-channel");
        log.info("IC-03: updated channel name={}", updated.getName());
    }

    // ==================== IC-04 ====================

    @Test
    @Order(4)
    @DisplayName("IC-04: disable sets enabled=false")
    void ic04_disableChannel() {
        assertThat(channelPid).as("channelPid must be set by IC-01").isNotNull();

        inboundChannelService.disable(channelPid);

        InboundChannel result = inboundChannelService.getByPid(channelPid);
        assertThat(result).isNotNull();
        assertThat(result.getEnabled()).isFalse();
        log.info("IC-04: channel disabled, enabled={}", result.getEnabled());
    }

    // ==================== IC-05 ====================

    @Test
    @Order(5)
    @DisplayName("IC-05: regenerateApiKey produces a different key")
    void ic05_regenerateApiKey() {
        assertThat(channelPid).as("channelPid must be set by IC-01").isNotNull();
        assertThat(originalApiKey).as("originalApiKey must be set by IC-01").isNotNull();

        InboundChannel updated = inboundChannelService.regenerateApiKey(channelPid);

        assertThat(updated).isNotNull();
        assertThat(updated.getApiKey()).isNotNull().isNotBlank();
        assertThat(updated.getApiKey()).isNotEqualTo(originalApiKey);
        log.info("IC-05: regenerated API key, old={}, new={}", originalApiKey, updated.getApiKey());
    }

    // ==================== IC-06 ====================

    @Test
    @Order(6)
    @DisplayName("IC-06: listByTenant returns all non-deleted channels")
    void ic06_listByTenant() {
        // Create a second channel
        InboundChannelCreateRequest request = new InboundChannelCreateRequest();
        request.setName(runId + "-second-channel");
        request.setChannelType("email_imap");
        request.setConfig(Map.of("host", "imap.example.com", "password", "pass123"));
        InboundChannel second = inboundChannelService.create(request);
        assertThat(second).isNotNull();

        List<InboundChannel> channels = inboundChannelService.listByTenant();

        assertThat(channels).isNotNull();
        assertThat(channels).extracting(InboundChannel::getPid)
                .contains(channelPid, second.getPid());
        log.info("IC-06: listByTenant returned {} channels", channels.size());
    }

    // ==================== IC-07 ====================

    @Test
    @Order(7)
    @DisplayName("IC-07: softDelete removes channel from list and getByPid")
    void ic07_softDelete() {
        assertThat(channelPid).as("channelPid must be set by IC-01").isNotNull();

        inboundChannelService.softDelete(channelPid);

        // Should not be returned by getByPid
        InboundChannel deleted = inboundChannelService.getByPid(channelPid);
        assertThat(deleted).isNull();

        // Should not appear in list
        List<InboundChannel> channels = inboundChannelService.listByTenant();
        assertThat(channels).extracting(InboundChannel::getPid)
                .doesNotContain(channelPid);
        log.info("IC-07: channel soft-deleted, pid={} no longer accessible", channelPid);
    }
}
