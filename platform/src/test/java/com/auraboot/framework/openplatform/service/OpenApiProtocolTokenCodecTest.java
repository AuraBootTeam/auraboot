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
        for (String invalid : new String[]{null, "", "ab2.payload.signature", "ab1.!.!"}) {
            assertThatThrownBy(() -> codec.decodeCursor(invalid, "assets", 1)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> codec.decodeCursor(codec.encodeCursor("assets", 1, ""), "assets", 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("continuation");
        OpenApiProtocolTokenCodec productionWithoutKey = new OpenApiProtocolTokenCodec("", "production", clock);
        assertThatThrownBy(() -> productionWithoutKey.encodeCursor("assets", 1, "asset-1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("signing key is required");
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
        assertThatThrownBy(() -> codec.decodeEtag('"' + cursor + '"', "assets", "asset-1"))
                .isInstanceOf(OpenApiPreconditionException.class);
        for (long invalid : new long[]{0, -1, (long) Integer.MAX_VALUE + 1}) {
            assertThatThrownBy(() -> codec.decodeEtag(codec.encodeEtag("assets", "asset-1", invalid), "assets", "asset-1"))
                    .isInstanceOf(OpenApiPreconditionException.class).hasMessageContaining("version is invalid");
        }
        OpenApiProtocolTokenCodec beforeIssuance = new OpenApiProtocolTokenCodec(
                "unit-test-open-platform-signing-key-at-least-32-bytes", "test",
                Clock.fixed(clock.instant().minusSeconds(301), ZoneOffset.UTC));
        assertThatThrownBy(() -> beforeIssuance.decodeCursor(cursor, "assets", 1)).hasMessageContaining("expired");
    }
}
