package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.cloudconfig.entity.CloudConfig;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.auraboot.framework.openplatform.entity.ApplicationInstallation;
import com.auraboot.framework.openplatform.mapper.ApplicationInstallationMapper;
import com.auraboot.framework.openplatform.mapper.ApplicationScopeGrantMapper;
import com.auraboot.framework.plugin.extension.integration.*;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Admission/dispatch contract only. Collaborator doubles do not prove persistence, replay or commit. */
class SignedIntegrationCallbackServiceTest {
    final AuraPluginManager plugins = mock(AuraPluginManager.class);
    final InstalledIntegrationRegistry registry = mock(InstalledIntegrationRegistry.class);
    final InstalledIntegrationService installed = mock(InstalledIntegrationService.class);
    final ApplicationInstallationMapper installations = mock(ApplicationInstallationMapper.class);
    final ApplicationScopeGrantMapper scopes = mock(ApplicationScopeGrantMapper.class);
    final CloudConfigService configs = mock(CloudConfigService.class);
    final SignedIntegrationCallbackExtension provider = mock(SignedIntegrationCallbackExtension.class);
    final ObjectMapper json = new ObjectMapper();
    final SignedIntegrationCallbackService service = new SignedIntegrationCallbackService(plugins, registry, installed,
            installations, scopes, configs, json, new OpenApiProtocolHash(json, new OpenPlatformSecretCodec()));
    final String code = "sample-fees", source = "sample-logistics", event = "external.sample-logistics.fee.v1";
    final String digest = "sha256:" + "a".repeat(64);
    final byte[] body = "synthetic-wire".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    final String contentType = "application/x-www-form-urlencoded";
    final SignedIntegrationCallbackExtension.Definition definition = new SignedIntegrationCallbackExtension.Definition(
            code, source, event, "integration", "sample", "callbackSalt", "callbackInstallationPid");
    CloudConfig config;
    ApplicationInstallation installation;

    @BeforeEach void setup() {
        when(plugins.getExtensionsOfType(SignedIntegrationCallbackExtension.class)).thenReturn(List.of(provider));
        when(provider.definitions()).thenReturn(List.of(definition));
        when(plugins.observeLoadedArtifact(provider.getClass())).thenReturn(Optional.of(
                new AuraPluginManager.LoadedPluginArtifact("provider", Path.of("plugin.jar"), digest)));
        when(registry.registration(source)).thenReturn(Optional.of(Map.entry(new ExternalEventIngressExtension.Registration(
                source, "fees.ingress", Set.of(event), "fees.write", "fees.ingress", "provider", "owner",
                "fee_notice:accept", "fee_notice:process", "fee_notice:result"), digest)));
        when(installations.findActiveIdentity(42L, "installation")).thenReturn(new ApplicationInstallationMapper.ActiveIdentity(7L, "app"));
        installation = new ApplicationInstallation(); installation.setId(7L); installation.setPid("installation");
        installation.setTenantId(42L); installation.setStatus("active"); installation.setEnvironment("test");
        when(installations.findByTenantAndPid(42L, "installation")).thenReturn(installation);
        when(scopes.findScopes(42L, 7L)).thenReturn(Set.of("fees.write", "unrelated.scope"));
        config = new CloudConfig(); config.setTenantId(42L); config.setConfigLevel("tenant"); config.setEnabled(true);
        config.setDeletedFlag(false); config.setServiceType("integration"); config.setProviderCode("sample");
        config.setConfig("{\"callbackSalt\":\"synthetic-secret\",\"callbackInstallationPid\":\"installation\"}");
        when(configs.getEffectiveConfig(42L, "integration", "sample")).thenReturn(config);
        when(provider.verify(eq(code), eq(contentType), any(byte[].class), eq("synthetic-secret")))
                .thenReturn(new SignedIntegrationCallbackExtension.Request("fact-123456", "shipment", "shipment-1",
                        Instant.EPOCH, Map.of("amount", "12.345")));
        when(installed.executeSynchronouslyWithReceipt(any(), eq(source), any()))
                .thenReturn(new InstalledIntegrationService.SynchronousResult(Map.of("state", "completed"), false));
        when(provider.acknowledge(eq(code), anyMap())).thenReturn(Map.of("result", true, "returnCode", "200"));
    }

    @AfterEach void clear() { MetaContext.clear(); }
    Map<String,Object> receive() { return service.receive(42L, "installation", code, contentType, body); }

    @Test void verifiedDispatchHasOnlyTheFixedScopeAndNoBearerTokenOrAmbientTenant() {
        MetaContext.setContext(99L, 8L, "unrelated-user", "browser");
        when(installed.executeSynchronouslyWithReceipt(any(), eq(source), any())).thenAnswer(call -> {
            assertTrue(com.auraboot.framework.application.security.MachineDataAccessContext.isActive());
            assertEquals(call.getArgument(0), com.auraboot.framework.application.security.MachineDataAccessContext.principal());
            assertEquals(42L, MetaContext.getCurrentTenantId());
            assertEquals("installation:installation", MetaContext.getCurrentUserPid());
            return new InstalledIntegrationService.SynchronousResult(Map.of("state", "completed"), false);
        });
        assertEquals(Map.of("result", true, "returnCode", "200"), receive());
        var principal = ArgumentCaptor.forClass(MachinePrincipal.class);
        var envelope = ArgumentCaptor.forClass(IntegrationEventEnvelope.class);
        verify(installed).executeSynchronouslyWithReceipt(principal.capture(), eq(source), envelope.capture());
        assertEquals(42L, principal.getValue().tenantId()); assertEquals("app", principal.getValue().applicationPid());
        assertEquals("installation", principal.getValue().installationPid()); assertNull(principal.getValue().tokenPid());
        assertEquals(Set.of("fees.write"), principal.getValue().scopes());
        assertEquals(42L, envelope.getValue().tenantId()); assertEquals("shipment/shipment-1", envelope.getValue().subject());
        assertEquals(Map.of("amount", "12.345"), envelope.getValue().payload());
        assertTrue(envelope.getValue().headers().get("externalEventId").matches("callback-[a-f0-9]{64}"));
        assertEquals(Instant.EPOCH, envelope.getValue().occurredAt());
        assertEquals("open-platform/installation/sample-logistics", envelope.getValue().source());
        assertFalse(com.auraboot.framework.application.security.MachineDataAccessContext.isActive());
        assertEquals(99L, MetaContext.getCurrentTenantId()); assertEquals("unrelated-user", MetaContext.getCurrentUserPid());
    }

    @Test void noActiveIdentityOrCurrentScopeMeansNoSignatureOrBusinessInvocation() {
        when(installations.findActiveIdentity(42L, "installation")).thenReturn(null);
        assertThrows(SecurityException.class, this::receive);
        when(installations.findActiveIdentity(42L, "installation")).thenReturn(new ApplicationInstallationMapper.ActiveIdentity(7L, "app"));
        when(scopes.findScopes(42L, 7L)).thenReturn(Set.of("unrelated.scope"));
        assertThrows(SecurityException.class, this::receive);
        verify(provider, never()).verify(anyString(), any(), any(), any()); verifyNoInteractions(installed);
    }

    @Test void selectedInstallationMustMatchAllServerIdentityFields() {
        for (String field : List.of("tenant", "pid", "status", "id")) {
            setup();
            switch (field) {
                case "tenant" -> installation.setTenantId(43L);
                case "pid" -> installation.setPid("another-installation");
                case "status" -> installation.setStatus("revoked");
                case "id" -> installation.setId(8L);
            }
            assertThrows(SecurityException.class, this::receive, field);
        }
        verifyNoInteractions(installed);
    }

    @Test void platformFallbackForeignTenantAndDisabledConfigsAreNotCallbackCredentials() {
        for (String field : List.of("level", "tenant", "enabled", "deleted", "service", "provider")) {
            setup();
            switch (field) {
                case "level" -> config.setConfigLevel("platform");
                case "tenant" -> config.setTenantId(43L);
                case "enabled" -> config.setEnabled(false);
                case "deleted" -> config.setDeletedFlag(true);
                case "service" -> config.setServiceType("oauth");
                case "provider" -> config.setProviderCode("another-provider");
            }
            assertThrows(SecurityException.class, this::receive, field);
        }
        verifyNoInteractions(installed);
    }

    @Test void configMustBindThisInstallationAndContainOneUnmaskedTextSecret() {
        for (String value : List.of("{}", "[]", "{", "{} {}",
                "{\"callbackInstallationPid\":\"other\",\"callbackSalt\":\"synthetic-secret\"}",
                "{\"callbackInstallationPid\":\"installation\",\"callbackSalt\":4}",
                "{\"callbackInstallationPid\":\"installation\",\"callbackSalt\":\"ENC:opaque\"}",
                "{\"callbackInstallationPid\":\"installation\",\"callbackSalt\":\"****\"}",
                "{\"callbackInstallationPid\":\"installation\",\"callbackSalt\":\"one\",\"callbackSalt\":\"two\"}")) {
            config.setConfig(value); assertThrows(SecurityException.class, this::receive, value);
        }
        verify(provider, never()).verify(anyString(), any(), any(), any()); verifyNoInteractions(installed);
    }

    @Test void signatureFailureNeverDispatchesAndRestoresThePreviousContext() {
        MetaContext.setContext(99L, 8L, "previous", "browser");
        when(provider.verify(anyString(), anyString(), any(), anyString())).thenThrow(new SecurityException("bad-signature"));
        assertThrows(SecurityException.class, this::receive); verifyNoInteractions(installed);
        assertEquals(99L, MetaContext.getCurrentTenantId()); assertEquals("previous", MetaContext.getCurrentUserPid());
    }

    @Test void absentDuplicateForeignOrChangedProviderArtifactCannotDispatch() {
        when(provider.definitions()).thenReturn(List.of()); assertThrows(SecurityException.class, this::receive);
        when(provider.definitions()).thenReturn(List.of(definition, definition)); assertThrows(SecurityException.class, this::receive);
        when(provider.definitions()).thenReturn(List.of(definition));
        when(plugins.observeLoadedArtifact(provider.getClass())).thenReturn(Optional.of(
                new AuraPluginManager.LoadedPluginArtifact("foreign", Path.of("foreign.jar"), digest)));
        assertThrows(SecurityException.class, this::receive);
        when(plugins.observeLoadedArtifact(provider.getClass())).thenReturn(Optional.of(
                new AuraPluginManager.LoadedPluginArtifact("provider", Path.of("plugin.jar"), "sha256:" + "b".repeat(64))));
        assertThrows(SecurityException.class, this::receive); verifyNoInteractions(installed);
    }

    @Test void incompleteOrFailedBusinessInvocationNeverCallsProviderAcknowledgement() {
        when(installed.executeSynchronouslyWithReceipt(any(), anyString(), any()))
                .thenReturn(new InstalledIntegrationService.SynchronousResult(Map.of("state", "accepted"), false));
        assertThrows(IllegalStateException.class, this::receive);
        assertFalse(com.auraboot.framework.application.security.MachineDataAccessContext.isActive());
        when(installed.executeSynchronouslyWithReceipt(any(), anyString(), any())).thenThrow(new IllegalStateException("rollback"));
        assertThrows(IllegalStateException.class, this::receive);
        verify(provider, never()).acknowledge(anyString(), anyMap());
    }

    @Test void callbackDoesNotClaimReplayAuthorityAndAlwaysReauthenticates() {
        receive(); receive();
        var capture = ArgumentCaptor.forClass(IntegrationEventEnvelope.class);
        verify(installed, times(2)).executeSynchronouslyWithReceipt(any(), eq(source), capture.capture());
        assertEquals(capture.getAllValues().get(0), capture.getAllValues().get(1));
        verify(provider, times(2)).verify(eq(code), eq(contentType), any(), eq("synthetic-secret"));
        when(scopes.findScopes(42L, 7L)).thenReturn(Set.of()); assertThrows(SecurityException.class, this::receive);
        verify(installed, times(2)).executeSynchronouslyWithReceipt(any(), eq(source), any());
    }

    @Test void invalidSelectorsAndBodyAreRejectedBeforeDiscovery() {
        for (Long tenant : Arrays.asList(null, 0L, -1L))
            assertThrows(SecurityException.class, () -> service.receive(tenant, "installation", code, contentType, body));
        assertThrows(SecurityException.class, () -> service.receive(42L, "../other", code, contentType, body));
        assertThrows(SecurityException.class, () -> service.receive(42L, "installation", code, contentType, new byte[65537]));
        verifyNoInteractions(plugins, installed);
    }

    @Test void receiveDeclaresAnIndependentBoundedRollbackTransaction() throws Exception {
        var method = SignedIntegrationCallbackService.class.getMethod("receive", Long.class, String.class, String.class, String.class, byte[].class);
        var transaction = method.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertNotNull(transaction); assertEquals(30, transaction.timeout());
        assertEquals(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW, transaction.propagation());
        assertEquals(List.of(Exception.class), List.of(transaction.rollbackFor()));
    }
}
