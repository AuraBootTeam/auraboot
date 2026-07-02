package com.auraboot.framework.application.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Startup security guard: warns loudly when the {@code test} Spring profile is active.
 *
 * <p>{@code application.yml} bakes in {@code spring.profiles.active=dev,local,test} for
 * local development, and the {@code test} profile enables anonymous test endpoints
 * ({@code TestSeedController}: create tenant + mint JWT) plus the permissive test
 * whitelist ({@code SecurityConfig}). If a deployment forgets to override
 * {@code SPRING_PROFILES_ACTIVE}, that unsafe default would silently expose an anonymous
 * authentication-bypass. The Docker image now defaults to {@code community}; this warner
 * is defense-in-depth so an accidental {@code test} profile is loudly visible in logs.
 */
@Slf4j
@Component
public class ProfileSecurityWarner {

    @Value("${spring.profiles.active:}")
    private String activeProfile;

    @EventListener(ApplicationReadyEvent.class)
    public void warnOnTestProfile() {
        if (isTestProfileActive(activeProfile)) {
            log.warn("================================ SECURITY ================================");
            log.warn("The 'test' Spring profile is ACTIVE (spring.profiles.active={}).", activeProfile);
            log.warn("This exposes anonymous test endpoints (TestSeedController: create tenant +");
            log.warn("mint JWT) and a permissive test whitelist. It MUST NOT run in production.");
            log.warn("Set SPRING_PROFILES_ACTIVE to a production profile (e.g. 'community').");
            log.warn("=========================================================================");
        }
    }

    /**
     * True when the comma-separated active-profile string contains {@code test}
     * (case-insensitive). Package-private for direct unit testing.
     */
    static boolean isTestProfileActive(String activeProfile) {
        if (activeProfile == null || activeProfile.isBlank()) {
            return false;
        }
        for (String p : activeProfile.split(",")) {
            if ("test".equalsIgnoreCase(p.trim())) {
                return true;
            }
        }
        return false;
    }
}
