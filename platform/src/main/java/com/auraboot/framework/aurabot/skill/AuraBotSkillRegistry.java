package com.auraboot.framework.aurabot.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Registry for {@link AuraBotSkill} beans (Plan Step 4).
 *
 * <p>Scans the {@link ApplicationContext} once on
 * {@link ContextRefreshedEvent} (post-bootstrap, so all {@code @Component}
 * skills are visible) and:
 * <ul>
 *   <li>Validates each {@link AuraBotSkill#name()} against the SPI contract
 *       regex {@code ^[a-z][a-z0-9-]*(:[a-z][a-z0-9-]*)?$}.</li>
 *   <li>Fails fast (throws {@link IllegalStateException}) on duplicate
 *       {@code name()} so the application never reaches a usable state with
 *       an ambiguous skill table.</li>
 *   <li>Pre-compiles each {@link AuraBotSkill#paramsSchema()} into a
 *       {@link JsonSchema} (Draft 2020-12) and caches it — Validator
 *       (Step 5) reuses these handles per request, avoiding repeated
 *       JSON-schema parse overhead (see plan §Risks R4).</li>
 * </ul>
 *
 * <p>Permission filtering for {@link #list(Set)} performs a strict subset
 * check: every entry in {@link AuraBotSkill#requiredPermissions()} must
 * appear in the supplied user-permission set. Empty
 * {@code requiredPermissions()} → always visible.
 *
 * <p>The registry is intentionally <em>immutable</em> after bootstrap:
 * dynamic plugin add/remove is out of scope for SPI v1; PF4J-loaded skills
 * still arrive as Spring beans before {@code ContextRefreshedEvent} fires.
 */
@Slf4j
@Component
public class AuraBotSkillRegistry implements ApplicationContextAware {

    /** SPI contract §5 — skill name regex. */
    static final Pattern NAME_PATTERN =
            Pattern.compile("^[a-z][a-z0-9-]*(:[a-z][a-z0-9-]*)?$");

    private final JsonSchemaFactory schemaFactory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private ApplicationContext applicationContext;

    /** Populated on {@link ContextRefreshedEvent}; immutable thereafter. */
    private volatile Map<String, AuraBotSkill> skillsByName = Collections.emptyMap();

    /** Pre-compiled JSON-schema handles, keyed by skill name. */
    private volatile Map<String, JsonSchema> compiledSchemasByName = Collections.emptyMap();

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * One-shot bootstrap. Spring may publish {@link ContextRefreshedEvent}
     * more than once (e.g. context refresh in tests), so guard with the
     * {@code skillsByName} volatile — the registry intentionally rebuilds
     * from the (possibly different) bean set rather than memoising.
     */
    @EventListener(ContextRefreshedEvent.class)
    public synchronized void onContextRefreshed(ContextRefreshedEvent event) {
        // Only react when the event's context is the one we're wired into —
        // avoids double-init when child contexts refresh.
        if (event.getApplicationContext() != this.applicationContext) {
            return;
        }
        Map<String, AuraBotSkill> beans =
                applicationContext.getBeansOfType(AuraBotSkill.class);

        // Use LinkedHashMap to keep deterministic discovery order for tests
        // / observability ("which skill collided with which?" stays stable).
        Map<String, AuraBotSkill> byName = new LinkedHashMap<>(beans.size() * 2);
        Map<String, JsonSchema> compiled = new LinkedHashMap<>(beans.size() * 2);

        for (Map.Entry<String, AuraBotSkill> entry : beans.entrySet()) {
            AuraBotSkill skill = entry.getValue();
            String name = skill.name();

            if (name == null || name.isBlank()) {
                throw new IllegalStateException(
                        "AuraBotSkill bean '" + entry.getKey()
                                + "' returned null/blank name()");
            }
            if (!NAME_PATTERN.matcher(name).matches()) {
                throw new IllegalStateException(
                        "invalid skill name: '" + name + "' — must match "
                                + NAME_PATTERN.pattern());
            }
            AuraBotSkill prior = byName.put(name, skill);
            if (prior != null) {
                throw new IllegalStateException(
                        "duplicate skill name: " + name
                                + " (registered by " + prior.getClass().getName()
                                + " and " + skill.getClass().getName() + ")");
            }

            JsonNode schemaNode = skill.paramsSchema();
            if (schemaNode == null) {
                throw new IllegalStateException(
                        "skill '" + name + "' returned null paramsSchema()");
            }
            // schemaFactory.getSchema validates structural well-formedness.
            // Failures here surface at startup — caller sees the offending
            // skill name, not a runtime 500 on first /execute call.
            compiled.put(name, schemaFactory.getSchema(schemaNode));
        }

        this.skillsByName = Collections.unmodifiableMap(byName);
        this.compiledSchemasByName = Collections.unmodifiableMap(compiled);

        log.info("AuraBotSkillRegistry initialised: {} skill(s) registered: {}",
                byName.size(), byName.keySet());
    }

    /**
     * Lookup by canonical {@code name()}.
     */
    public Optional<AuraBotSkill> get(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(skillsByName.get(name));
    }

    /**
     * @return {@code true} if a skill with this name is registered. Used by
     *         the request validator to short-circuit unknown-skill 400s
     *         before doing any params parsing.
     */
    public boolean exists(String name) {
        return name != null && skillsByName.containsKey(name);
    }

    /**
     * Pre-compiled JSON-schema handle for {@code skillName}. Validator
     * (Step 5) calls this on every request — if you need the raw
     * {@link JsonNode} (e.g. for FE discovery), use {@link AuraBotSkill#paramsSchema()}
     * via {@link #get(String)}.
     */
    public Optional<JsonSchema> getCompiledSchema(String skillName) {
        if (skillName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(compiledSchemasByName.get(skillName));
    }

    /**
     * Skills visible to a user with {@code currentUserPermissions}. A skill
     * is included when {@code skill.requiredPermissions() ⊆
     * currentUserPermissions}. {@code null} permission set is treated as
     * empty (anonymous user — only no-permission skills surface).
     *
     * <p>Output order matches Spring's bean discovery order so the FE
     * picker UI is deterministic across requests.
     */
    public List<SkillMeta> list(Set<String> currentUserPermissions) {
        Set<String> userPerms = currentUserPermissions == null
                ? Collections.emptySet()
                : currentUserPermissions;

        return skillsByName.values().stream()
                .filter(s -> userPerms.containsAll(s.requiredPermissions()))
                .map(this::toMeta)
                .collect(Collectors.toList());
    }

    /**
     * Test-only / introspection: total registered skill count. Avoids
     * exposing the internal map.
     */
    int size() {
        return skillsByName.size();
    }

    private SkillMeta toMeta(AuraBotSkill s) {
        // Defensive copy of requiredPermissions — caller should not be able
        // to mutate the skill's own set via the meta payload.
        Set<String> perms = s.requiredPermissions() == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(s.requiredPermissions()));

        return SkillMeta.builder()
                .name(s.name())
                .displayName(s.displayName())
                .category(s.category())
                .riskLevel(s.riskLevel().name())
                .paramsSchema(s.paramsSchema())
                .requiredPermissions(perms)
                .supportsUndo(s.supportsUndo())
                .supportsDryRun(s.supportsDryRun())
                .supportsStreaming(s.supportsStreaming())
                .build();
    }
}
