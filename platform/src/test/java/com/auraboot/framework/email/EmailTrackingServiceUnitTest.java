package com.auraboot.framework.email;

import com.auraboot.framework.email.config.EmailTrackingConfig;
import com.auraboot.framework.email.mapper.EmailTrackingEventMapper;
import com.auraboot.framework.email.model.EmailConstants;
import com.auraboot.framework.email.model.EmailTrackingEvent;
import com.auraboot.framework.email.service.EmailTrackingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link EmailTrackingService} — no DB, no Spring context.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmailTrackingService Unit Tests")
class EmailTrackingServiceUnitTest {

    @Mock
    private EmailTrackingEventMapper trackingEventMapper;

    private EmailTrackingConfig trackingConfig;
    private EmailTrackingService service;

    @BeforeEach
    void setUp() {
        trackingConfig = new EmailTrackingConfig();
        trackingConfig.setEnabled(true);
        trackingConfig.setBaseUrl("https://track.example.com");
        service = new EmailTrackingService(trackingConfig, trackingEventMapper);
    }

    @Test
    @DisplayName("generateTrackingId returns 32-char hex without dashes and is unique per call")
    void generateTrackingId_uniqueAndFormat() {
        String a = service.generateTrackingId();
        String b = service.generateTrackingId();
        assertThat(a).hasSize(32).doesNotContain("-").matches("[0-9a-f]+");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("injectTracking returns original when tracking is disabled")
    void injectTracking_disabled_returnsOriginal() {
        trackingConfig.setEnabled(false);
        String html = "<html><body><a href=\"https://x.com\">x</a></body></html>";
        assertThat(service.injectTracking(html, "tid")).isEqualTo(html);
    }

    @Test
    @DisplayName("injectTracking returns original when html is null")
    void injectTracking_nullHtml() {
        assertThat(service.injectTracking(null, "tid")).isNull();
    }

    @Test
    @DisplayName("injectTracking returns original when html is blank")
    void injectTracking_blankHtml() {
        assertThat(service.injectTracking("   ", "tid")).isEqualTo("   ");
    }

    @Test
    @DisplayName("injectTracking rewrites href links and inserts pixel before </body>")
    void injectTracking_happyPath() {
        String html = "<html><body><a href=\"https://example.com/x?y=1\">link</a></body></html>";
        String result = service.injectTracking(html, "TID123");
        assertThat(result)
                .contains("https://track.example.com/api/email/tracking/TID123/click?url=")
                .contains("https%3A%2F%2Fexample.com%2Fx%3Fy%3D1")
                .contains("https://track.example.com/api/email/tracking/TID123/open.gif")
                .endsWith("</body></html>");
        // Pixel must be before closing body tag
        int pixelIdx = result.indexOf("open.gif");
        int bodyClose = result.lastIndexOf("</body>");
        assertThat(pixelIdx).isLessThan(bodyClose);
    }

    @Test
    @DisplayName("injectTracking appends pixel at end when no </body> tag")
    void injectTracking_noBodyTag() {
        String html = "<div>plain</div>";
        String result = service.injectTracking(html, "T1");
        assertThat(result).startsWith(html).contains("open.gif");
    }

    @Test
    @DisplayName("injectTracking preserves single-quote href delimiters")
    void injectTracking_singleQuoteHref() {
        String html = "<body><a href='https://a.com'>a</a></body>";
        String result = service.injectTracking(html, "T2");
        assertThat(result).contains("href='https://track.example.com");
    }

    @Test
    @DisplayName("recordEvent inserts a tracking event with all fields populated")
    void recordEvent_inserts() {
        service.recordEvent("TID", EmailConstants.TRACKING_OPEN, null,
                "1.2.3.4", "UA", 7L, 42L);
        verify(trackingEventMapper).insert(any(EmailTrackingEvent.class));
    }

    @Test
    @DisplayName("recordEvent swallows mapper exception (must not propagate)")
    void recordEvent_swallowsException() {
        doThrow(new RuntimeException("db down"))
                .when(trackingEventMapper).insert(any(EmailTrackingEvent.class));
        // Should not throw
        service.recordEvent("TID", EmailConstants.TRACKING_CLICK, "https://l.x",
                null, null, null, null);
        verify(trackingEventMapper).insert(any(EmailTrackingEvent.class));
    }

    @Test
    @DisplayName("getStats returns openCount and clickCount from mapper")
    void getStats_returnsCounts() {
        when(trackingEventMapper.countByType(99L, EmailConstants.TRACKING_OPEN)).thenReturn(5);
        when(trackingEventMapper.countByType(eq(99L), eq(EmailConstants.TRACKING_CLICK))).thenReturn(3);
        Map<String, Integer> stats = service.getStats(99L);
        assertThat(stats).containsEntry("openCount", 5).containsEntry("clickCount", 3);
    }
}
