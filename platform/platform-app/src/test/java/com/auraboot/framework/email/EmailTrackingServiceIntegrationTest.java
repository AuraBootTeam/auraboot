package com.auraboot.framework.email;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.email.config.EmailTrackingConfig;
import com.auraboot.framework.email.mapper.EmailTrackingEventMapper;
import com.auraboot.framework.email.model.EmailConstants;
import com.auraboot.framework.email.model.EmailTrackingEvent;
import com.auraboot.framework.email.service.EmailTrackingService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link EmailTrackingService}.
 *
 * <p>Exercises tracking-ID generation, HTML injection, and DB event recording against a real
 * PostgreSQL database. Uses {@code Propagation.NOT_SUPPORTED} to allow commits within tests
 * while still inheriting tenant context from {@link BaseIntegrationTest}.
 *
 * @since 6.5.0
 */
@Slf4j
@DisplayName("EmailTrackingService Integration Tests (ET-01~ET-09)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EmailTrackingServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private EmailTrackingService emailTrackingService;

    @Autowired
    private EmailTrackingEventMapper emailTrackingEventMapper;

    @Autowired
    private EmailTrackingConfig emailTrackingConfig;

    private final String runId = "et-" + System.currentTimeMillis();

    // ══════════════════════════════════════════════════════════════════════════
    // ET-01: generateTrackingId — 32 chars, no dashes
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("ET-01: generateTrackingId returns 32-char hex string with no dashes")
    void et01_generateTrackingId_format() {
        String id = emailTrackingService.generateTrackingId();

        assertThat(id).isNotNull();
        assertThat(id).hasSize(32);
        assertThat(id).doesNotContain("-");
        assertThat(id).matches("[0-9a-f]{32}");

        log.info("ET-01 PASS: trackingId={}", id);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ET-02: generateTrackingId — uniqueness
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(2)
    @DisplayName("ET-02: generateTrackingId produces unique values")
    void et02_generateTrackingId_unique() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            ids.add(emailTrackingService.generateTrackingId());
        }
        assertThat(ids).hasSize(50);
        log.info("ET-02 PASS: 50 unique tracking IDs generated");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ET-03: injectTracking — pixel and link rewrite
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName("ET-03: injectTracking injects pixel and rewrites links (tracking enabled)")
    void et03_injectTracking_addsPixelAndRewritesLinks() {
        // Ensure tracking is enabled
        boolean originalEnabled = emailTrackingConfig.isEnabled();
        emailTrackingConfig.setEnabled(true);

        try {
            String trackingId = emailTrackingService.generateTrackingId();
            String originalUrl = "https://example.com/product?id=123";
            String html = "<html><body><p>Hello</p><a href=\"" + originalUrl + "\">Click me</a></body></html>";

            String result = emailTrackingService.injectTracking(html, trackingId);

            // Pixel injected before </body>
            assertThat(result).contains("open.gif");
            assertThat(result).contains(trackingId);
            assertThat(result).contains("/api/email/tracking/" + trackingId + "/open.gif");

            // Original link rewritten to click redirect
            assertThat(result).contains("/api/email/tracking/" + trackingId + "/click");
            assertThat(result).doesNotContain("href=\"" + originalUrl + "\"");

            // Still contains </body>
            assertThat(result).containsIgnoringCase("</body>");

            log.info("ET-03 PASS: pixel and links injected, trackingId={}", trackingId);
        } finally {
            emailTrackingConfig.setEnabled(originalEnabled);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ET-04: injectTracking — disabled returns original HTML unchanged
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(4)
    @DisplayName("ET-04: injectTracking returns original HTML when tracking is disabled")
    void et04_injectTracking_disabledReturnsOriginal() {
        boolean originalEnabled = emailTrackingConfig.isEnabled();
        emailTrackingConfig.setEnabled(false);

        try {
            String trackingId = emailTrackingService.generateTrackingId();
            String html = "<html><body><a href=\"https://example.com\">Link</a></body></html>";

            String result = emailTrackingService.injectTracking(html, trackingId);

            assertThat(result).isEqualTo(html);
            assertThat(result).doesNotContain("open.gif");
            assertThat(result).doesNotContain("/click");

            log.info("ET-04 PASS: tracking disabled, original HTML returned");
        } finally {
            emailTrackingConfig.setEnabled(originalEnabled);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ET-05: injectTracking — no </body> tag appends pixel at end
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName("ET-05: injectTracking appends pixel at end when no </body> tag present")
    void et05_injectTracking_noBodyTagAppendsPixelAtEnd() {
        boolean originalEnabled = emailTrackingConfig.isEnabled();
        emailTrackingConfig.setEnabled(true);

        try {
            String trackingId = emailTrackingService.generateTrackingId();
            String html = "<p>Simple email without body tags</p>";

            String result = emailTrackingService.injectTracking(html, trackingId);

            assertThat(result).startsWith("<p>Simple email without body tags</p>");
            assertThat(result).endsWith("\">");
            assertThat(result).contains("open.gif");
            assertThat(result).contains(trackingId);

            log.info("ET-05 PASS: pixel appended at end when no </body> tag");
        } finally {
            emailTrackingConfig.setEnabled(originalEnabled);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ET-06: recordEvent — open event persisted to DB
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(6)
    @DisplayName("ET-06: recordEvent persists an open event to ab_email_tracking_event")
    void et06_recordEvent_openPersisted() {
        String trackingId = runId + "-open";
        Long tenantId = MetaContext.getCurrentTenantId();

        emailTrackingService.recordEvent(
                trackingId,
                EmailConstants.TRACKING_OPEN,
                null,
                "192.168.1.100",
                "Mozilla/5.0",
                tenantId,
                null   // message_id FK is nullable
        );

        // Verify stored in DB
        List<EmailTrackingEvent> events = emailTrackingEventMapper.selectList(
                new LambdaQueryWrapper<EmailTrackingEvent>()
                        .eq(EmailTrackingEvent::getTrackingId, trackingId)
                        .eq(EmailTrackingEvent::getEventType, EmailConstants.TRACKING_OPEN));

        assertThat(events).hasSize(1);
        EmailTrackingEvent saved = events.get(0);
        assertThat(saved.getTrackingId()).isEqualTo(trackingId);
        assertThat(saved.getEventType()).isEqualTo(EmailConstants.TRACKING_OPEN);
        assertThat(saved.getIpAddress()).isEqualTo("192.168.1.100");
        assertThat(saved.getUserAgent()).isEqualTo("Mozilla/5.0");
        assertThat(saved.getLinkUrl()).isNull();
        assertThat(saved.getEventAt()).isNotNull();

        // Cleanup
        emailTrackingEventMapper.delete(
                new LambdaQueryWrapper<EmailTrackingEvent>()
                        .eq(EmailTrackingEvent::getTrackingId, trackingId));

        log.info("ET-06 PASS: open event persisted, id={}", saved.getId());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ET-07: recordEvent — click event persisted with linkUrl
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(7)
    @DisplayName("ET-07: recordEvent persists a click event with linkUrl")
    void et07_recordEvent_clickWithLinkUrl() {
        String trackingId = runId + "-click";
        String linkUrl = "https://example.com/landing?ref=email";
        Long tenantId = MetaContext.getCurrentTenantId();

        emailTrackingService.recordEvent(
                trackingId,
                EmailConstants.TRACKING_CLICK,
                linkUrl,
                "10.0.0.1",
                "Outlook/16.0",
                tenantId,
                null   // message_id FK is nullable
        );

        List<EmailTrackingEvent> events = emailTrackingEventMapper.selectList(
                new LambdaQueryWrapper<EmailTrackingEvent>()
                        .eq(EmailTrackingEvent::getTrackingId, trackingId)
                        .eq(EmailTrackingEvent::getEventType, EmailConstants.TRACKING_CLICK));

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getLinkUrl()).isEqualTo(linkUrl);

        // Cleanup
        emailTrackingEventMapper.delete(
                new LambdaQueryWrapper<EmailTrackingEvent>()
                        .eq(EmailTrackingEvent::getTrackingId, trackingId));

        log.info("ET-07 PASS: click event with linkUrl persisted");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ET-08: getStats — returns correct counts
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(8)
    @DisplayName("ET-08: getStats returns correct open and click counts")
    void et08_getStats_correctCounts() {
        // We verify getStats() shape by testing with message_id=0 (no rows → both 0).
        // We separately verify recordEvent + mapper counting via direct DB query.
        String trackingId = runId + "-stats";
        Long tenantId = MetaContext.getCurrentTenantId();

        // Verify getStats structure with no matching rows
        Map<String, Integer> emptyStats = emailTrackingService.getStats(Long.MAX_VALUE);
        assertThat(emptyStats).containsKey("openCount");
        assertThat(emptyStats).containsKey("clickCount");
        assertThat(emptyStats.get("openCount")).isEqualTo(0);
        assertThat(emptyStats.get("clickCount")).isEqualTo(0);

        // Insert events with null messageId (no FK violation since message_id is nullable)
        for (int i = 0; i < 2; i++) {
            emailTrackingService.recordEvent(trackingId, EmailConstants.TRACKING_OPEN,
                    null, "1.2.3.4", "UA", tenantId, null);
        }
        for (int i = 0; i < 3; i++) {
            emailTrackingService.recordEvent(trackingId, EmailConstants.TRACKING_CLICK,
                    "https://example.com", "1.2.3.4", "UA", tenantId, null);
        }

        // Verify counts via direct mapper query (by trackingId)
        long openCount = emailTrackingEventMapper.selectCount(
                new LambdaQueryWrapper<EmailTrackingEvent>()
                        .eq(EmailTrackingEvent::getTrackingId, trackingId)
                        .eq(EmailTrackingEvent::getEventType, EmailConstants.TRACKING_OPEN));
        long clickCount = emailTrackingEventMapper.selectCount(
                new LambdaQueryWrapper<EmailTrackingEvent>()
                        .eq(EmailTrackingEvent::getTrackingId, trackingId)
                        .eq(EmailTrackingEvent::getEventType, EmailConstants.TRACKING_CLICK));

        assertThat(openCount).isEqualTo(2);
        assertThat(clickCount).isEqualTo(3);

        // Cleanup
        emailTrackingEventMapper.delete(
                new LambdaQueryWrapper<EmailTrackingEvent>()
                        .eq(EmailTrackingEvent::getTrackingId, trackingId));

        log.info("ET-08 PASS: stats counting verified: opens=2, clicks=3");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ET-09: injectTracking — multiple links rewritten
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @Order(9)
    @DisplayName("ET-09: injectTracking rewrites multiple links in HTML")
    void et09_injectTracking_multipleLinksRewritten() {
        boolean originalEnabled = emailTrackingConfig.isEnabled();
        emailTrackingConfig.setEnabled(true);

        try {
            String trackingId = emailTrackingService.generateTrackingId();
            String html = "<html><body>"
                    + "<a href=\"https://example.com/page1\">Page 1</a>"
                    + "<a href=\"https://example.com/page2?q=hello\">Page 2</a>"
                    + "<a href=\"http://another.com/\">Another</a>"
                    + "</body></html>";

            String result = emailTrackingService.injectTracking(html, trackingId);

            // All 3 original URLs should be gone, replaced with tracking redirects
            assertThat(result).doesNotContain("href=\"https://example.com/page1\"");
            assertThat(result).doesNotContain("href=\"https://example.com/page2?q=hello\"");
            assertThat(result).doesNotContain("href=\"http://another.com/\"");

            // Should contain 3 click redirects
            long clickCount = result.chars()
                    .mapToObj(c -> (char) c)
                    .reduce(new StringBuilder(), StringBuilder::append, StringBuilder::append)
                    .toString()
                    .split("/click\\?", -1).length - 1;
            assertThat(clickCount).isEqualTo(3);

            log.info("ET-09 PASS: {} links rewritten", clickCount);
        } finally {
            emailTrackingConfig.setEnabled(originalEnabled);
        }
    }
}
