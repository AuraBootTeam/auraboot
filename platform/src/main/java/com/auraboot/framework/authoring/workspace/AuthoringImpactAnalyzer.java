package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.workspace.AuthoringDependencyFingerprintRepository.ModelFingerprint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.TreeSet;

/** Resolves metadata dependencies without reading or executing business records. */
@Component
public class AuthoringImpactAnalyzer {

    public static final String ANALYZER_VERSION = "core-page-dependencies-v1";
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(2);

    private final AuthoringDependencyFingerprintRepository dependencyRepository;
    private final AuthoringPageSnapshotFactory snapshotFactory;

    public AuthoringImpactAnalyzer(
            AuthoringDependencyFingerprintRepository dependencyRepository,
            AuthoringPageSnapshotFactory snapshotFactory) {
        this.dependencyRepository = dependencyRepository;
        this.snapshotFactory = snapshotFactory;
    }

    public ImpactResult analyze(long tenantId, JsonNode snapshot) {
        Set<String> modelCodes = new TreeSet<>();
        collectModelCodes(snapshot, modelCodes);
        ArrayNode dependencies = JsonNodeFactory.instance.arrayNode();
        try {
            for (String modelCode : modelCodes) {
                ModelFingerprint model = dependencyRepository.findCurrentModel(
                        tenantId, modelCode, QUERY_TIMEOUT);
                if (model == null) {
                    return ImpactResult.failed("DEPENDENCY_MISSING");
                }
                ObjectNode dependency = dependencies.addObject();
                dependency.put("resourceType", "MODEL");
                dependency.put("resourceCode", modelCode);
                dependency.put("resourcePid", model.pid());
                dependency.put("version", model.version());
                dependency.put("rowVersion", model.rowVersion());
                dependency.put("updatedAt", model.updatedAt().toString());
                dependency.put("fieldFingerprint", model.fieldFingerprint());
            }
        } catch (QueryTimeoutException exception) {
            return ImpactResult.failed("ANALYSIS_TIMEOUT");
        } catch (DataAccessException exception) {
            return ImpactResult.failed("ANALYSIS_FAILED");
        }
        return ImpactResult.known(dependencies, snapshotFactory.checksum(dependencies));
    }

    private void collectModelCodes(JsonNode node, Set<String> modelCodes) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            addText(node.get("modelCode"), modelCodes);
            JsonNode dataSource = node.get("dataSource");
            if (dataSource != null && dataSource.isObject()) {
                addText(dataSource.get("model"), modelCodes);
            }
            node.elements().forEachRemaining(child -> collectModelCodes(child, modelCodes));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> collectModelCodes(child, modelCodes));
        }
    }

    private void addText(JsonNode value, Set<String> modelCodes) {
        if (value != null && value.isTextual() && !value.asText().isBlank()) {
            modelCodes.add(value.asText());
        }
    }

    public record ImpactResult(
            String status,
            JsonNode dependencies,
            String dependencyChecksum,
            String failureCode) {

        public static ImpactResult known(JsonNode dependencies, String dependencyChecksum) {
            return new ImpactResult("KNOWN", dependencies.deepCopy(), dependencyChecksum, null);
        }

        public static ImpactResult failed(String failureCode) {
            return new ImpactResult(
                    "FAILED", JsonNodeFactory.instance.arrayNode(), null, failureCode);
        }

        public boolean known() {
            return "KNOWN".equals(status);
        }
    }
}
