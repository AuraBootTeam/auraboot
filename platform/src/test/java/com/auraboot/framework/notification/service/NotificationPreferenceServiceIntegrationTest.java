package com.auraboot.framework.notification.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.notification.entity.NotificationPreference;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * NotificationPreferenceService integration tests.
 *
 * <p>Opt-out model: all channels enabled by default unless explicitly disabled.
 * SYSTEM+IN_APP is always forced on.
 *
 * <p>Covers:
 * <ul>
 *   <li>NP-01: isEnabled returns true when no preference record exists</li>
 *   <li>NP-02: updatePreference disables a channel+category</li>
 *   <li>NP-03: isEnabled returns false after opt-out</li>
 *   <li>NP-04: updatePreference re-enables a disabled channel</li>
 *   <li>NP-05: SYSTEM+IN_APP is always enabled regardless of preference</li>
 *   <li>NP-06: getPreferences returns stored preferences for user</li>
 *   <li>NP-07: filterRecipients excludes opted-out users</li>
 * </ul>
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationPreferenceServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private NotificationPreferenceService notificationPreferenceService;

    private final String CHANNEL = "email";
    // Unique per run so data from previous NOT_SUPPORTED runs doesn't interfere
    private final String CATEGORY = "MARKETING-" + System.currentTimeMillis();

    // ==================== NP-01: default enabled ====================

    @Test
    @Order(1)
    @DisplayName("NP-01: isEnabled returns true when no preference record exists (opt-out model)")
    void isEnabled_noRecord_returnsTrue() {
        boolean enabled = notificationPreferenceService.isEnabled(
                getTestUser().getId(), CHANNEL, CATEGORY);

        assertThat(enabled).isTrue();
        log.info("NP-01: default enabled for user={} channel={} category={}", getTestUser().getId(), CHANNEL, CATEGORY);
    }

    @Test
    @Order(2)
    @DisplayName("NP-02: updatePreference disables a channel+category")
    void updatePreference_disablesChannel() {
        assertThatCode(() ->
                notificationPreferenceService.updatePreference(
                        getTestUser().getId(), CHANNEL, CATEGORY, false))
                .doesNotThrowAnyException();

        log.info("NP-02: opted out user={} channel={} category={}", getTestUser().getId(), CHANNEL, CATEGORY);
    }

    @Test
    @Order(3)
    @DisplayName("NP-03: isEnabled returns false after opt-out")
    void isEnabled_afterOptOut_returnsFalse() {
        boolean enabled = notificationPreferenceService.isEnabled(
                getTestUser().getId(), CHANNEL, CATEGORY);

        assertThat(enabled).isFalse();
    }

    @Test
    @Order(4)
    @DisplayName("NP-04: updatePreference re-enables a disabled channel")
    void updatePreference_reEnablesChannel() {
        notificationPreferenceService.updatePreference(
                getTestUser().getId(), CHANNEL, CATEGORY, true);

        boolean enabled = notificationPreferenceService.isEnabled(
                getTestUser().getId(), CHANNEL, CATEGORY);
        assertThat(enabled).isTrue();
    }

    @Test
    @Order(5)
    @DisplayName("NP-05: SYSTEM+IN_APP is always enabled regardless of preference")
    void isEnabled_systemInApp_alwaysTrue() {
        // Opt out from SYSTEM+IN_APP
        notificationPreferenceService.updatePreference(
                getTestUser().getId(), "in_app", "system", false);

        // Must still be enabled (forced on)
        boolean enabled = notificationPreferenceService.isEnabled(
                getTestUser().getId(), "in_app", "system");
        assertThat(enabled).isTrue();
    }

    @Test
    @Order(6)
    @DisplayName("NP-06: getPreferences returns stored preferences for user")
    void getPreferences_returnsStoredPreferences() {
        List<NotificationPreference> prefs = notificationPreferenceService.getPreferences(
                getTestUser().getId());

        assertThat(prefs).isNotNull();
        // At least the MARKETING/EMAIL and SYSTEM/IN_APP prefs from earlier tests
        assertThat(prefs).isNotEmpty();
    }

    @Test
    @Order(7)
    @DisplayName("NP-07: filterRecipients excludes opted-out users")
    void filterRecipients_excludesOptedOut() {
        // Disable EMAIL+MARKETING for test user
        notificationPreferenceService.updatePreference(
                getTestUser().getId(), CHANNEL, CATEGORY, false);

        List<Long> recipients = List.of(getTestUser().getId());
        List<Long> filtered = notificationPreferenceService.filterRecipients(
                recipients, CHANNEL, CATEGORY);

        assertThat(filtered).doesNotContain(getTestUser().getId());
    }
}
