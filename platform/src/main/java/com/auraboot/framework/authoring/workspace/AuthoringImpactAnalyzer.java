package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.workspace.AuthoringDependencyFingerprintRepository.ResourceFingerprint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;

/** Resolves metadata dependencies without reading or executing business records. */
@Component
public class AuthoringImpactAnalyzer {

    public static final String ANALYZER_VERSION = "core-page-dependencies-v5";
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(2);
    private static final Comparator<DependencyRef> DEPENDENCY_ORDER =
            Comparator.comparing(DependencyRef::resourceType)
                    .thenComparing(DependencyRef::resourceCode);

    private final AuthoringDependencyFingerprintRepository dependencyRepository;
    private final AuthoringPageSnapshotFactory snapshotFactory;

    public AuthoringImpactAnalyzer(
            AuthoringDependencyFingerprintRepository dependencyRepository,
            AuthoringPageSnapshotFactory snapshotFactory) {
        this.dependencyRepository = dependencyRepository;
        this.snapshotFactory = snapshotFactory;
    }

    public ImpactResult analyze(long tenantId, long envId, JsonNode snapshot) {
        Set<DependencyRef> references = new TreeSet<>(DEPENDENCY_ORDER);
        collectReferences(snapshot, references);
        ArrayNode dependencies = JsonNodeFactory.instance.arrayNode();
        try {
            for (DependencyRef reference : references) {
                ResourceFingerprint resource = resolve(tenantId, envId, reference);
                if (resource == null) {
                    return ImpactResult.failed("DEPENDENCY_MISSING");
                }
                ObjectNode dependency = dependencies.addObject();
                dependency.put("resourceType", resource.resourceType());
                dependency.put("resourceCode", resource.resourceCode());
                dependency.put("resourcePid", resource.pid());
                dependency.put("version", resource.version());
                dependency.put("rowVersion", resource.rowVersion());
                dependency.put("updatedAt", resource.updatedAt().toString());
                dependency.put("componentFingerprint", resource.componentFingerprint());
            }
        } catch (QueryTimeoutException exception) {
            return ImpactResult.failed("ANALYSIS_TIMEOUT");
        } catch (DataAccessException exception) {
            return ImpactResult.failed("ANALYSIS_FAILED");
        }
        return ImpactResult.known(dependencies, snapshotFactory.checksum(dependencies));
    }

    private ResourceFingerprint resolve(long tenantId, long envId, DependencyRef reference) {
        return switch (reference.resourceType()) {
            case "COMMAND" -> dependencyRepository.findCurrentCommand(
                    tenantId, reference.resourceCode(), QUERY_TIMEOUT);
            case "DICTIONARY" -> dependencyRepository.findCurrentDictionary(
                    tenantId, reference.resourceCode(), QUERY_TIMEOUT);
            case "MODEL" -> dependencyRepository.findCurrentModel(
                    tenantId, reference.resourceCode(), QUERY_TIMEOUT);
            case "NAMED_QUERY" -> dependencyRepository.findCurrentNamedQuery(
                    tenantId, reference.resourceCode(), QUERY_TIMEOUT);
            case "PAGE" -> dependencyRepository.findCurrentPage(
                    tenantId, envId, reference.resourceCode(), QUERY_TIMEOUT);
            default -> throw new IllegalStateException(
                    "Unsupported authoring dependency type: " + reference.resourceType());
        };
    }

    private void collectReferences(JsonNode node, Set<DependencyRef> references) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            addReference("MODEL", node.get("modelCode"), references);
            addReference("MODEL", node.get("childModel"), references);
            addReference("DICTIONARY", node.get("dictCode"), references);
            addReference("COMMAND", node.get("command"), references);
            addReference("COMMAND", node.get("commandCode"), references);
            addReference("NAMED_QUERY", node.get("queryCode"), references);
            addPageTarget(node.get("navigateTo"), references);
            if ("navigate".equals(node.path("type").asText())) {
                addPageTarget(node.get("to"), references);
            }
            JsonNode dataSource = node.get("dataSource");
            if (dataSource != null && dataSource.isObject()) {
                addReference("MODEL", dataSource.get("model"), references);
            }
            JsonNode commands = node.get("commands");
            if (commands != null && commands.isObject()) {
                commands.elements().forEachRemaining(
                        command -> addReference("COMMAND", command, references));
            }
            node.elements().forEachRemaining(child -> collectReferences(child, references));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> collectReferences(child, references));
        }
    }

    private void addReference(
            String resourceType,
            JsonNode value,
            Set<DependencyRef> references) {
        if (value != null && value.isTextual() && !value.asText().isBlank()) {
            references.add(new DependencyRef(resourceType, value.asText()));
        }
    }

    private void addPageTarget(JsonNode value, Set<DependencyRef> references) {
        if (value == null || !value.isTextual()) {
            return;
        }
        String target = value.asText().trim();
        if (target.isBlank()
                || target.startsWith("/")
                || target.startsWith("{")
                || target.contains(":")) {
            return;
        }
        if (target.matches("[A-Za-z][A-Za-z0-9_-]*")) {
            references.add(new DependencyRef("PAGE", target));
        }
    }

    private record DependencyRef(String resourceType, String resourceCode) {
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
