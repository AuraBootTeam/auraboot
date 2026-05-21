package com.auraboot.framework.rag.d7;

import com.auraboot.framework.rag.dto.RetrievalResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class D7RetrievalTraceWriter {

    private static final int SCHEMA_VERSION = 1;
    private static final String QUERY_ID_FIELD = "queryId";
    private static final String QUERY_FIELD = "query";
    private static final String QUERIES_FIELD = "queries";
    private static final String RESULTS_FIELD = "results";
    private static final String RANKED_SOURCE_PATHS_FIELD = "rankedSourcePaths";
    private static final String NO_ANSWER_FIELD = "noAnswer";
    private static final String DESCRIPTION =
            "Runtime captured D7 retrieval results for scripts/rag-evaluate-results.mjs.";

    private final ObjectMapper objectMapper;
    private final D7KnowledgeProperties properties;

    public synchronized void recordRetrieval(Long tenantId, String query,
                                             List<D7CompiledKnowledgeMatch> compiledMatches,
                                             List<RetrievalResult> rawResults) {
        if (!properties.isTraceEnabled()) {
            return;
        }
        if (query == null || query.isBlank()) {
            return;
        }

        String queryId = resolveQueryId(query);
        if (queryId == null || queryId.isBlank()) {
            log.debug("Skipping D7 retrieval trace because no golden query id matched query");
            return;
        }

        Path outputPath = resolvePath(properties.getTraceOutputPath());
        if (outputPath == null) {
            log.debug("Skipping D7 retrieval trace because traceOutputPath is blank");
            return;
        }

        ObjectNode root = readOrCreateTraceFile(outputPath);
        ArrayNode results = ensureResultsArray(root);
        upsertResult(results, queryId, tenantId, query, sourcePaths(compiledMatches), isNoAnswer(compiledMatches, rawResults));
        root.put("capturedAt", Instant.now().toString());
        writeTraceFile(outputPath, root);
    }

    private String resolveQueryId(String query) {
        Path goldenQueryPath = resolvePath(properties.getGoldenQueryPath());
        if (goldenQueryPath == null || !Files.isRegularFile(goldenQueryPath)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(goldenQueryPath.toFile());
            JsonNode queries = root.get(QUERIES_FIELD);
            if (queries == null || !queries.isArray()) {
                return null;
            }
            for (JsonNode item : queries) {
                if (query.equals(item.path(QUERY_FIELD).asText())) {
                    return item.path("id").asText();
                }
            }
            return null;
        } catch (IOException e) {
            log.warn("Failed to read D7 golden query file {}: {}", goldenQueryPath, e.getMessage());
            return null;
        }
    }

    private ObjectNode readOrCreateTraceFile(Path outputPath) {
        if (Files.isRegularFile(outputPath)) {
            try {
                JsonNode existing = objectMapper.readTree(outputPath.toFile());
                if (existing != null && existing.isObject()) {
                    ObjectNode object = (ObjectNode) existing;
                    object.put("schemaVersion", SCHEMA_VERSION);
                    object.put("description", DESCRIPTION);
                    return object;
                }
            } catch (IOException e) {
                log.warn("Failed to read D7 trace output {}, rewriting it: {}", outputPath, e.getMessage());
            }
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("description", DESCRIPTION);
        root.set(RESULTS_FIELD, objectMapper.createArrayNode());
        return root;
    }

    private ArrayNode ensureResultsArray(ObjectNode root) {
        JsonNode results = root.get(RESULTS_FIELD);
        if (results != null && results.isArray()) {
            return (ArrayNode) results;
        }
        ArrayNode created = objectMapper.createArrayNode();
        root.set(RESULTS_FIELD, created);
        return created;
    }

    private void upsertResult(ArrayNode results, String queryId, Long tenantId, String query,
                              List<String> rankedSourcePaths, boolean noAnswer) {
        Map<String, JsonNode> existingByQueryId = new LinkedHashMap<>();
        for (JsonNode result : results) {
            existingByQueryId.put(result.path(QUERY_ID_FIELD).asText(), result);
        }
        existingByQueryId.put(queryId, buildResult(queryId, tenantId, query, rankedSourcePaths, noAnswer));

        results.removeAll();
        existingByQueryId.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .forEach(results::add);
    }

    private ObjectNode buildResult(String queryId, Long tenantId, String query,
                                   List<String> rankedSourcePaths, boolean noAnswer) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put(QUERY_ID_FIELD, queryId);
        if (tenantId != null) {
            result.put("tenantId", tenantId);
        }
        result.put(QUERY_FIELD, query);
        ArrayNode paths = objectMapper.createArrayNode();
        for (String path : rankedSourcePaths) {
            paths.add(path);
        }
        result.set(RANKED_SOURCE_PATHS_FIELD, paths);
        result.put(NO_ANSWER_FIELD, noAnswer);
        return result;
    }

    private List<String> sourcePaths(List<D7CompiledKnowledgeMatch> compiledMatches) {
        if (compiledMatches == null || compiledMatches.isEmpty()) {
            return List.of();
        }
        Set<String> paths = new LinkedHashSet<>();
        for (D7CompiledKnowledgeMatch match : compiledMatches) {
            D7CompiledKnowledgePage page = match.getPage();
            if (page == null || page.getSourceRefs() == null) {
                continue;
            }
            for (D7SourceRef sourceRef : page.getSourceRefs()) {
                String path = sourceRef.getPath();
                if (path != null && !path.isBlank()) {
                    paths.add(path);
                }
            }
        }
        return List.copyOf(paths);
    }

    private boolean isNoAnswer(List<D7CompiledKnowledgeMatch> compiledMatches, List<RetrievalResult> rawResults) {
        boolean hasCompiled = compiledMatches != null && !compiledMatches.isEmpty();
        boolean hasRaw = rawResults != null && !rawResults.isEmpty();
        return !hasCompiled && !hasRaw;
    }

    private void writeTraceFile(Path outputPath, ObjectNode root) {
        try {
            Path parent = outputPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), root);
        } catch (IOException e) {
            log.warn("Failed to write D7 trace output {}: {}", outputPath, e.getMessage());
        }
    }

    private Path resolvePath(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            return null;
        }
        Path path = Path.of(configuredPath);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return Path.of(System.getProperty("user.dir")).resolve(path).normalize();
    }
}
