package com.auraboot.framework.email;

import com.auraboot.framework.email.service.EmailSyncService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the static header-parsing helpers in {@link EmailSyncService}.
 *
 * <p>These tests deliberately do <em>not</em> load a Spring context — they exercise
 * the package-private static methods directly to verify correct RFC 5322 header parsing.
 *
 * @since 6.5.0
 */
@DisplayName("EmailSyncService — Header Parsing Unit Tests")
class EmailSyncServiceParsingTest {

    // ══════════════════════════════════════════════════════════════════════════
    // extractEmailAddress
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("SP-01: extractEmailAddress — 'Name <email>' returns email only")
    void sp01_extractEmailAddress_withDisplayName() {
        String result = EmailSyncService.extractEmailAddress("John Doe <john@example.com>");
        assertThat(result).isEqualTo("john@example.com");
    }

    @Test
    @DisplayName("SP-02: extractEmailAddress — plain email returns same value")
    void sp02_extractEmailAddress_plainEmail() {
        String result = EmailSyncService.extractEmailAddress("john@example.com");
        assertThat(result).isEqualTo("john@example.com");
    }

    @Test
    @DisplayName("SP-03: extractEmailAddress — null input returns null")
    void sp03_extractEmailAddress_null() {
        String result = EmailSyncService.extractEmailAddress(null);
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("SP-04: extractEmailAddress — blank string returns null")
    void sp04_extractEmailAddress_blank() {
        String result = EmailSyncService.extractEmailAddress("   ");
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("SP-05: extractEmailAddress — address with spaces around angle brackets")
    void sp05_extractEmailAddress_spacesAroundBrackets() {
        String result = EmailSyncService.extractEmailAddress("  Jane Smith  <jane@corp.io>  ");
        assertThat(result).isEqualTo("jane@corp.io");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // extractDisplayName
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("SP-06: extractDisplayName — 'Name <email>' returns name")
    void sp06_extractDisplayName_withDisplayName() {
        String result = EmailSyncService.extractDisplayName("John Doe <john@example.com>");
        assertThat(result).isEqualTo("John Doe");
    }

    @Test
    @DisplayName("SP-07: extractDisplayName — plain email returns null")
    void sp07_extractDisplayName_plainEmail() {
        String result = EmailSyncService.extractDisplayName("john@example.com");
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("SP-08: extractDisplayName — null returns null")
    void sp08_extractDisplayName_null() {
        String result = EmailSyncService.extractDisplayName(null);
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("SP-09: extractDisplayName — quoted name is unquoted")
    void sp09_extractDisplayName_quotedName() {
        String result = EmailSyncService.extractDisplayName("\"Doe, John\" <john@example.com>");
        assertThat(result).isEqualTo("Doe, John");
    }

    @Test
    @DisplayName("SP-10: extractDisplayName — name with trailing space is trimmed")
    void sp10_extractDisplayName_trimmed() {
        String result = EmailSyncService.extractDisplayName("Alice   <alice@example.com>");
        assertThat(result).isEqualTo("Alice");
    }

    @Test
    @DisplayName("SP-11: extractDisplayName — empty name before angle bracket returns null")
    void sp11_extractDisplayName_emptyName() {
        String result = EmailSyncService.extractDisplayName("<noreply@example.com>");
        assertThat(result).isNull();
    }
}
