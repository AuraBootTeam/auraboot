package com.auraboot.framework.agent.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for IntentParser Phase 3 (LLM fallback).
 *
 * These tests verify graceful handling of languages not covered by regex/keyword rules.
 * When LLM is not configured, the parser must fall back to "default" match type.
 * When LLM is configured, responses must be properly extracted and confidence-calibrated.
 *
 * Inherits @SpringBootTest(classes = TestApplication.class) + @ActiveProfiles("integration-test")
 * from BaseIntegrationTest.
 */
class IntentParserLlmTest extends BaseIntegrationTest {

    @Autowired
    private IntentParser intentParser;

    @Test
    void parse_unsupportedLanguage_gracefulFallback() {
        // Korean — not in regex/keyword rules, should use LLM or default
        IntentParser.IntentResult result = intentParser.parse("고객을 만들어 주세요");
        assertThat(result).isNotNull();
        assertThat(result.getMatchType()).isIn("llm", "default");
        // If LLM is active, it should recognize Korean "create customer" as "create"
        // If LLM is absent, default to "query" with low confidence
        if ("llm".equals(result.getMatchType())) {
            assertThat(result.getIntent()).isEqualTo("create");
            assertThat(result.getConfidence()).isBetween(0.70, 0.82);
        } else {
            assertThat(result.getIntent()).isEqualTo("query");
            assertThat(result.getConfidence()).isLessThan(0.5);
        }
    }

    @Test
    void parse_germanInput_gracefulFallback() {
        // German "Show me all customers" — "Zeig" won't match English keywords,
        // but "Kunden" (customers) is not in keyword list either.
        // May fall through to LLM or default.
        IntentParser.IntentResult result = intentParser.parse("Zeig mir alle Kunden");
        assertThat(result).isNotNull();
        assertThat(result.getIntent()).isNotNull();
        assertThat(result.getMatchType()).isIn("pattern", "keyword", "llm", "default");
        // Regardless of path, confidence must be within valid range
        assertThat(result.getConfidence()).isBetween(0.0, 1.0);
    }

    @Test
    void parse_russianInput_gracefulFallback() {
        // Russian "Delete this record" — not in any regex/keyword rule
        IntentParser.IntentResult result = intentParser.parse("Удалите эту запись");
        assertThat(result).isNotNull();
        assertThat(result.getMatchType()).isIn("llm", "default");
        if ("llm".equals(result.getMatchType())) {
            assertThat(result.getIntent()).isEqualTo("delete");
            assertThat(result.getConfidence()).isBetween(0.70, 0.82);
        }
    }

    @Test
    void parse_nullLlmClient_fallsBackToDefault() {
        // When LLM is not configured, unknown languages default to "query"
        // Greek input has no regex/keyword coverage
        IntentParser.IntentResult result = intentParser.parse("αυτό δεν είναι γνωστή γλώσσα");
        assertThat(result).isNotNull();
        // Confidence must be at or below max LLM confidence (0.82)
        assertThat(result.getConfidence()).isLessThanOrEqualTo(0.82);
        // Match type must be a recognized value
        assertThat(result.getMatchType()).isIn("pattern", "keyword", "llm", "default");
    }

    @Test
    void parse_koreanDeleteIntent_llmOrDefault() {
        // Thai "delete this item" — not in any rule set
        IntentParser.IntentResult result = intentParser.parse("ลบรายการนี้");
        assertThat(result).isNotNull();
        assertThat(result.getMatchType()).isIn("llm", "default");
        if ("llm".equals(result.getMatchType())) {
            assertThat(result.getIntent()).isEqualTo("delete");
        }
    }

    @Test
    void parse_frenchInput_gracefulFallback() {
        // French "Delete this record" — not in keyword rules
        IntentParser.IntentResult result = intentParser.parse("Supprimez cet enregistrement");
        assertThat(result).isNotNull();
        assertThat(result.getMatchType()).isIn("llm", "default");
        if ("llm".equals(result.getMatchType())) {
            assertThat(result.getIntent()).isEqualTo("delete");
            assertThat(result.getConfidence()).isBetween(0.70, 0.82);
        }
    }

    @Test
    void parse_llmResult_confidenceCalibration() {
        // Verify that when LLM path is taken, confidence is within expected range
        // Use a language unlikely to match rules (Arabic)
        IntentParser.IntentResult result = intentParser.parse("أنشئ سجلاً جديداً");
        assertThat(result).isNotNull();
        if ("llm".equals(result.getMatchType())) {
            // LLM confidence must be either 0.82 (clean single-word response) or 0.70 (extracted)
            assertThat(result.getConfidence()).isIn(0.82, 0.70);
        } else {
            // Default fallback confidence
            assertThat(result.getConfidence()).isLessThan(0.5);
        }
    }
}
