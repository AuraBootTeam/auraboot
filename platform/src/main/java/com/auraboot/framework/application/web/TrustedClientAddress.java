package com.auraboot.framework.application.web;

import java.net.InetAddress;
import java.util.List;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

/** Resolve a literal client address from the raw socket peer and explicitly trusted XFF hops. */
public final class TrustedClientAddress {
    private final List<IpAddressMatcher> proxies;

    public TrustedClientAddress(List<String> trustedProxyCidrs) {
        if (trustedProxyCidrs == null) throw invalid();
        this.proxies = trustedProxyCidrs.stream().map(value -> {
            if (value == null) throw invalid();
            String[] parts = value.split("/", -1);
            if (parts.length > 2) throw invalid();
            String host = literal(parts[0]);
            int bits = host.contains(":") ? 128 : 32;
            int prefix;
            try { prefix = parts.length == 1 ? bits : Integer.parseInt(parts[1]); }
            catch (NumberFormatException malformed) { throw invalid(); }
            if (prefix <= 0 || prefix > bits) throw invalid();
            return new IpAddressMatcher(host + "/" + prefix);
        }).toList();
    }

    /** The caller must supply an unmodified socket peer; header-rewriting filters must be disabled. */
    public String resolve(String rawPeer, List<String> forwardedHeaders) {
        String peer = literal(rawPeer);
        if (!trusted(peer)) return peer;
        if (forwardedHeaders == null || forwardedHeaders.size() != 1) throw invalid();
        String header = forwardedHeaders.getFirst();
        if (header == null || header.length() > 2048) throw invalid();
        String[] hops = header.split(",", -1);
        if (hops.length == 0 || hops.length > 32) throw invalid();
        String current = peer;
        for (int index = hops.length - 1; index >= 0; index--) {
            if (!trusted(current)) break;
            current = literal(hops[index].trim());
        }
        return current;
    }

    private boolean trusted(String address) { return proxies.stream().anyMatch(proxy -> proxy.matches(address)); }

    private static String literal(String value) {
        if (value == null || value.length() > 45) throw invalid();
        boolean v4 = value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
        boolean v6 = value.contains(":") && value.matches("[0-9a-fA-F:.]+");
        if (!v4 && !v6) throw invalid();
        if (v4 && java.util.Arrays.stream(value.split("\\.")).anyMatch(part -> Integer.parseInt(part) > 255)) throw invalid();
        try { return InetAddress.getByName(value).getHostAddress(); }
        catch (java.net.UnknownHostException malformed) { throw invalid(); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Client address cannot be verified"); }
}
