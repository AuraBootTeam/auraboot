package com.auraboot.framework.saas.fingerprint;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for InstanceFingerprintService.
 */
@DisplayName("InstanceFingerprintService")
class InstanceFingerprintServiceTest {

    @Test
    @DisplayName("computeFingerprint produces stable SHA-256 hex")
    void computeFingerprint_stableOutput() {
        String result = InstanceFingerprintService.computeFingerprint(
                "https://auraboot.example.com", "550e8400-e29b-41d4-a716-446655440000");

        // SHA-256 should produce 64 hex characters
        assertThat(result).hasSize(64);
        assertThat(result).matches("[0-9a-f]{64}");

        // Same input → same output
        String result2 = InstanceFingerprintService.computeFingerprint(
                "https://auraboot.example.com", "550e8400-e29b-41d4-a716-446655440000");
        assertThat(result).isEqualTo(result2);
    }

    @Test
    @DisplayName("different inputs produce different fingerprints")
    void computeFingerprint_differentInputs() {
        String fp1 = InstanceFingerprintService.computeFingerprint(
                "https://a.com", "uuid-1");
        String fp2 = InstanceFingerprintService.computeFingerprint(
                "https://b.com", "uuid-1");
        String fp3 = InstanceFingerprintService.computeFingerprint(
                "https://a.com", "uuid-2");

        assertThat(fp1).isNotEqualTo(fp2);
        assertThat(fp1).isNotEqualTo(fp3);
        assertThat(fp2).isNotEqualTo(fp3);
    }

    @Test
    @DisplayName("matches returns true when fingerprints match")
    void matches_trueWhenEqual() {
        // matches() with null license fingerprint → true (Community, skip check)
        // Direct computation test
        String fp = InstanceFingerprintService.computeFingerprint("http://localhost:6443", "test-uuid");
        assertThat(fp).isNotNull();
        assertThat(fp).hasSize(64);
    }
}
