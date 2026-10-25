package com.auraboot.framework.aurabot.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure unit test for {@link AuraBotSkillRegistry} — no Spring boot, no DB.
 *
 * <p>Drives the registry by feeding a mock {@link ApplicationContext} into
 * {@link AuraBotSkillRegistry#setApplicationContext(ApplicationContext)} and
 * firing a {@link ContextRefreshedEvent} manually. This isolates the three
 * pieces of registry contract (name regex / dedup / permission filter)
 * from any cross-cutting Spring concerns.
 */
@DisplayName("AuraBotSkillRegistry — unit (no Spring context)")
class AuraBotSkillRegistryUnitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AuraBotSkillRegistry registry;
    private ApplicationContext appContext;

    @BeforeEach
    void setUp() {
        registry = new AuraBotSkillRegistry();
        appContext = mock(ApplicationContext.class);
        registry.setApplicationContext(appContext);
    }

    private void publishWithBeans(Map<String, AuraBotSkill> beans) {
        when(appContext.getBeansOfType(AuraBotSkill.class)).thenReturn(beans);
        ContextRefreshedEvent event = mock(ContextRefreshedEvent.class);
        when(event.getApplicationContext()).thenReturn(appContext);
        registry.onContextRefreshed(event);
    }

    @Test
    @DisplayName("nameValidation — accepts SPI-spec valid names: simple, namespaced, hyphenated")
    void nameValidation_acceptsValid() {
        // Three valid name shapes from the SPI contract §5 regex.
        FakeSkill echo = FakeSkill.builder().name("echo").build();
        FakeSkill modelQuery = FakeSkill.builder().name("model:query").build();
        FakeSkill wizardCrud = FakeSkill.builder().name("wizard:crud").build();
        FakeSkill hyphen = FakeSkill.builder().name("a-b-c").build();

        Map<String, AuraBotSkill> beans = new LinkedHashMap<>();
        beans.put("echoBean", echo);
        beans.put("modelQueryBean", modelQuery);
        beans.put("wizardCrudBean", wizardCrud);
        beans.put("hyphenBean", hyphen);

        publishWithBeans(beans);

        assertThat(registry.size()).isEqualTo(4);
        assertThat(registry.exists("echo")).isTrue();
        assertThat(registry.exists("model:query")).isTrue();
        assertThat(registry.get("wizard:crud")).isPresent();
        assertThat(registry.get("a-b-c")).isPresent();
        // Compiled schema cache populated for every registered skill.
        assertThat(registry.getCompiledSchema("echo")).isPresent();
    }

    @Test
    @DisplayName("nameValidation — rejects uppercase / digit-leading / multi-colon / blank")
    void nameValidation_rejectsInvalid() {
        for (String bad : List.of(
                "Echo",            // uppercase
                "1echo",           // digit-leading
                "model:query:v2",  // multi-colon
                "echo_v1",         // underscore
                "echo!",           // punctuation
                "")) {             // blank — caught by null/blank guard before regex
            AuraBotSkillRegistry r = new AuraBotSkillRegistry();
            ApplicationContext ctx = mock(ApplicationContext.class);
            r.setApplicationContext(ctx);
            FakeSkill s = FakeSkill.builder().name(bad).build();
            when(ctx.getBeansOfType(AuraBotSkill.class)).thenReturn(Map.of("badBean", s));
            ContextRefreshedEvent ev = mock(ContextRefreshedEvent.class);
            when(ev.getApplicationContext()).thenReturn(ctx);

            assertThatThrownBy(() -> r.onContextRefreshed(ev))
                    .as("name '%s' must be rejected", bad)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("permissionFilter — excludes skills whose requiredPermissions are not held by user")
    void permissionFilter_excludesSkillsUserCannotUse() {
        FakeSkill open = FakeSkill.builder()
                .name("open")
                .requiredPermissions(Set.of())
                .build();
        FakeSkill restricted = FakeSkill.builder()
                .name("admin:reset")
                .requiredPermissions(Set.of("system:admin"))
                .build();
        FakeSkill multi = FakeSkill.builder()
                .name("model:query")
                .requiredPermissions(Set.of("model:read", "model:list"))
                .build();

        Map<String, AuraBotSkill> beans = new LinkedHashMap<>();
        beans.put("openBean", open);
        beans.put("restrictedBean", restricted);
        beans.put("multiBean", multi);
        publishWithBeans(beans);

        // User with only model:read sees `open` (no perms) — but NOT
        // model:query (missing model:list) and NOT admin:reset.
        List<SkillMeta> visible = registry.list(Set.of("model:read"));
        assertThat(visible).extracting(SkillMeta::getName).containsExactly("open");

        // Anonymous (null) collapses to empty set — only zero-perm skills.
        assertThat(registry.list(null))
                .extracting(SkillMeta::getName)
                .containsExactly("open");

        // Full perm holder sees all three, in bean discovery order.
        List<SkillMeta> all = registry.list(Set.of("system:admin", "model:read", "model:list"));
        assertThat(all).extracting(SkillMeta::getName)
                .containsExactly("open", "admin:reset", "model:query");

        // SkillMeta requiredPermissions defensively copied.
        SkillMeta multiMeta = all.stream()
                .filter(m -> m.getName().equals("model:query"))
                .findFirst().orElseThrow();
        assertThatThrownBy(() -> multiMeta.getRequiredPermissions().add("evil"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * Minimal {@link AuraBotSkill} stand-in for unit tests. Builder lets
     * each test pick exactly the surface it cares about; defaults match
     * the SPI's "low-risk no-perms echo" shape.
     */
    private static final class FakeSkill implements AuraBotSkill {
        private final String name;
        private final Set<String> requiredPermissions;
        private final JsonNode paramsSchema;

        private FakeSkill(Builder b) {
            this.name = b.name;
            this.requiredPermissions = b.requiredPermissions;
            this.paramsSchema = b.paramsSchema;
        }

        static Builder builder() {
            return new Builder();
        }

        @Override public String name() { return name; }
        @Override public String displayName() { return "Fake " + name; }
        @Override public RiskLevel riskLevel() { return RiskLevel.LOW; }
        @Override public JsonNode paramsSchema() { return paramsSchema; }
        @Override public Set<String> requiredPermissions() { return requiredPermissions; }
        @Override public SkillResult execute(SkillRequest req) {
            throw new UnsupportedOperationException("fake");
        }

        static final class Builder {
            private String name;
            private Set<String> requiredPermissions = Set.of();
            private JsonNode paramsSchema = MAPPER.createObjectNode()
                    .put("type", "object");

            Builder name(String n) { this.name = n; return this; }
            Builder requiredPermissions(Set<String> p) { this.requiredPermissions = p; return this; }
            FakeSkill build() {
                if (paramsSchema == null) {
                    paramsSchema = MAPPER.createObjectNode().put("type", "object");
                }
                return new FakeSkill(this);
            }
        }
    }

    /** Sanity helper proving the registry reads from a fresh map each call. */
    @SuppressWarnings("unused")
    private Map<String, AuraBotSkill> mutableMap(AuraBotSkill... skills) {
        Map<String, AuraBotSkill> m = new HashMap<>();
        for (int i = 0; i < skills.length; i++) {
            m.put("bean-" + i, skills[i]);
        }
        return m;
    }

    @SuppressWarnings("unused")
    private Optional<AuraBotSkill> registryGet(String name) {
        return registry.get(name);
    }
}
