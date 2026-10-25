package com.auraboot.framework.aurabot.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
 * <p><strong>Test bean registration.</strong> Beans are registered via
 * {@link ApplicationContextRunner#withBean} rather than
 * {@code @Configuration} / {@code @TestConfiguration} static inner classes.
 * Reason: {@code TestApplication} declares an explicit
 * {@code @ComponentScan(basePackages = "com.auraboot.framework")} which
 * <em>bypasses</em> the {@code TestConfiguration} exclusion baked into
 * {@code @SpringBootApplication}. A nested {@code @Configuration} (or
 * {@code @TestConfiguration}) class anywhere under that base package gets
 * picked up by every other IT in the JVM — a duplicate-name fixture would
 * then trip the registry's fail-fast guard during unrelated context loads.
 *
 * <p>This test does <em>not</em> need PostgreSQL or Redis — schema
 * compilation runs in-process against {@code networknt/json-schema-validator}.
 */
@DisplayName("AuraBotSkillRegistry — bootstrap (ApplicationContextRunner)")
class AuraBotSkillRegistryBootstrapIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean("auraBotSkillRegistry", AuraBotSkillRegistry.class, AuraBotSkillRegistry::new);

    @Test
    @DisplayName("bootstrap fails fast when two beans share the same skill name()")
    void bootstrap_failsOnDuplicateName() {
        runner
                .withBean("firstCollision", AuraBotSkill.class, () -> new TestSkill("collision"))
                .withBean("secondCollision", AuraBotSkill.class, () -> new TestSkill("collision"))
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
        runner
                .withBean("alphaSkill", AuraBotSkill.class, () -> new TestSkill("alpha"))
                .withBean("betaListSkill", AuraBotSkill.class, () -> new TestSkill("beta:list"))
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

    /**
     * Minimal SPI impl for bootstrap fixtures. Uses a trivial
     * {@code {"type":"object"}} schema — validation correctness is the
     * Validator's responsibility (Step 5), not the registry's.
     *
     * <p>Declared {@code private static} so it cannot be component-scanned
     * by {@code TestApplication}'s explicit {@code @ComponentScan}.
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
