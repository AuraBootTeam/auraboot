package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.workspace.AuthoringDependencyFingerprintRepository.ResourceFingerprint;
import com.auraboot.framework.authoring.workspace.AuthoringImpactAnalyzer.ImpactResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthoringImpactAnalyzerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AuthoringDependencyFingerprintRepository repository =
            mock(AuthoringDependencyFingerprintRepository.class);
    private final AuthoringImpactAnalyzer analyzer = new AuthoringImpactAnalyzer(
            repository, new AuthoringPageSnapshotFactory(objectMapper));

    @Test
    void returnsSortedMetadataOnlyDependencies() throws Exception {
        when(repository.findCurrentModel(eq(7L), eq("orders"), any(Duration.class)))
                .thenReturn(resource("MODEL", "orders", "model-orders", 3, 5, "fields-orders"));
        when(repository.findCurrentModel(eq(7L), eq("payments"), any(Duration.class)))
                .thenReturn(resource(
                        "MODEL", "payments", "model-payments", 2, 4, "fields-payments"));

        ImpactResult result = analyzer.analyze(7, objectMapper.readTree("""
                {"pid":"page-1","modelCode":"orders","blocks":[
                  {"id":"table-1","blockType":"table",
                   "dataSource":{"model":"payments"}},
                  {"id":"table-2","blockType":"table",
                   "dataSource":{"model":"orders"}}
                ]}
                """));

        assertThat(result.known()).isTrue();
        assertThat(result.dependencyChecksum()).hasSize(64);
        assertThat(result.dependencies()).hasSize(2);
        assertThat(result.dependencies().get(0).path("resourceCode").asText())
                .isEqualTo("orders");
        assertThat(result.dependencies().toString()).doesNotContain("record");
    }

    @Test
    void collectsRealPageDictionaryCommandAndChildModelReferences() throws Exception {
        when(repository.findCurrentCommand(eq(7L), eq("orders:create"), any(Duration.class)))
                .thenReturn(resource(
                        "COMMAND", "orders:create", "command-create", 2, 3, "command-fields"));
        when(repository.findCurrentCommand(eq(7L), eq("lines:delete"), any(Duration.class)))
                .thenReturn(resource(
                        "COMMAND", "lines:delete", "command-delete", 1, 2, "command-lines"));
        when(repository.findCurrentDictionary(eq(7L), eq("order_status"), any(Duration.class)))
                .thenReturn(resource(
                        "DICTIONARY", "order_status", "dict-status", 4, 4, "dict-items"));
        when(repository.findCurrentModel(eq(7L), eq("orders"), any(Duration.class)))
                .thenReturn(resource("MODEL", "orders", "model-orders", 3, 5, "fields-orders"));
        when(repository.findCurrentModel(eq(7L), eq("order_lines"), any(Duration.class)))
                .thenReturn(resource(
                        "MODEL", "order_lines", "model-lines", 1, 1, "fields-lines"));

        ImpactResult result = analyzer.analyze(7, objectMapper.readTree("""
                {"modelCode":"orders","blocks":[
                  {"dictCode":"order_status",
                   "action":{"command":"orders:create"}},
                  {"subTable":{"childModel":"order_lines","commands":{
                    "delete":"lines:delete"}}}
                ]}
                """));

        assertThat(result.known()).isTrue();
        assertThat(result.dependencies()).extracting(dependency ->
                dependency.path("resourceType").asText()
                        + ":" + dependency.path("resourceCode").asText())
                .containsExactly(
                        "COMMAND:lines:delete",
                        "COMMAND:orders:create",
                        "DICTIONARY:order_status",
                        "MODEL:order_lines",
                        "MODEL:orders");
        assertThat(result.dependencies().toString())
                .doesNotContain("label")
                .doesNotContain("execution_config");
    }

    @Test
    void missingDependencyFailsClosedWithoutAPartialFingerprint() throws Exception {
        when(repository.findCurrentModel(eq(7L), eq("missing"), any(Duration.class)))
                .thenReturn(null);

        ImpactResult result = analyzer.analyze(7, objectMapper.readTree("""
                {"pid":"page-1","modelCode":"missing","blocks":[]}
                """));

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.failureCode()).isEqualTo("DEPENDENCY_MISSING");
        assertThat(result.dependencyChecksum()).isNull();
        assertThat(result.dependencies()).isEmpty();
    }

    @Test
    void dependencyQueryTimeoutHasAStableFailClosedOutcome() throws Exception {
        when(repository.findCurrentModel(eq(7L), eq("orders"), any(Duration.class)))
                .thenThrow(new QueryTimeoutException("database details must not escape"));

        ImpactResult result = analyzer.analyze(7, objectMapper.readTree("""
                {"pid":"page-1","modelCode":"orders","blocks":[]}
                """));

        assertThat(result.failureCode()).isEqualTo("ANALYSIS_TIMEOUT");
        assertThat(result.toString()).doesNotContain("database details");
    }

    private ResourceFingerprint resource(
            String resourceType,
            String resourceCode,
            String pid,
            int version,
            int rowVersion,
            String components) {
        return new ResourceFingerprint(
                resourceType, resourceCode, pid, version, rowVersion,
                Instant.parse("2026-08-09T00:00:00Z"), components);
    }
}
