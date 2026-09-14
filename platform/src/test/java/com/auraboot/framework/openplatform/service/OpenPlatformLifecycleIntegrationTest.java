package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.CreateApplicationRequest;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.InstallApplicationRequest;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.UpdateInstallationScopesRequest;
import com.auraboot.framework.openplatform.mapper.OpenPlatformAuthMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenPlatformLifecycleIntegrationTest extends BaseIntegrationTest {

    @Autowired private OpenPlatformManagementService managementService;
    @Autowired private OpenPlatformTokenService tokenService;
    @Autowired private OpenPlatformAuthMapper authMapper;
    @Autowired private OpenPlatformSecretCodec secretCodec;

    @Test
    void credentialScopeAndInstallationLifecycleRevokeTokensImmediately() {
        var application = managementService.createApplication(
                new CreateApplicationRequest("ERP lifecycle", "real PostgreSQL integration fixture"));
        var installation = managementService.install(application.pid(), new InstallApplicationRequest(
                "production", Set.of("openapi.profile.read", "automation.events.write"), 1200));
        var credential = managementService.createCredential(installation.pid());
        var token = tokenService.issue("client_credentials", credential.clientId(),
                credential.clientSecret(), "openapi.profile.read");

        var authenticated = authMapper.findToken(secretCodec.sha256(token.accessToken()),
                OpenPlatformTokenService.AUDIENCE, Instant.now());
        assertEquals(installation.pid(), authenticated.installationPid());
        assertEquals(1200, authenticated.rateLimitPerMinute());

        managementService.updateScopes(installation.pid(),
                new UpdateInstallationScopesRequest(Set.of("automation.events.write")));
        assertNull(authMapper.findToken(secretCodec.sha256(token.accessToken()),
                OpenPlatformTokenService.AUDIENCE, Instant.now()));
        assertThrows(OpenPlatformTokenService.InvalidScopeException.class, () -> tokenService.issue(
                "client_credentials", credential.clientId(), credential.clientSecret(),
                "openapi.profile.read"));

        var replacement = tokenService.issue("client_credentials", credential.clientId(),
                credential.clientSecret(), "automation.events.write");
        managementService.disableInstallation(installation.pid());
        assertNull(authMapper.findToken(secretCodec.sha256(replacement.accessToken()),
                OpenPlatformTokenService.AUDIENCE, Instant.now()));
        assertThrows(OpenPlatformTokenService.InvalidClientException.class, () -> tokenService.issue(
                "client_credentials", credential.clientId(), credential.clientSecret(),
                "automation.events.write"));
    }

    @Test
    void credentialAndApplicationDisableCascadeAcrossActiveTokens() {
        var application = managementService.createApplication(
                new CreateApplicationRequest("Warehouse lifecycle", null));
        var firstInstallation = managementService.install(application.pid(),
                new InstallApplicationRequest("development", Set.of("openapi.profile.read"), 600));
        var firstCredential = managementService.createCredential(firstInstallation.pid());
        var firstToken = tokenService.issue("client_credentials", firstCredential.clientId(),
                firstCredential.clientSecret(), "openapi.profile.read");
        managementService.revokeCredential(firstInstallation.pid(), firstCredential.credentialPid());
        assertNull(authMapper.findToken(secretCodec.sha256(firstToken.accessToken()),
                OpenPlatformTokenService.AUDIENCE, Instant.now()));

        var secondInstallation = managementService.install(application.pid(),
                new InstallApplicationRequest("staging", Set.of("openapi.profile.read"), 60));
        var secondCredential = managementService.createCredential(secondInstallation.pid());
        var secondToken = tokenService.issue("client_credentials", secondCredential.clientId(),
                secondCredential.clientSecret(), "openapi.profile.read");
        managementService.disableApplication(application.pid());

        assertNull(authMapper.findToken(secretCodec.sha256(secondToken.accessToken()),
                OpenPlatformTokenService.AUDIENCE, Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> managementService.install(application.pid(),
                new InstallApplicationRequest("production", Set.of("openapi.profile.read"), 600)));
    }
}
