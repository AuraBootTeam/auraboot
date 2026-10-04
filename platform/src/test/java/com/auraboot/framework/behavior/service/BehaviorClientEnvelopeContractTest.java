package com.auraboot.framework.behavior.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.behavior.dto.BehaviorEventInput;
import com.auraboot.framework.behavior.ingest.BehaviorIngestPublisher;
import com.auraboot.framework.behavior.ingest.BehaviorEventAvroCompatibilityTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BehaviorClientEnvelopeContractTest {
    private static final Map<String, String> PROVEN = new LinkedHashMap<>();
    private final BehaviorIngestPublisher publisher = mock(BehaviorIngestPublisher.class);
    private final BehaviorCollectService service = new BehaviorCollectService(publisher);

    @AfterEach void cleanup() { MetaContext.clear(); }

    private BehaviorEventInput event(String version, Map<String, Object> props) {
        BehaviorEventInput event = new BehaviorEventInput();
        event.setEventId("owned-contract-event");
        event.setEventName("page_view");
        event.setSource("web");
        event.setSchemaVersion(version);
        event.setProps(props);
        return event;
    }

    private void reject(BehaviorEventInput invalid) {
        MetaContext.setContext(900L, 55L, "owned", "Owned");
        var batch = List.of(event("1", Map.of("routeTemplate", "/p/c/owned")), invalid);
        assertThatThrownBy(() -> service.record(batch)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.recordAnonymous(batch, 900L)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(publisher);
    }

    @Test void sensitiveAndUnknownPropertiesRejectTheWholeBatchBeforePublishing() {
        for (String key : List.of("rawQuestion", "sql", "rows", "password", "unknownProperty")) {
            reject(event("1", Map.of(key, "synthetic-private-content")));
        }
        prove("sensitive-property-allowlist", "sensitiveAndUnknownPropertiesRejectTheWholeBatchBeforePublishing");
    }

    @Test void nestedObjectsCannotHideContentUnderAnAllowedKey() {
        reject(event("1", Map.of("routeTemplate", Map.of("sql", "synthetic-private-content"))));
        prove("nested-content-rejection", "nestedObjectsCannotHideContentUnderAnAllowedKey");
    }

    @Test void queryStringsAndRawTextAreNotRouteTemplates() {
        reject(event("1", Map.of("routeTemplate", "/p/c/owned?token=synthetic")));
        reject(event("1", Map.of("routeTemplate", "synthetic private question")));
        prove("route-template-minimization", "queryStringsAndRawTextAreNotRouteTemplates");
    }

    @Test void unknownOrMissingSchemaVersionsRejectBeforePublish() {
        for (String version : List.of("0", "999", "")) reject(event(version, Map.of()));
        reject(event(null, Map.of()));
        prove("schema-version-rejection", "unknownOrMissingSchemaVersionsRejectBeforePublish");
    }

    @Test void clientCannotDeclareAServerVerifiedIdentityQuality() {
        BehaviorEventInput forged = event("1", Map.of());
        forged.setIdentityQuality("server_verified");
        reject(forged);
        prove("client-trust-boundary", "clientCannotDeclareAServerVerifiedIdentityQuality");
    }

    @Test void supportedVersionsPreserveSafePropertiesAndUseServerIdentity() {
        MetaContext.setContext(900L, 55L, "owned", "Owned");
        for (String version : List.of("1", "2")) {
            BehaviorEventInput accepted = event(version, Map.of("routeTemplate", "/p/c/owned/:id"));
            accepted.setIdentityQuality("stable");
            when(publisher.publish(900L, 55L, List.of(accepted))).thenReturn(1);
            assertThat(service.record(List.of(accepted))).isEqualTo(1);
            verify(publisher).publish(eq(900L), eq(55L), eq(List.of(accepted)));
        }
        prove("supported-version-server-identity", "supportedVersionsPreserveSafePropertiesAndUseServerIdentity");
    }

    @Test void additiveVersionsKeepActualAvroReaderWriterCompatibility() throws Exception {
        BehaviorEventAvroCompatibilityTest compatibility = new BehaviorEventAvroCompatibilityTest();
        compatibility.v2SchemaIsBackwardCompatibleWithV1Payloads();
        compatibility.v1ReadersCanIgnoreV2AdditiveFields();
        prove("avro-bidirectional-compatibility", "additiveVersionsKeepActualAvroReaderWriterCompatibility");
    }

    private static void prove(String assertionId, String method) {
        PROVEN.put(assertionId, BehaviorClientEnvelopeContractTest.class.getName() + "#" + method + "()");
    }

    @AfterAll static void emitExactScenarioReceipt() throws Exception {
        if (!"AMOS-overview-behavior-envelope-contract".equals(System.getenv("S12_SCENARIO_ID"))) return;
        if (PROVEN.size() != 7) return; // An incomplete or failed selection cannot certify this contract.
        ObjectMapper mapper = new ObjectMapper();
        Path root = Path.of(System.getenv("S12_SCENARIO_EVIDENCE_DIR"));
        Files.createDirectories(root);
        var assertions = new java.util.ArrayList<Map<String, Object>>();
        for (var entry : PROVEN.entrySet()) {
            String file = entry.getKey() + ".json";
            Files.writeString(root.resolve(file), mapper.writeValueAsString(Map.of(
                    "assertionId", entry.getKey(), "testId", entry.getValue(), "passed", true)));
            assertions.add(Map.of("id", entry.getKey(), "testId", entry.getValue(),
                    "passed", true, "evidence", List.of(file)));
        }
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("runId", System.getenv("S12_RUN_ID"));
        receipt.put("scenarioId", System.getenv("S12_SCENARIO_ID"));
        receipt.put("driver", "unit");
        receipt.put("sourceCommit", System.getenv("S12_SOURCE_COMMIT"));
        receipt.put("baseUrl", System.getenv("S12_BASE_URL"));
        receipt.put("executed", 7);
        receipt.put("passed", 7);
        receipt.put("failed", 0);
        receipt.put("skipped", 0);
        receipt.put("assertions", assertions);
        Files.writeString(Path.of(System.getenv("S12_RECEIPT_FILE")), mapper.writeValueAsString(receipt));
    }
}
