package com.auraboot.framework.smoke;

import com.auraboot.framework.application.TestApplication;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring container startup smoke gate.
 *
 * <p>Verifies that the full {@link TestApplication} context wires every bean
 * without {@code UnsatisfiedDependencyException} or
 * {@code ConflictingBeanDefinitionException}. Catches:
 *
 * <ul>
 *   <li>Missing SPI provider beans (e.g. issue #339 {@code TimeSeriesPort})</li>
 *   <li>Duplicate {@code @Component} simple names (e.g. {@code SemanticValidator}
 *       in commit {@code 23b791363})</li>
 *   <li>Circular dependencies introduced by accident</li>
 *   <li>Mis-spelled / missing {@code @ConditionalOnX} predicates</li>
 * </ul>
 *
 * <p>Runs against a transient PostgreSQL 16 testcontainer so MyBatis +
 * Flyway / schema.sql validation also happens. The container is started
 * lazily on first test class load and reused across tests in the same JVM.
 *
 * <p>Designed to be cheap enough to run as a per-PR CI gate (≤ 60s on
 * GitHub Actions ubuntu runners) and discriminating enough to catch any
 * P0 bean-wiring regression before it lands on main.
 *
 * <p>Reflection trail: ida/docs/25 §3.1 (80% of session bugs traced to
 * missing runtime smoke gate); ida/docs/23 §B.5.1.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SpringContextLoadsSmokeTest {

    /**
     * Canonical schema.sql contains PL/pgSQL DO $$ ... $$ blocks that
     * TestContainers' built-in ScriptUtils cannot parse, so we copy the file
     * into the container and execute it via psql which understands the full
     * dialect.
     */
    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("aura_boot")
            .withUsername("auraboot")
            .withPassword("auraboot")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("database/schema-pg-test-bootstrap.sql"),
                    "/docker-entrypoint-initdb.d/00-schema.sql")
            // The 9000-line canonical schema takes ~60-90s to apply via the
            // entrypoint init script; bump TC's default 60s startup window so
            // the wait strategy doesn't time out before psql finishes.
            .withStartupTimeout(Duration.ofMinutes(3))
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Disable optional features that need extra services not started by this gate
        registry.add("spring.data.redis.url", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> "");
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoads() {
        // If we reach this point, every @Component / @Service / @Repository / @Controller
        // in framework.* has been instantiated successfully. The assertion below is
        // mostly cosmetic; the real verification is "@SpringBootTest didn't throw".
        assertThat(applicationContext).isNotNull();
        assertThat(applicationContext.getBeanDefinitionCount()).isGreaterThan(100);
    }

    @Test
    void noUnsatisfiedDependencies() {
        // Asserts that all beans are eagerly resolvable, not lazy-deferred. Some
        // missing SPI providers only surface on first call; force resolution here.
        for (String name : applicationContext.getBeanDefinitionNames()) {
            // Triggers eager instantiation if not already done. Failure throws
            // BeansException which fails the test.
            try {
                applicationContext.getBean(name);
            } catch (Exception e) {
                throw new AssertionError(
                        "Bean '" + name + "' failed to resolve: " + e.getMessage(), e);
            }
        }
    }
}
