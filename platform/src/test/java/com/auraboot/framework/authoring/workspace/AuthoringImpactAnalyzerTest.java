package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.workspace.AuthoringDependencyFingerprintRepository.ModelFingerprint;
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
                .thenReturn(model("model-orders", 3, 5, "fields-orders"));
        when(repository.findCurrentModel(eq(7L), eq("payments"), any(Duration.class)))
                .thenReturn(model("model-payments", 2, 4, "fields-payments"));

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

    private ModelFingerprint model(
            String pid,
            int version,
            int rowVersion,
            String fields) {
        return new ModelFingerprint(
                pid, version, rowVersion, Instant.parse("2026-08-09T00:00:00Z"), fields);
    }
}
