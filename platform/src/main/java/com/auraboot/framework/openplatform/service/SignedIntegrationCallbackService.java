package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.auraboot.framework.openplatform.mapper.ApplicationInstallationMapper;
import com.auraboot.framework.openplatform.mapper.ApplicationScopeGrantMapper;
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.auraboot.framework.plugin.extension.integration.SignedIntegrationCallbackExtension;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Signature authentication cannot inherit a default tenant or mint a bearer token. */
@Service
@RequiredArgsConstructor
public class SignedIntegrationCallbackService {
    private final AuraPluginManager plugins;
    private final InstalledIntegrationRegistry registry;
    private final InstalledIntegrationService installed;
    private final ApplicationInstallationMapper installations;
    private final ApplicationScopeGrantMapper scopes;
    private final CloudConfigService configs;
    private final ObjectMapper json;
    private final OpenApiProtocolHash hashes;

    private record Bound(SignedIntegrationCallbackExtension extension,
                         SignedIntegrationCallbackExtension.Definition definition,
                         com.auraboot.framework.plugin.extension.integration.ExternalEventIngressExtension.Registration source) {}

    private Bound resolve(String code) {
        if (code == null || !code.matches("[a-z][a-z0-9-]{2,63}")) throw denied();
        var matches = new ArrayList<Bound>();
        for (var extension : plugins.getExtensionsOfType(SignedIntegrationCallbackExtension.class)) {
            var definitions = extension.definitions();
            if (definitions == null || definitions.size() > 32) throw denied();
            for (var definition : definitions) {
                if (definition == null) throw denied();
                if (!code.equals(definition.code())) continue;
                var artifact = plugins.observeLoadedArtifact(extension.getClass()).orElseThrow(SignedIntegrationCallbackService::denied);
                var source = registry.registration(definition.sourceCode()).orElseThrow(SignedIntegrationCallbackService::denied);
                if (!source.getKey().providerPluginId().equals(artifact.pluginId())
                        || !source.getValue().equals(artifact.digest())
                        || !source.getKey().eventTypes().contains(definition.eventType())) throw denied();
                matches.add(new Bound(extension, definition, source.getKey()));
            }
        }
        if (matches.size() != 1) throw denied();
        return matches.getFirst();
    }

    /** The proxied transaction must commit before the controller receives any acknowledgement. */
    @Transactional(timeout = 30, rollbackFor = Exception.class,
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Map<String,Object> receive(Long tenant, String installationPid, String code,
                                      String contentType, byte[] body) {
        if (tenant == null || tenant <= 0 || installationPid == null
                || !installationPid.matches("[A-Za-z0-9_-]{1,100}")
                || body == null || body.length == 0 || body.length > SignedIntegrationCallbackExtension.MAX_BODY_BYTES)
            throw denied();
        var bound = resolve(code);
        var identity = installations.findActiveIdentity(tenant, installationPid);
        if (identity == null || identity.installationId() == null || identity.applicationPid() == null) throw denied();
        var result = new AtomicReference<Map<String,Object>>();
        MetaContext.runWithSnapshot(null, () -> {
            MetaContext.setContext(tenant, 0L, "installation:" + installationPid, "signed-integration-callback");
            var installation = installations.findByTenantAndPid(tenant, installationPid);
            if (installation == null || !tenant.equals(installation.getTenantId())
                    || !installationPid.equals(installation.getPid()) || !"active".equals(installation.getStatus())
                    || !identity.installationId().equals(installation.getId())
                    || !scopes.findScopes(tenant, identity.installationId()).contains(bound.source().requiredScope())) throw denied();
            String secret = secret(tenant, installationPid, bound.definition());
            var verified = Objects.requireNonNull(bound.extension().verify(code, contentType, body.clone(), secret));
            String external = "callback-" + hashes.fingerprint(Map.of("callback", code, "event", verified.eventId()), false);
            String subject = verified.subjectType() + "/" + verified.subjectPid();
            var envelope = new IntegrationEventEnvelope("1.0", "callback:" + installationPid + ":" + code + ":" + external,
                    bound.definition().eventType(), "open-platform/" + installationPid + "/" + bound.definition().sourceCode(),
                    subject, verified.occurredAt(), tenant, external, null, subject, 0, verified.payload(),
                    Map.of("applicationPid", identity.applicationPid(), "installationPid", installationPid,
                            "sourceCode", bound.definition().sourceCode(), "externalEventId", external,
                            "externalSubjectType", verified.subjectType(), "externalSubjectPid", verified.subjectPid()));
            var principal = new MachinePrincipal(tenant, installationPid, "signed-callback",
                    Set.of(bound.source().requiredScope()), identity.applicationPid(), installationPid,
                    installation.getEnvironment(), null);
            InstalledIntegrationService.SynchronousResult completed;
            try (var machine = com.auraboot.framework.application.security.MachineDataAccessContext.begin(principal)) {
                completed = installed.executeSynchronouslyWithReceipt(principal, bound.definition().sourceCode(), envelope);
            }
            if (completed == null || !"completed".equals(completed.data().get("state")))
                throw new IllegalStateException("Signed callback business result is not complete");
            var acknowledgement = bound.extension().acknowledge(code, completed.data());
            if (acknowledgement == null || acknowledgement.isEmpty())
                throw new IllegalStateException("Signed callback acknowledgement required");
            // Validate JSON and recursively freeze it while still inside the transaction.
            result.set(IntegrationEventEnvelope.immutablePayload(acknowledgement));
        });
        return result.get();
    }

    private String secret(Long tenant, String installation, SignedIntegrationCallbackExtension.Definition definition) {
        var config = configs.getEffectiveConfig(tenant, definition.serviceType(), definition.providerCode());
        if (config == null || !tenant.equals(config.getTenantId()) || !"tenant".equals(config.getConfigLevel())
                || !Boolean.TRUE.equals(config.getEnabled()) || !Boolean.FALSE.equals(config.getDeletedFlag())
                || !definition.serviceType().equals(config.getServiceType())
                || !definition.providerCode().equals(config.getProviderCode()) || config.getConfig() == null) throw denied();
        JsonNode root;
        try {
            root = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature()).readTree(config.getConfig());
        } catch (JsonProcessingException malformed) {
            // Parser diagnostics can contain decrypted values. Do not expose them in errors or logs.
            throw denied();
        }
        if (root == null || !root.isObject() || !root.path(definition.installationField()).isTextual()
                || !installation.equals(root.path(definition.installationField()).textValue())) throw denied();
        var value = root.path(definition.secretField());
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 4096
                || value.textValue().startsWith("ENC:") || value.textValue().startsWith("****")) throw denied();
        return value.textValue();
    }

    private static SecurityException denied() { return new SecurityException("SIGNED_CALLBACK_DENIED"); }
}
