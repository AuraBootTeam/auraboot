package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal;
import com.auraboot.framework.openplatform.mapper.ApplicationInstallationMapper;
import com.auraboot.framework.openplatform.mapper.OpenApiIdempotencyMapper;
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.auraboot.framework.plugin.extension.integration.ReliableIntegrationAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ExternalEventIngressService {
    private static final Pattern SOURCE_CODE = Pattern.compile("[a-z][a-z0-9-]{2,63}");
    private static final Pattern EVENT_TYPE = Pattern.compile("[a-z][a-z0-9.-]{2,100}");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[!-~]{8,255}");

    private final ApplicationInstallationMapper installationMapper;
    private final OpenApiIdempotencyMapper idempotencyMapper;
    private final ReliableIntegrationAccessor integrationAccessor;
    private final OpenPlatformSecretCodec secretCodec;
    private final ObjectMapper objectMapper;

    private InstalledIntegrationService installedIntegrations;

    @org.springframework.beans.factory.annotation.Autowired
    public void setInstalledIntegrations(InstalledIntegrationService installedIntegrations) {
        this.installedIntegrations = installedIntegrations;
    }

    @Transactional
    public IngressResult accept(MachinePrincipal principal, String sourceCode,
                                String idempotencyKey, String rawBody) {
        if (!SOURCE_CODE.matcher(sourceCode).matches()) {
            throw new InvalidExternalEventException("invalid_source_code");
        }
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new InvalidExternalEventException("invalid_idempotency_key");
        }
        var installation = installationMapper.findByTenantAndPid(
                principal.tenantId(), principal.installationPid());
        if (installation == null || !"active".equals(installation.getStatus())) {
            throw new InvalidExternalEventException("installation_not_active");
        }
        boolean installed = installedIntegrations != null && installedIntegrations.registered(sourceCode);
        ExternalEvent event = installed ? parseInstalled(rawBody) : parse(rawBody);
        String routeCode = "external-event:" + sourceCode;
        String requestHash = secretCodec.sha256(rawBody);
        int claimed = idempotencyMapper.claim(principal.tenantId(), installation.getId(), routeCode,
                idempotencyKey, requestHash, Instant.now().plusSeconds(86_400));
        if (claimed == 0) {
            String existingHash = idempotencyMapper.findRequestHash(principal.tenantId(), installation.getId(),
                    routeCode, idempotencyKey);
            if (!requestHash.equals(existingHash)) {
                throw new IdempotencyConflictException();
            }
            if (installedIntegrations == null || !installedIntegrations.registered(sourceCode))
                return new IngressResult(event.id(), true);
        }

        String durableEventId = "ext:" + principal.installationPid() + ":"
                + (installedIntegrations != null && installedIntegrations.registered(sourceCode) ? sourceCode+":" : "") + event.id();
        String publicEventType = "external." + sourceCode + "." + event.type() + ".v" + event.schemaVersion();
        String requestId = MDC.get("requestId");
        Map<String, String> traceHeaders = new LinkedHashMap<>(Map.of(
                "applicationPid", principal.applicationPid(),
                "installationPid", principal.installationPid(),
                "sourceCode", sourceCode,
                "externalEventId", event.id()));
        if (installed) {
            traceHeaders.put("externalSubjectType",event.subjectType());
            traceHeaders.put("externalSubjectPid",event.subjectPid());
        }
        if (requestId != null && !requestId.isBlank()) {
            traceHeaders.put("requestId", requestId);
        }
        IntegrationEventEnvelope envelope = new IntegrationEventEnvelope(
                "1.0", durableEventId, publicEventType,
                "open-platform/" + principal.installationPid() + "/" + sourceCode,
                event.subjectType() + "/" + event.subjectPid(), event.occurredAt(), principal.tenantId(),
                requestId == null || requestId.isBlank() ? durableEventId : requestId,
                null, event.subjectType() + "/" + event.subjectPid(),
                event.sequence(), event.data(), traceHeaders);
        InstalledIntegrationService.Acceptance acceptance = null;
        if (installedIntegrations != null && installedIntegrations.registered(sourceCode)) {
            acceptance = installedIntegrations.accept(principal,sourceCode,envelope);
        }
        if (acceptance == null || !acceptance.duplicate()) integrationAccessor.enqueue(envelope);
        try {
            int completed = idempotencyMapper.complete(principal.tenantId(), installation.getId(), routeCode,
                    idempotencyKey, requestHash,
                    objectMapper.writeValueAsString(Map.of("eventId", event.id(), "accepted", true)));
            if (acceptance != null && completed != 1) throw new IllegalStateException("External acceptance response was not persisted");
        } catch (Exception exception) {
            throw new IllegalStateException("Could not persist external event response", exception);
        }
        return new IngressResult(event.id(), acceptance != null && acceptance.duplicate(),
                acceptance == null ? null : acceptance.recordPid());
    }

    private ExternalEvent parseInstalled(String rawBody) {
        try {
            JsonNode root = objectMapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(rawBody);
            if (root == null || !root.isObject() || !keys(root).equals(java.util.Set.of(
                    "id","type","schemaVersion","occurredAt","subject","sequence","data")))
                throw new InvalidExternalEventException("invalid_event_body");
            for (String field : java.util.List.of("id","type","occurredAt"))
                if (!root.path(field).isTextual()) throw new InvalidExternalEventException("invalid_"+field);
            if (!root.path("schemaVersion").isIntegralNumber() || !root.path("schemaVersion").canConvertToInt()
                    || root.path("schemaVersion").asInt() < 1) throw new InvalidExternalEventException("invalid_schema_version");
            if (!root.path("sequence").isIntegralNumber() || !root.path("sequence").canConvertToLong()
                    || root.path("sequence").asLong() < 0) throw new InvalidExternalEventException("invalid_sequence");
            JsonNode subject = root.path("subject");
            if (!subject.isObject() || !keys(subject).equals(java.util.Set.of("type","pid"))
                    || !subject.path("type").isTextual() || !subject.path("pid").isTextual())
                throw new InvalidExternalEventException("invalid_subject");
            // Reuse existing length, event code, Instant and payload checks after exact typing.
            return parse(objectMapper.writeValueAsString(root));
        } catch (InvalidExternalEventException failure) { throw failure; }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new InvalidExternalEventException("invalid_event_body");
        }
    }

    private java.util.Set<String> keys(JsonNode node) {
        java.util.Set<String> result = new java.util.HashSet<>();node.fieldNames().forEachRemaining(result::add);return result;
    }

    private ExternalEvent parse(String rawBody) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            String id = requiredText(root, "id", 160);
            String type = requiredText(root, "type", 100);
            if (!EVENT_TYPE.matcher(type).matches()) {
                throw new InvalidExternalEventException("invalid_event_type");
            }
            int schemaVersion = root.path("schemaVersion").asInt(0);
            if (schemaVersion < 1) {
                throw new InvalidExternalEventException("invalid_schema_version");
            }
            Instant occurredAt = Instant.parse(requiredText(root, "occurredAt", 64));
            JsonNode subject = root.path("subject");
            String subjectType = requiredText(subject, "type", 80);
            String subjectPid = requiredText(subject, "pid", 160);
            long sequence = root.path("sequence").asLong(0);
            if (sequence < 0) {
                throw new InvalidExternalEventException("invalid_sequence");
            }
            JsonNode dataNode = root.path("data");
            if (!dataNode.isObject()) {
                throw new InvalidExternalEventException("invalid_data");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.convertValue(dataNode, LinkedHashMap.class);
            return new ExternalEvent(id, type, schemaVersion, occurredAt, subjectType, subjectPid,
                    sequence, data);
        } catch (InvalidExternalEventException exception) {
            throw exception;
        } catch (DateTimeParseException exception) {
            throw new InvalidExternalEventException("invalid_occurred_at");
        } catch (Exception exception) {
            throw new InvalidExternalEventException("invalid_event_body");
        }
    }

    private String requiredText(JsonNode node, String field, int maxLength) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new InvalidExternalEventException("invalid_" + field);
        }
        return value;
    }

    private record ExternalEvent(String id, String type, int schemaVersion, Instant occurredAt,
                                 String subjectType, String subjectPid, long sequence,
                                 Map<String, Object> data) {
    }

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record IngressResult(String eventId, boolean duplicate, String recordPid) {
        public IngressResult(String eventId, boolean duplicate) { this(eventId,duplicate,null); }
    }

    public static class InvalidExternalEventException extends RuntimeException {
        public InvalidExternalEventException(String message) {
            super(message);
        }
    }

    public static class IdempotencyConflictException extends RuntimeException {
    }
}
