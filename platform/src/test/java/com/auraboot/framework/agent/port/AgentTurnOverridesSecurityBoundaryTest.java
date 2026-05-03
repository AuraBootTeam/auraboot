package com.auraboot.framework.agent.port;

import com.auraboot.framework.aurabot.dto.ChatRequest;
import com.auraboot.framework.conversation.TurnRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DC.3a (design v5 §10.7 Fix 1) — security-boundary test for
 * {@link AgentTurnOverrides}. Asserts that the override fields stay
 * server-only and cannot be injected through any public DTO that REST
 * controllers ({@code @RequestBody}) deserialise.
 *
 * <p>The threat model: an attacker discovers (via stack trace, leaked
 * documentation, or source code reading) that {@code AgentChatPortImpl}
 * honours fields like {@code systemPromptOverride} or {@code toolDefsOverride}
 * and tries to send a JSON body containing them to the {@code /chat/stream}
 * endpoint. Without this test, a future engineer adding such fields to
 * {@link ChatRequest} or {@link TurnRequest} could open the injection
 * vector silently.
 *
 * <p>What this test enforces:
 * <ol>
 *   <li>{@link ChatRequest} (the {@code @RequestBody} DTO) declares
 *       NONE of the AgentTurnOverrides field names.</li>
 *   <li>{@link TurnRequest} (the chokepoint internal record, also passed
 *       through SerDe in some test fixtures) declares NONE of them.</li>
 *   <li>{@link AgentTurnOverrides} lives in {@code com.auraboot.framework.agent.port}
 *       — NOT under {@code aurabot.dto} or any package that Jackson
 *       auto-scans for {@code @RequestBody} types.</li>
 * </ol>
 *
 * <p>This is structural — Jackson by default ignores unknown JSON fields,
 * so even an attacker who guesses field names won't get them deserialised.
 * The test catches the case where someone refactors AgentTurnOverrides'
 * field names back onto a public DTO (the v4 mistake the v5 review caught).
 */
@DisplayName("AgentTurnOverrides — DC.3a security boundary")
class AgentTurnOverridesSecurityBoundaryTest {

    /** The five fields on AgentTurnOverrides. If new fields are added, update this set. */
    private static final Set<String> OVERRIDES_FIELD_NAMES = Set.of(
            "systemPromptOverride",
            "messagesOverride",
            "toolDefsOverride",
            "extraTools",
            "persistSessionTape");

    @Test
    @DisplayName("ChatRequest declares NONE of the AgentTurnOverrides field names")
    void chatRequest_doesNotExposeOverrideFields() {
        Set<String> chatRequestFields = Arrays.stream(ChatRequest.class.getDeclaredFields())
                .map(Field::getName)
                .collect(java.util.stream.Collectors.toSet());

        for (String overrideField : OVERRIDES_FIELD_NAMES) {
            assertThat(chatRequestFields)
                    .as("ChatRequest must NOT declare AgentTurnOverrides field '%s' "
                            + "(prompt-injection / tool-forgery vector if exposed via @RequestBody)",
                            overrideField)
                    .doesNotContain(overrideField);
        }
    }

    @Test
    @DisplayName("TurnRequest declares NONE of the AgentTurnOverrides field names")
    void turnRequest_doesNotExposeOverrideFields() {
        Set<String> turnRequestComponents = Arrays.stream(TurnRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());

        for (String overrideField : OVERRIDES_FIELD_NAMES) {
            assertThat(turnRequestComponents)
                    .as("TurnRequest must NOT declare AgentTurnOverrides field '%s' "
                            + "(chokepoint must not propagate override fields from request)",
                            overrideField)
                    .doesNotContain(overrideField);
        }
    }

    @Test
    @DisplayName("AgentTurnOverrides lives in agent.port package — not in aurabot.dto / @RequestBody scan zones")
    void agentTurnOverrides_inCorrectPackage() {
        String pkg = AgentTurnOverrides.class.getPackageName();
        // Must NOT be a typical Jackson @RequestBody DTO scan zone:
        assertThat(pkg).doesNotContain(".dto");
        assertThat(pkg).doesNotContain(".request");
        assertThat(pkg).doesNotContain(".payload");
        // Should be in the SPI port package (where server-internal types live):
        assertThat(pkg).isEqualTo("com.auraboot.framework.agent.port");
    }

    @Test
    @DisplayName("AgentTurnOverrides class is final — no untrusted subclassing path")
    void agentTurnOverrides_isFinal() {
        assertThat(java.lang.reflect.Modifier.isFinal(AgentTurnOverrides.class.getModifiers()))
                .as("AgentTurnOverrides should be final to prevent untrusted subclasses with overridden getters")
                .isTrue();
    }

    @Test
    @DisplayName("AgentTurnOverrides Builder is the only construction path")
    void agentTurnOverrides_builderOnly() {
        // No public constructors:
        long publicCtors = Arrays.stream(AgentTurnOverrides.class.getConstructors()).count();
        assertThat(publicCtors)
                .as("AgentTurnOverrides should expose construction only through Builder, not public constructors")
                .isZero();
        // Static builder() method exists:
        boolean hasBuilder = Arrays.stream(AgentTurnOverrides.class.getMethods())
                .anyMatch(m -> "builder".equals(m.getName())
                        && java.lang.reflect.Modifier.isStatic(m.getModifiers()));
        assertThat(hasBuilder).isTrue();
    }
}
