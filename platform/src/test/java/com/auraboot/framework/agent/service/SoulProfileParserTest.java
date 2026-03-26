package com.auraboot.framework.agent.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for SoulProfileParser.
 * Covers: structured JSON, plain-text fallback, null/blank inputs, prompt section rendering.
 */
class SoulProfileParserTest {

    // =========================================================================
    // Structured JSON
    // =========================================================================

    @Test
    void parse_structuredJson_returnsAllFields() {
        String json = """
                {
                  "persona": "AuraBot, a versatile enterprise AI assistant focused on process optimization",
                  "values": ["efficiency", "data-driven", "compliance-first"],
                  "tone": "professional",
                  "tone_description": "Professional yet approachable, concise, avoids unnecessary filler",
                  "boundaries": [
                    "Never perform operations beyond authorized scope — proactively inform the user",
                    "Never repeat sensitive data in output"
                  ],
                  "greeting": "Hello, I'm AuraBot. How can I help you today?",
                  "language_preference": "zh-CN"
                }
                """;

        Map<String, Object> profile = SoulProfileParser.parse(json);

        assertThat(profile).isNotEmpty();
        assertThat(SoulProfileParser.getPersona(profile))
                .isEqualTo("AuraBot, a versatile enterprise AI assistant focused on process optimization");
        assertThat(SoulProfileParser.getValues(profile))
                .containsExactly("efficiency", "data-driven", "compliance-first");
        assertThat(SoulProfileParser.getTone(profile)).isEqualTo("professional");
        assertThat(SoulProfileParser.getToneDescription(profile))
                .isEqualTo("Professional yet approachable, concise, avoids unnecessary filler");
        assertThat(SoulProfileParser.getBoundaries(profile)).hasSize(2);
        assertThat(SoulProfileParser.getBoundaries(profile).get(0))
                .contains("Never perform operations beyond authorized scope");
        assertThat(SoulProfileParser.getGreeting(profile))
                .isEqualTo("Hello, I'm AuraBot. How can I help you today?");
        assertThat(SoulProfileParser.getLanguagePreference(profile)).isEqualTo("zh-CN");
    }

    @Test
    void parse_approvalAssistantJson_returnsExpectedValues() {
        String json = """
                {
                  "persona": "A specialized approval workflow assistant, focused on policy compliance",
                  "values": ["accuracy", "policy-compliance", "timeliness"],
                  "tone": "formal",
                  "tone_description": "Formal and precise, always cites relevant policies",
                  "boundaries": [
                    "Only process approvals within delegated authority",
                    "Always explain rejection reasons clearly"
                  ],
                  "greeting": "I'm your Approval Assistant. I can help review and process pending approvals.",
                  "language_preference": "zh-CN"
                }
                """;

        Map<String, Object> profile = SoulProfileParser.parse(json);

        assertThat(SoulProfileParser.getTone(profile)).isEqualTo("formal");
        assertThat(SoulProfileParser.getValues(profile))
                .containsExactly("accuracy", "policy-compliance", "timeliness");
        assertThat(SoulProfileParser.getBoundaries(profile))
                .containsExactly(
                        "Only process approvals within delegated authority",
                        "Always explain rejection reasons clearly"
                );
    }

    @Test
    void parse_customerServiceJson_returnsExpectedValues() {
        String json = """
                {
                  "persona": "A patient and empathetic customer service agent",
                  "values": ["customer-first", "patience", "problem-resolution"],
                  "tone": "friendly",
                  "tone_description": "Warm and patient, acknowledges customer frustration before problem-solving",
                  "boundaries": [
                    "Never share internal system details with customers",
                    "Escalate to human agent when unable to resolve within 3 attempts"
                  ],
                  "greeting": "Hi! I'm here to help. What can I do for you?",
                  "language_preference": "zh-CN"
                }
                """;

        Map<String, Object> profile = SoulProfileParser.parse(json);

        assertThat(SoulProfileParser.getTone(profile)).isEqualTo("friendly");
        assertThat(SoulProfileParser.getPersona(profile))
                .isEqualTo("A patient and empathetic customer service agent");
        assertThat(SoulProfileParser.getBoundaries(profile)).hasSize(2);
        assertThat(SoulProfileParser.getGreeting(profile))
                .isEqualTo("Hi! I'm here to help. What can I do for you?");
    }

    // =========================================================================
    // Fallback: plain text treated as persona
    // =========================================================================

    @Test
    void parse_plainText_fallsBackToPersonaKey() {
        String plainText = "A helpful enterprise assistant with expertise in finance";

        Map<String, Object> profile = SoulProfileParser.parse(plainText);

        assertThat(profile).hasSize(1);
        assertThat(SoulProfileParser.getPersona(profile))
                .isEqualTo("A helpful enterprise assistant with expertise in finance");
        assertThat(SoulProfileParser.getValues(profile)).isEmpty();
        assertThat(SoulProfileParser.getBoundaries(profile)).isEmpty();
        assertThat(SoulProfileParser.getTone(profile)).isNull();
        assertThat(SoulProfileParser.getGreeting(profile)).isNull();
    }

    @Test
    void parse_invalidJsonFragment_fallsBackToPersonaKey() {
        String badJson = "{ not valid json }";

        Map<String, Object> profile = SoulProfileParser.parse(badJson);

        assertThat(SoulProfileParser.getPersona(profile)).isEqualTo("{ not valid json }");
    }

    // =========================================================================
    // Null / blank inputs
    // =========================================================================

    @Test
    void parse_null_returnsEmptyMap() {
        Map<String, Object> profile = SoulProfileParser.parse(null);
        assertThat(profile).isEmpty();
    }

    @Test
    void parse_blank_returnsEmptyMap() {
        Map<String, Object> profile = SoulProfileParser.parse("   ");
        assertThat(profile).isEmpty();
    }

    @Test
    void parse_emptyString_returnsEmptyMap() {
        Map<String, Object> profile = SoulProfileParser.parse("");
        assertThat(profile).isEmpty();
    }

    // =========================================================================
    // Partial JSON — missing keys return null / empty
    // =========================================================================

    @Test
    void parse_partialJson_missingKeysReturnNullOrEmpty() {
        String json = """
                {
                  "persona": "A minimal agent",
                  "tone": "professional"
                }
                """;

        Map<String, Object> profile = SoulProfileParser.parse(json);

        assertThat(SoulProfileParser.getPersona(profile)).isEqualTo("A minimal agent");
        assertThat(SoulProfileParser.getTone(profile)).isEqualTo("professional");
        assertThat(SoulProfileParser.getValues(profile)).isEmpty();
        assertThat(SoulProfileParser.getBoundaries(profile)).isEmpty();
        assertThat(SoulProfileParser.getGreeting(profile)).isNull();
        assertThat(SoulProfileParser.getLanguagePreference(profile)).isNull();
    }

    // =========================================================================
    // toPromptSection rendering
    // =========================================================================

    @Test
    void toPromptSection_emptyProfile_returnsEmptyString() {
        String section = SoulProfileParser.toPromptSection(Map.of());
        assertThat(section).isEmpty();
    }

    @Test
    void toPromptSection_fullProfile_containsExpectedFragments() {
        Map<String, Object> profile = SoulProfileParser.parse("""
                {
                  "persona": "AuraBot, a versatile enterprise AI assistant",
                  "values": ["efficiency", "compliance-first"],
                  "tone": "professional",
                  "tone_description": "Professional yet approachable",
                  "boundaries": [
                    "Never perform operations beyond authorized scope",
                    "Never repeat sensitive data in output"
                  ],
                  "greeting": "Hello!"
                }
                """);

        String section = SoulProfileParser.toPromptSection(profile);

        assertThat(section).contains("## Agent Soul Profile");
        assertThat(section).contains("**Persona**: AuraBot, a versatile enterprise AI assistant");
        assertThat(section).contains("efficiency");
        assertThat(section).contains("compliance-first");
        assertThat(section).contains("Professional yet approachable");
        assertThat(section).contains("## Behavioural Boundaries (MUST respect)");
        assertThat(section).contains("Never perform operations beyond authorized scope");
        assertThat(section).contains("Never repeat sensitive data in output");
        // greeting is not rendered in the prompt section (it's used for UI only)
    }

    @Test
    void toPromptSection_toneKeyword_usedWhenNoDescription() {
        Map<String, Object> profile = SoulProfileParser.parse("""
                {
                  "persona": "A simple agent",
                  "tone": "formal"
                }
                """);

        String section = SoulProfileParser.toPromptSection(profile);

        assertThat(section).contains("**Tone**: formal");
    }

    @Test
    void toPromptSection_plainTextFallback_rendersPersona() {
        Map<String, Object> profile = SoulProfileParser.parse("A helpful assistant");

        String section = SoulProfileParser.toPromptSection(profile);

        assertThat(section).contains("**Persona**: A helpful assistant");
    }

    // =========================================================================
    // Convenience accessors on empty profile
    // =========================================================================

    @Test
    void accessors_emptyProfile_returnNullOrEmpty() {
        Map<String, Object> profile = Map.of();

        assertThat(SoulProfileParser.getPersona(profile)).isNull();
        assertThat(SoulProfileParser.getValues(profile)).isEmpty();
        assertThat(SoulProfileParser.getTone(profile)).isNull();
        assertThat(SoulProfileParser.getToneDescription(profile)).isNull();
        assertThat(SoulProfileParser.getBoundaries(profile)).isEmpty();
        assertThat(SoulProfileParser.getGreeting(profile)).isNull();
        assertThat(SoulProfileParser.getLanguagePreference(profile)).isNull();
    }
}
