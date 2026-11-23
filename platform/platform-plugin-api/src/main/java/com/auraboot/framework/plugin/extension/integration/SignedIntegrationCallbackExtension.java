package com.auraboot.framework.plugin.extension.integration;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.pf4j.ExtensionPoint;

/** Provider wire verification over a fixed installed integration, without ambient data authority. */
public interface SignedIntegrationCallbackExtension extends ExtensionPoint {
    int MAX_BODY_BYTES = 65536;

    List<Definition> definitions();

    /** Verify the exact wire bytes before returning any business request. Never retain the secret. */
    Request verify(String code, String contentType, byte[] body, String secret);

    /** Called only for a completed native result; the host commits before sending this acknowledgement. */
    Map<String,Object> acknowledge(String code, Map<String,Object> completedResult);

    record Definition(String code, String sourceCode, String eventType,
                      String serviceType, String providerCode, String secretField,
                      String installationField) {
        public Definition {
            if (code == null || !code.matches("[a-z][a-z0-9-]{2,63}")
                    || sourceCode == null || !sourceCode.matches("[a-z][a-z0-9-]{2,63}")
                    || eventType == null || !eventType.startsWith("external." + sourceCode + "."))
                throw new IllegalArgumentException("Invalid signed callback contract");
            for (String selector : new String[]{serviceType, providerCode, secretField, installationField}) {
                if (selector == null || !selector.matches("[a-zA-Z][a-zA-Z0-9_-]{0,63}"))
                    throw new IllegalArgumentException("Invalid signed callback secret selector");
            }
            if (secretField.equals(installationField))
                throw new IllegalArgumentException("Callback secret and installation selectors must differ");
        }
    }

    /** Stable provider identity and JSON facts. A missing provider timestamp is represented by EPOCH. */
    record Request(String eventId, String subjectType, String subjectPid,
                   Instant occurredAt, Map<String,Object> payload) {
        public Request {
            if (eventId == null || !eventId.matches("[A-Za-z0-9._:-]{8,128}")
                    || subjectType == null || !subjectType.matches("[a-z][a-z0-9-]{1,63}")
                    || subjectPid == null || !subjectPid.matches("[A-Za-z0-9_.:-]{1,128}"))
                throw new IllegalArgumentException("Invalid signed callback identity");
            // Reuse the SDK's recursive JSON copy rather than retaining mutable provider objects.
            java.util.Objects.requireNonNull(occurredAt, "occurredAt");
            payload = IntegrationEventEnvelope.immutablePayload(payload);
            if (payload.containsKey("requestId") || payload.containsKey("externalSubject"))
                throw new IllegalArgumentException("Callback request identity is host-owned");
        }
    }
}
