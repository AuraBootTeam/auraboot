package com.auraboot.framework.openplatform.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenApiProtocolTokenCodecTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-14T08:00:00Z"), ZoneOffset.UTC);
    private final OpenApiProtocolTokenCodec codec = new OpenApiProtocolTokenCodec(
            "unit-test-open-platform-signing-key-at-least-32-bytes", "test", clock);

    @Test
    void cursorIsOpaqueAuthenticatedAndBoundToResourceSchema() {
        String cursor = codec.encodeCursor("assets", 1, "asset-public-pid");
        assertThat(cursor).doesNotContain("asset-public-pid");
        assertThat(codec.decodeCursor(cursor, "assets", 1)).isEqualTo("asset-public-pid");
        assertThatThrownBy(() -> codec.decodeCursor(cursor, "inventory.stock-ins", 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("resource schema");
        String tampered = cursor.substring(0, cursor.length() - 1) + (cursor.endsWith("A") ? "B" : "A");
        assertThatThrownBy(() -> codec.decodeCursor(tampered, "assets", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cursorExpiresAndEtagBindsResourcePidAndVersion() {
        String cursor = codec.encodeCursor("assets", 1, "asset-1");
        OpenApiProtocolTokenCodec expiredCodec = new OpenApiProtocolTokenCodec(
                "unit-test-open-platform-signing-key-at-least-32-bytes", "test",
                Clock.fixed(Instant.parse("2026-09-15T08:00:01Z"), ZoneOffset.UTC));
        assertThatThrownBy(() -> expiredCodec.decodeCursor(cursor, "assets", 1))
                .hasMessageContaining("expired");

        String etag = codec.encodeEtag("assets", "asset-1", 7);
        assertThat(etag).startsWith("\"").endsWith("\"");
        assertThat(codec.decodeEtag(etag, "assets", "asset-1")).isEqualTo(7);
        assertThatThrownBy(() -> codec.decodeEtag(etag, "assets", "asset-2"))
                .isInstanceOf(OpenApiPreconditionException.class);
        assertThatThrownBy(() -> codec.decodeEtag("W/" + etag, "assets", "asset-1"))
                .isInstanceOf(OpenApiPreconditionException.class);
    }
}
