package com.auraboot.framework.openplatform.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenPlatformSecretCodecTest {
    private final OpenPlatformSecretCodec codec = new OpenPlatformSecretCodec();

    @Test
    void createsPurposePrefixedNonRepeatingSecretsAndStableHashes() {
        String first = codec.newAccessToken();
        String second = codec.newAccessToken();

        assertTrue(first.startsWith("ab_at_"));
        assertNotEquals(first, second);
        assertEquals(64, codec.sha256(first).length());
        assertEquals(codec.sha256(first), codec.sha256(first));
        assertNotEquals(codec.sha256(first), codec.sha256(second));
    }
}
