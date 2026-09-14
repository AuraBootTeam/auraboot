package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.security.ExternalApiKeyAuthenticator.ExternalApiKeyPrincipal;
import com.auraboot.framework.openplatform.mapper.ApplicationInstallationMapper;
import com.auraboot.framework.openplatform.mapper.OpenApiIdempotencyMapper;
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.auraboot.framework.plugin.extension.integration.ReliableIntegrationAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
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

    @Transactional
    public IngressResult accept(ExternalApiKeyPrincipal principal, String sourceCode,
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
        ExternalEvent event = parse(rawBody);
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
            return new IngressResult(event.id(), true);
        }

        String durableEventId = "ext:" + principal.installationPid() + ":" + event.id();
        String publicEventType = "external." + sourceCode + "." + event.type() + ".v" + event.schemaVersion();
        integrationAccessor.enqueue(new IntegrationEventEnvelope(
                "1.0", durableEventId, publicEventType,
                "open-platform/" + principal.installationPid() + "/" + sourceCode,
                event.subjectType() + "/" + event.subjectPid(), event.occurredAt(), principal.tenantId(),
                durableEventId, null, event.subjectType() + "/" + event.subjectPid(),
                event.sequence(), event.data(), Map.of(
                        "applicationPid", principal.applicationPid(),
                        "installationPid", principal.installationPid(),
                        "sourceCode", sourceCode,
                        "externalEventId", event.id())));
        try {
            idempotencyMapper.complete(principal.tenantId(), installation.getId(), routeCode,
                    idempotencyKey, requestHash,
                    objectMapper.writeValueAsString(Map.of("eventId", event.id(), "accepted", true)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not persist external event response", exception);
        }
        return new IngressResult(event.id(), false);
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

    public record IngressResult(String eventId, boolean duplicate) {
    }

    public static class InvalidExternalEventException extends RuntimeException {
        public InvalidExternalEventException(String message) {
            super(message);
        }
    }

    public static class IdempotencyConflictException extends RuntimeException {
    }
}
