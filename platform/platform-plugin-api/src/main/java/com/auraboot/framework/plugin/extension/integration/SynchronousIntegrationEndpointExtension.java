package com.auraboot.framework.plugin.extension.integration;

import org.pf4j.ExtensionPoint;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Fixed public contracts over an installed integration, never caller-selected commands. */
public interface SynchronousIntegrationEndpointExtension extends ExtensionPoint {
    List<Definition> definitions();
    Request prepare(String code, Map<String,Object> input);
    Map<String,Object> project(String code, String requestId, Map<String,Object> result);

    record Definition(String code, String name, String sourceCode, String eventType, int version,
                      int timeoutMs, Map<String,Object> inputSchema, Map<String,Object> resultSchema) {
        public Definition {
            if (code == null || !code.matches("[a-z][a-z0-9.-]{1,90}") || name == null || name.isBlank()
                    || sourceCode == null || !sourceCode.matches("[a-z][a-z0-9-]{2,63}")
                    || eventType == null || !eventType.startsWith("external." + sourceCode + ".")
                    || version < 1 || timeoutMs < 1 || timeoutMs > 30000
                    || inputSchema == null || resultSchema == null)
                throw new IllegalArgumentException("Invalid synchronous integration contract");
            inputSchema = Map.copyOf(inputSchema); resultSchema = Map.copyOf(resultSchema);
        }
    }
    record Request(String subjectType, String subjectPid, Instant occurredAt, long sequence, Map<String,Object> payload) {
        public Request {
            if (subjectType == null || !subjectType.matches("[a-z][a-z0-9-]{1,63}")
                    || subjectPid == null || !subjectPid.matches("[A-Za-z0-9_.:-]{1,128}")
                    || occurredAt == null || sequence < 0 || payload == null)
                throw new IllegalArgumentException("Invalid synchronous business identity");
            payload = Map.copyOf(payload);
        }
    }
}
