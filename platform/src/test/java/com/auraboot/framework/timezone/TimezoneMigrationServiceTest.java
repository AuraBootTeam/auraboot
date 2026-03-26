package com.auraboot.framework.timezone;

import com.auraboot.framework.tenant.service.TenantPreferenceService;
import com.auraboot.framework.timezone.service.TimezoneMigrationService;
import com.auraboot.framework.timezone.service.TimezoneMigrationService.MigrationAssessment;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.DateTimeException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TimezoneMigrationService unit tests")
class TimezoneMigrationServiceTest {

    @Mock
    private TenantPreferenceService tenantPreferenceService;

    private TimezoneMigrationService service;

    @BeforeEach
    void setUp() {
        service = new TimezoneMigrationService(tenantPreferenceService);
    }

    // -----------------------------------------------------------------------
    // assess()
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("assess: same timezone returns NONE impact")
    void assess_sameTimezone_returnsNoneImpact() {
        MigrationAssessment result = service.assess("Asia/Shanghai", "Asia/Shanghai");

        assertThat(result.impact()).isEqualTo("none");
        assertThat(result.offsetDiffMinutes()).isEqualTo(0);
        assertThat(result.fromTimezone()).isEqualTo("Asia/Shanghai");
        assertThat(result.toTimezone()).isEqualTo("Asia/Shanghai");
        assertThat(result.description()).isNotBlank();
        assertThat(result.recommendation()).isNotBlank();
    }

    @Test
    @DisplayName("assess: Shanghai to Tokyo returns MEDIUM impact with +60 minute diff")
    void assess_shanghai_to_tokyo_returnsMediumImpact() {
        // Asia/Shanghai = UTC+8, Asia/Tokyo = UTC+9  => diff = +60 minutes
        MigrationAssessment result = service.assess("Asia/Shanghai", "Asia/Tokyo");

        assertThat(result.impact()).isEqualTo("medium");
        assertThat(result.offsetDiffMinutes()).isEqualTo(60);
        assertThat(result.affectedFieldTypes()).contains("date");
        assertThat(result.description()).containsIgnoringCase("timestamptz");
        assertThat(result.recommendation()).containsIgnoringCase("date");
    }

    @Test
    @DisplayName("assess: Tokyo to Shanghai returns MEDIUM impact with -60 minute diff")
    void assess_tokyo_to_shanghai_returnsMediumImpact() {
        MigrationAssessment result = service.assess("Asia/Tokyo", "Asia/Shanghai");

        assertThat(result.impact()).isEqualTo("medium");
        assertThat(result.offsetDiffMinutes()).isEqualTo(-60);
    }

    @Test
    @DisplayName("assess: UTC to UTC+0 (same effective offset) returns NONE impact")
    void assess_utc_to_gmt_returnsNoneImpact() {
        MigrationAssessment result = service.assess("UTC", "gmt");

        assertThat(result.impact()).isEqualTo("none");
        assertThat(result.offsetDiffMinutes()).isEqualTo(0);
    }

    @Test
    @DisplayName("assess: invalid fromTimezone throws DateTimeException")
    void assess_invalidFromTimezone_throwsException() {
        assertThatThrownBy(() -> service.assess("Not/A/Timezone", "Asia/Tokyo"))
                .isInstanceOf(DateTimeException.class);
    }

    @Test
    @DisplayName("assess: invalid toTimezone throws DateTimeException")
    void assess_invalidToTimezone_throwsException() {
        assertThatThrownBy(() -> service.assess("Asia/Shanghai", "Invalid/Zone"))
                .isInstanceOf(DateTimeException.class);
    }

    @Test
    @DisplayName("assess: large offset difference produces MEDIUM, not LOW")
    void assess_largeOffsetDiff_isMediumNotLow() {
        // Asia/Shanghai (UTC+8) vs America/New_York (UTC-5) => diff = -780 minutes
        MigrationAssessment result = service.assess("Asia/Shanghai", "America/New_York");

        assertThat(result.impact()).isEqualTo("medium");
        assertThat(Math.abs(result.offsetDiffMinutes())).isGreaterThan(60);
    }

    // -----------------------------------------------------------------------
    // updateTimezone()
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("updateTimezone: saves timezone as text node to preference")
    void updateTimezone_savesTextNodeToPreference() {
        Long tenantId = 1L;
        String timezone = "Asia/Tokyo";

        service.updateTimezone(tenantId, timezone);

        ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> captor =
                ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(tenantPreferenceService).setPreference(
                eq(tenantId),
                eq("ui.timezone"),
                captor.capture()
        );
        assertThat(captor.getValue().isTextual()).isTrue();
        assertThat(captor.getValue().asText()).isEqualTo(timezone);
    }

    @Test
    @DisplayName("updateTimezone: invalid timezone throws DateTimeException without saving")
    void updateTimezone_invalidTimezone_throwsExceptionWithoutSaving() {
        assertThatThrownBy(() -> service.updateTimezone(1L, "Not/Valid"))
                .isInstanceOf(DateTimeException.class);

        verifyNoInteractions(tenantPreferenceService);
    }
}
