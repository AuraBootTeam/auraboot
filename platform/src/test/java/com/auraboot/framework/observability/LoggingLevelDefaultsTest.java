package com.auraboot.framework.observability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The logger levels in application.yml are defaults, not pins. They used to be literals — three of
 * them stuck at DEBUG since the initial commit — so every environment paid for per-statement
 * transaction logging and no environment could turn it down. Each level now reads
 * LOGGING_LEVEL_&lt;PROPERTY_PATH_UPPER_SNAKE&gt;, the same name Spring Boot's relaxed binding accepts.
 *
 * <p>These tests are written against whatever the file contains rather than a hardcoded list, so a
 * future literal level, or one wired to an off-convention env var name, fails here.
 */
class LoggingLevelDefaultsTest {

    private static final String PREFIX = "logging.level.";

    /** ${LOGGING_LEVEL_SOMETHING:INFO} — group 1 is the env var, group 2 the fallback level. */
    private static final Pattern OVERRIDABLE = Pattern.compile("\\$\\{(LOGGING_LEVEL_[A-Z0-9_]+):([A-Za-z]+)}");

    private static Map<String, String> declaredLevels() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        Properties properties = factory.getObject();

        Map<String, String> levels = new TreeMap<>();
        properties.forEach((key, value) -> {
            if (key.toString().startsWith(PREFIX)) {
                levels.put(key.toString(), value.toString());
            }
        });
        return levels;
    }

    /** LOGGING_LEVEL_ORG_POSTGRESQL for logging.level.org.postgresql — the convention under test. */
    private static String expectedEnvVar(String propertyKey) {
        return propertyKey.replace('.', '_').toUpperCase(Locale.ROOT);
    }

    /**
     * Resolves against the yaml plus a stand-in for the process environment. Deliberately not a
     * StandardEnvironment: the real system environment would make the default assertions depend on
     * the shell the test happens to run in.
     */
    private static PropertySourcesPropertyResolver resolverWith(Map<String, Object> fakeEnvironment) {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addLast(new PropertiesPropertySource("application.yml", yaml()));
        sources.addFirst(new MapPropertySource("fake-environment", fakeEnvironment));
        return new PropertySourcesPropertyResolver(sources);
    }

    private static Properties yaml() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        return factory.getObject();
    }

    @Test
    void everyLoggerLevelIsOverridableByAConventionallyNamedEnvironmentVariable() {
        Map<String, String> levels = declaredLevels();

        // Guard the assertions below against passing on an empty map.
        assertThat(levels)
                .hasSizeGreaterThanOrEqualTo(7)
                .containsKeys(
                        PREFIX + "org.apache.tomcat.util.net.jsse.JSSESupport",
                        PREFIX + "org.apache.coyote",
                        PREFIX + "com.zaxxer.hikari.pool",
                        PREFIX + "org.postgresql",
                        PREFIX + "org.springframework.transaction.interceptor",
                        PREFIX + "org.springframework.transaction.support",
                        PREFIX + "org.springframework.jdbc.datasource");

        levels.forEach((key, value) -> {
            Matcher matcher = OVERRIDABLE.matcher(value);
            assertThat(matcher.matches())
                    .as("%s is pinned to '%s'; write it as ${%s:<level>} so an environment can change it",
                            key, value, expectedEnvVar(key))
                    .isTrue();
            assertThat(matcher.group(1))
                    .as("%s reads an off-convention env var; Spring Boot's relaxed binding uses the other name",
                            key)
                    .isEqualTo(expectedEnvVar(key));
        });
    }

    @Test
    void everyLoggerLevelDefaultsToInfoWhenNothingIsSet() {
        PropertySourcesPropertyResolver resolver = resolverWith(Map.of());

        declaredLevels().keySet().forEach(key ->
                assertThat(resolver.getProperty(key)).as(key).isEqualTo("INFO"));
    }

    @Test
    void everyLoggerLevelCanBeRaisedByItsEnvironmentVariable() {
        Map<String, String> levels = declaredLevels();
        Map<String, Object> fakeEnvironment = new HashMap<>();
        levels.keySet().forEach(key -> fakeEnvironment.put(expectedEnvVar(key), "TRACE"));

        PropertySourcesPropertyResolver resolver = resolverWith(fakeEnvironment);

        levels.keySet().forEach(key ->
                assertThat(resolver.getProperty(key)).as(key).isEqualTo("TRACE"));
    }
}
