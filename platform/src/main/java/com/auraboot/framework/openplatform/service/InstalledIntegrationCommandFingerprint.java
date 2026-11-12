package com.auraboot.framework.openplatform.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Fingerprints command authority without coupling independent installation bindings. */
final class InstalledIntegrationCommandFingerprint {
    private static final String BINDINGS = "/handlerParams/installedIntegration/bindings";
    // Cache only digests and verdicts, never command configurations or credentials.
    private static final Cache<LegacyKey, Boolean> LEGACY = Caffeine.newBuilder()
            .maximumSize(2048).expireAfterAccess(Duration.ofMinutes(30)).build();

    private InstalledIntegrationCommandFingerprint() {}

    static Map<String, Object> scoped(ObjectMapper json, Map<String, String> configs, String installation) {
        var result = new LinkedHashMap<String, Object>();
        for (var entry : configs.entrySet()) {
            var config = object(json, entry.getValue());
            JsonNode declaration = config.at("/handlerParams/installedIntegration");
            if (!(declaration instanceof ObjectNode integration)) throw invalid();
            if (integration.has("bindings")) {
                JsonNode bindings = integration.get("bindings");
                int selected = selected(bindings, installation);
                integration.set("bindings", json.createArrayNode().add(bindings.get(selected).deepCopy()));
            } else if (!integration.path("bindingLookup").isObject()) {
                throw invalid();
            }
            // Maps, unlike ObjectNode, honor recursive ORDER_MAP_ENTRIES_BY_KEYS in the host digest.
            result.put(entry.getKey(), json.convertValue(config, Object.class));
        }
        return result;
    }

    /** Reconstruct only provider-declared process read additions. Every other command byte/field
     * remains part of the digest; callers may use these candidates only for completed receipts.
     */
    static List<Map<String,String>> beforeProcessReadAdditions(ObjectMapper json,Map<String,String> configs,
            String processCommand,java.util.Set<String> additions) {
        if(additions.isEmpty() || !configs.containsKey(processCommand))return List.of();
        var process=object(json,configs.get(processCommand));
        if(!(process.path("handlerParams") instanceof ObjectNode parameters)
                || !(parameters.path("installedMachineData") instanceof ObjectNode grants))return List.of();
        for(String model:additions) {
            JsonNode access=grants.get(model);
            if(!(access instanceof ObjectNode grant) || grant.size()!=1 || !grant.has("read")
                    || !grant.path("read").isArray() || grant.path("read").isEmpty())return List.of();
            var fields=new java.util.HashSet<String>();
            for(var field:grant.path("read")) {
                if(!field.isTextual() || !field.asText().matches("[A-Za-z_][A-Za-z0-9_.]{0,127}")
                        || !fields.add(field.asText()))return List.of();
            }
        }
        additions.forEach(grants::remove);
        var candidates=new ArrayList<Map<String,String>>();
        var previous=new LinkedHashMap<>(configs);previous.put(processCommand,process.toString());
        candidates.add(Map.copyOf(previous));
        if(grants.isEmpty()) {
            // A first read scope may introduce its container as well as its sole model grant.
            parameters.remove("installedMachineData");previous=new LinkedHashMap<>(configs);
            previous.put(processCommand,process.toString());candidates.add(Map.copyOf(previous));
        }
        return List.copyOf(candidates);
    }

    static boolean legacyPrefixMatches(ObjectMapper json, Map<String, String> configs, String installation,
            String currentLegacyHash, String originalHash, Function<Map<String, String>, String> legacyHash) {
        if (originalHash == null || !originalHash.matches("[0-9a-f]{64}")) return false;
        var key = new LegacyKey(currentLegacyHash, installation, originalHash);
        return LEGACY.get(key, ignored -> prefixMatches(json, configs, installation, originalHash, legacyHash));
    }

    private static boolean prefixMatches(ObjectMapper json, Map<String, String> configs, String installation,
            String originalHash, Function<Map<String, String>, String> legacyHash) {
        var projections = new LinkedHashMap<String, Projection>();
        JsonNode common = null;
        int first = -1;
        for (var entry : configs.entrySet()) {
            JsonNode bindings = object(json, entry.getValue()).at(BINDINGS);
            if (bindings.isMissingNode()) return false; // Lookup bindings have no inline-prefix ancestry.
            int selected = selected(bindings, installation);
            if (common != null && !common.equals(bindings)) return false;
            common = bindings;
            first = selected + 1; // A historical prefix must still contain this installation.
            projections.put(entry.getKey(), projection(json, entry.getValue()));
        }
        if (common == null) return false;
        // Only remove an appended suffix. Every other byte stays pinned by the original digest.
        for (int size = common.size() - 1; size >= first; size--) {
            var candidate = new LinkedHashMap<String, String>();
            for (var entry : projections.entrySet()) candidate.put(entry.getKey(), entry.getValue().prefix(size));
            if (originalHash.equals(legacyHash.apply(candidate))) return true;
        }
        return false;
    }

    private static int selected(JsonNode bindings, String installation) {
        if (!bindings.isArray() || bindings.isEmpty() || bindings.size() > 1000) throw invalid();
        int selected = -1;
        for (int index = 0; index < bindings.size(); index++) {
            JsonNode value = bindings.get(index);
            if (!value.isObject() || !value.path("installationPid").isTextual()) throw invalid();
            if (installation.equals(value.path("installationPid").asText())) {
                if (selected >= 0) throw invalid();
                selected = index;
            }
        }
        if (selected < 0) throw invalid();
        return selected;
    }

    private static ObjectNode object(ObjectMapper json, String text) {
        try {
            JsonNode value = json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(text);
            if (value instanceof ObjectNode object) return object;
            throw invalid();
        } catch (IOException badConfiguration) {
            throw new IllegalArgumentException("Invalid installed command fingerprint configuration", badConfiguration);
        }
    }

    private static Projection projection(ObjectMapper json, String text) {
        try (JsonParser parser = json.createParser(text)) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() != JsonToken.START_ARRAY
                        || !BINDINGS.equals(parser.getParsingContext().pathAsPointer().toString())) continue;
                var ends = new ArrayList<Integer>();
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    if (parser.currentToken() != JsonToken.START_OBJECT) throw invalid();
                    parser.skipChildren();
                    ends.add(Math.toIntExact(parser.currentLocation().getCharOffset()));
                }
                if (ends.isEmpty()) throw invalid();
                int close = Math.toIntExact(parser.currentTokenLocation().getCharOffset());
                return new Projection(text, List.copyOf(ends), close,
                        text.substring(ends.getLast(), close));
            }
            throw invalid();
        } catch (IOException badConfiguration) {
            throw new IllegalArgumentException("Invalid installed command fingerprint configuration", badConfiguration);
        }
    }

    private record Projection(String source, List<Integer> ends, int close, String trailingWhitespace) {
        String prefix(int size) {
            return source.substring(0, ends.get(size - 1)) + trailingWhitespace + source.substring(close);
        }
    }
    private record LegacyKey(String current, String installation, String original) {}
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid installed command fingerprint configuration");
    }
}
