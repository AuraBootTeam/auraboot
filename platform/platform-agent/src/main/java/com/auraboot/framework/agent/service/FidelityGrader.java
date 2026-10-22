package com.auraboot.framework.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * FidelityGrader (specs/01 §1.3 v1.1 + design/skill-substrate-contract §4).
 *
 * Grades an Action by how faithfully the stored row can reconstruct what
 * happened, and produces the v1.1 metadata fields (command_signature,
 * stdout_hash) that support learning-loop pattern aggregation.
 *
 * Per-substrate fidelity rules:
 *   full     — DSL command/query with full before/after snapshot diff
 *   semantic — API call / MCP tool: semantic change known, no DB diff
 *   blackbox — code sandbox / llm_native: only metadata + exit code observed
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FidelityGrader {

    public static final String FIDELITY_FULL = "full";
    public static final String FIDELITY_SEMANTIC = "semantic";
    public static final String FIDELITY_BLACKBOX = "blackbox";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    /**
     * Grade an Action by the tool substrate that produced it.
     * Per {@code skill-substrate-contract.md §10.1}:
     *   - dsl_command writes produce full before/after snapshots → full fidelity.
     *   - dsl_query and prompt are reads with no state delta to reconstruct
     *     → semantic fidelity (the operation is known, no diff exists to store).
     *   - api_call / mcp / connector cross opaque network boundaries; we know
     *     the semantic op but not downstream DB state → semantic.
     *   - code / llm_native run inside sandboxes where we only observe
     *     exit_code + stdout → blackbox.
     *
     * @param toolType one of dsl_command / dsl_query / api_call / mcp /
     *                 connector / prompt / code / llm_native
     */
    public String grade(String toolType) {
        if (toolType == null) return FIDELITY_BLACKBOX;
        return switch (toolType) {
            case "dsl_command"                      -> FIDELITY_FULL;
            case "dsl_query", "prompt"              -> FIDELITY_SEMANTIC;
            case "api_call", "mcp", "connector"     -> FIDELITY_SEMANTIC;
            case "code", "llm_native"               -> FIDELITY_BLACKBOX;
            default -> FIDELITY_BLACKBOX;
        };
    }

    /**
     * Canonical SHA-256 of (commandCode + sorted-key JSON of args). Two Actions
     * with the same {@code command_signature} represent the same semantic operation
     * — used by the learning loop to aggregate repeated patterns into Skill drafts
     * and by Approval Gate to detect replay requests.
     *
     * Returns {@code null} when commandCode is missing (can't dedup opaque calls).
     */
    public String commandSignature(String commandCode, Map<String, Object> args) {
        if (commandCode == null || commandCode.isBlank()) return null;
        try {
            Map<String, Object> sorted = args == null ? Map.of() : canonicalize(args);
            String payload = commandCode + "\u001f" + objectMapper.writeValueAsString(sorted);
            return sha256Hex(payload);
        } catch (Exception e) {
            log.debug("commandSignature failed for {}: {}", commandCode, e.getMessage());
            return null;
        }
    }

    /** SHA-256 of arbitrary text (e.g. stdout from a code skill). */
    public String hashText(String text) {
        if (text == null) return null;
        try {
            return sha256Hex(text);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Recursively normalise a value tree so JSON serialization is deterministic:
     *   - {@code Map} entries wrapped in {@link TreeMap} (sorted keys)
     *   - {@code List} elements normalised recursively but ORDER IS PRESERVED
     *     (list ordering is semantically meaningful; only map keys can reorder
     *     without changing meaning)
     *
     * This also handles maps nested inside lists (e.g. {@code items: [{a:1,b:2}]})
     * which the earlier map-only implementation missed.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> canonicalize(Map<String, Object> m) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            sorted.put(e.getKey(), canonicalizeValue(e.getValue()));
        }
        return sorted;
    }

    @SuppressWarnings("unchecked")
    private Object canonicalizeValue(Object v) {
        if (v instanceof Map<?, ?> nested) {
            return canonicalize((Map<String, Object>) nested);
        }
        if (v instanceof java.util.List<?> list) {
            java.util.List<Object> out = new java.util.ArrayList<>(list.size());
            for (Object el : list) {
                out.add(canonicalizeValue(el));
            }
            return out;
        }
        return v;
    }

    private String sha256Hex(String s) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(64);
        for (byte b : hash) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}
