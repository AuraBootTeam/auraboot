package com.auraboot.framework.application.web;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrustedClientAddressTest {
    @Test void untrustedPeerIgnoresForgedOrMalformedHeaders() {
        var resolver = new TrustedClientAddress(List.of("10.0.0.0/8"));
        assertThat(resolver.resolve("203.0.113.9", List.of("1.2.3.4"))).isEqualTo("203.0.113.9");
        assertThat(resolver.resolve("203.0.113.9", List.of("malformed", "duplicate"))).isEqualTo("203.0.113.9");
        assertThat(resolver.resolve("203.0.113.9", null)).isEqualTo("203.0.113.9");
    }
    @Test void trustedHopsArePeeledFromTheRightAndUntrustedLeftInputCannotWin() {
        var resolver = new TrustedClientAddress(List.of("10.0.0.0/8", "127.0.0.1/32"));
        assertThat(resolver.resolve("10.0.0.1", List.of("198.51.100.99, 203.0.113.9, 10.0.0.2")))
                .isEqualTo("203.0.113.9");
        assertThat(resolver.resolve("127.0.0.1", List.of("203.0.113.7"))).isEqualTo("203.0.113.7");
        assertThat(resolver.resolve("10.0.0.1", List.of("malformed, 203.0.113.9"))).isEqualTo("203.0.113.9");
    }
    @Test void ipv6AndMappedIpv4PeersUseLiteralCanonicalAddresses() {
        var resolver = new TrustedClientAddress(List.of("2001:db8:1::/48", "127.0.0.1/32"));
        assertThat(resolver.resolve("2001:db8:1::9", List.of("2001:db8:2::7"))).isEqualTo("2001:db8:2:0:0:0:0:7");
        assertThat(resolver.resolve("::ffff:127.0.0.1", List.of("203.0.113.9"))).isEqualTo("203.0.113.9");
    }
    @Test void missingOrAmbiguousTrustedProxyEvidenceFailsClosed() {
        var resolver = new TrustedClientAddress(List.of("127.0.0.1/32"));
        assertThatThrownBy(() -> resolver.resolve("127.0.0.1", null)).isInstanceOf(IllegalArgumentException.class);
        for (var headers : List.of(List.<String>of(), List.of(""), List.of("1.2.3.4", "5.6.7.8"),
                List.of("example.com"), List.of("999.1.2.3"), List.of("1.2.3.4:80"), List.of("1.2.3.4,"),
                List.of("1.2.3.4,".repeat(33)))) {
            assertThatThrownBy(() -> resolver.resolve("127.0.0.1", headers)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> resolver.resolve("localhost", List.of("1.2.3.4"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void proxyConfigurationRejectsHostnamesMalformedMasksAndTrustAll() {
        for (String value : List.of("example.com/24", "0.0.0.0/0", "1.2.3.4/33", "::/129", "127.0.0.1/no", "127.0.0.1/32/32", "")) {
            assertThatThrownBy(() -> new TrustedClientAddress(List.of(value))).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
