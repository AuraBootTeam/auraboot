package com.auraboot.framework.aurabot.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bootstrap-level IT for {@link AuraBotSkillRegistry} (Plan Step 4).
 *
 * <p>Uses {@link ApplicationContextRunner} to spin a <em>throw-away</em>
 * Spring context for each scenario — the main {@code BaseIntegrationTest}
 * context is intentionally avoided here because the registry's fail-fast
 * duplicate-name check, if exercised in the shared IT context, would
 * poison every downstream IT in the same JVM.
 *
 * <p>This test does <em>not</em> need PostgreSQL or Redis — schema
 * compilation runs in-process against {@code networknt/json-schema-validator}.
 */
@DisplayName("AuraBotSkillRegistry — bootstrap (ApplicationContextRunner)")
class AuraBotSkillRegistryBootstrapIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RegistryConfig.class);

    @Test
    @DisplayName("bootstrap fails fast when two beans share the same skill name()")
    void bootstrap_failsOnDuplicateName() {
        runner.withUserConfiguration(DuplicateNameConfig.class)
                .run(context -> {
                    // Registry throws IllegalStateException straight from
                    // its @EventListener — Spring re-publishes it without
                    // wrapping, so the startup failure IS the ISE rather
                    // than a wrapper. Its message must surface the
                    // offending name — operators need that token to grep
                    // service logs.
                    assertThat(context).hasFailed();
                    Throwable failure = context.getStartupFailure();
                    Throwable cause = failure;
                    while (cause.getCause() != null && cause.getCause() != cause) {
                        cause = cause.getCause();
                    }
                    assertThat(cause)
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("duplicate skill name")
                            .hasMessageContaining("collision");
                });
    }

    @Test
    @DisplayName("bootstrap succeeds with two unique-name skills; registry.get returns both")
    void bootstrap_succeedsWithUniqueNames() {
        runner.withUserConfiguration(UniqueNamesConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AuraBotSkillRegistry.class);

                    AuraBotSkillRegistry registry =
                            context.getBean(AuraBotSkillRegistry.class);
                    assertThat(registry.size()).isEqualTo(2);
                    assertThat(registry.get("alpha")).isPresent();
                    assertThat(registry.get("beta:list")).isPresent();
                    assertThat(registry.get("missing")).isEmpty();

                    // Compiled schema cache populated for every registered
                    // skill — Validator (Step 5) leans on this.
                    assertThat(registry.getCompiledSchema("alpha")).isPresent();
                    assertThat(registry.getCompiledSchema("beta:list")).isPresent();

                    // Permission filter sanity (richer cases live in unit test).
                    assertThat(registry.list(Set.of()))
                            .extracting(SkillMeta::getName)
                            .containsExactlyInAnyOrder("alpha", "beta:list");
                });
    }

    // ---------------------------------------------------------------
    // Test-only @Configuration classes
    // ---------------------------------------------------------------

    /** Bare scaffold — registers only the registry bean. */
    @Configuration
    static class RegistryConfig {
        @Bean
        AuraBotSkillRegistry auraBotSkillRegistry() {
            return new AuraBotSkillRegistry();
        }
    }

    /**
     * Two beans both returning {@code name() == "collision"} — must trip
     * the fail-fast guard.
     */
    @Configuration
    static class DuplicateNameConfig {
        @Bean
        AuraBotSkill firstCollision() {
            return new TestSkill("collision");
        }

        @Bean
        AuraBotSkill secondCollision() {
            return new TestSkill("collision");
        }
    }

    /** Two unique skills — should boot cleanly. */
    @Configuration
    static class UniqueNamesConfig {
        @Bean
        AuraBotSkill alphaSkill() {
            return new TestSkill("alpha");
        }

        @Bean
        AuraBotSkill betaListSkill() {
            return new TestSkill("beta:list");
        }
    }

    /**
     * Minimal SPI impl for bootstrap fixtures. Uses a trivial
     * {@code {"type":"object"}} schema — validation correctness is the
     * Validator's responsibility (Step 5), not the registry's.
     */
    private static final class TestSkill implements AuraBotSkill {
        private final String name;

        TestSkill(String name) {
            this.name = name;
        }

        @Override public String name() { return name; }
        @Override public String displayName() { return "Test " + name; }
        @Override public RiskLevel riskLevel() { return RiskLevel.LOW; }
        @Override public JsonNode paramsSchema() {
            return MAPPER.createObjectNode().put("type", "object");
        }
        @Override public SkillResult execute(SkillRequest req) {
            throw new UnsupportedOperationException("test skill");
        }
    }
}
