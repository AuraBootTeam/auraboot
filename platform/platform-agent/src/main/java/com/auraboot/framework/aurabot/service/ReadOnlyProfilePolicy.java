package com.auraboot.framework.aurabot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Review D2 (2026-07-19, owner-approved): profiles whose turns must never
 * expose write-capable tools, regardless of what the triage bucket or the
 * resolved catalog say. This is the hard half of the G11 ruling — a
 * support-chat style profile is a <b>tool-scope</b> constraint, and routing
 * buckets are never a security boundary (proven repeatedly by this review).
 *
 * <p>Deliberately a separate property from the triage-layer
 * {@code aurabot.triage.light-profiles}: that one shapes ROUTING, this one
 * shapes the TOOL ENVELOPE. Keeping them independent is the point — the
 * envelope cap holds even if routing misclassifies.
 */
@Component
public class ReadOnlyProfilePolicy {

    static final String DEFAULT_READ_ONLY_PROFILES = "support_chat";

    private final Set<String> readOnlyProfiles;

    public ReadOnlyProfilePolicy(
            @Value("${aurabot.policy.read-only-profiles:" + DEFAULT_READ_ONLY_PROFILES + "}") List<String> profiles) {
        this.readOnlyProfiles = profiles == null ? Set.of() : profiles.stream()
                .filter(p -> p != null && !p.isBlank())
                .map(p -> p.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Manual-construction/test convenience: defaults to {@value #DEFAULT_READ_ONLY_PROFILES}. */
    public ReadOnlyProfilePolicy() {
        this(List.of(DEFAULT_READ_ONLY_PROFILES));
    }

    public boolean isReadOnlyProfile(String profileId) {
        return profileId != null && readOnlyProfiles.contains(profileId.toLowerCase(Locale.ROOT));
    }
}
