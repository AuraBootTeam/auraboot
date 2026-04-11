package com.auraboot.framework.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Configurable synonym/expansion map for RAG query rewriting.
 * <p>
 * Loaded from {@code aurabot/synonyms.yml} (or any active application YAML via
 * the {@code aurabot.synonyms} prefix).  Operators can override the defaults by
 * adding entries under this prefix in their environment-specific YAML.
 *
 * <pre>
 * aurabot:
 *   synonyms:
 *     expansions:
 *       bpm:
 *         - workflow
 *         - approval
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "aurabot.synonyms")
public class SynonymConfig {

    /**
     * Query expansion map.
     * Key: trigger term (lowercase), Value: expansion terms to OR-join.
     */
    private Map<String, List<String>> expansions = Collections.emptyMap();
}
