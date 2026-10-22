package com.auraboot.framework.meta.ddl;

import com.auraboot.framework.plugin.event.PluginImportCompletedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL DDL/events/transactions, with discovery limited to test-owned tables. */
class SoftDeletePublicationPostgresIT {
    @Test void explicitPublicationAndStartupValidationRespectTransactionBoundaries() {
        assertEquals("crm-release-foundation", required("AURA_RUNTIME_NAME"));
        String database = required("POSTGRES_DB");
        assertEquals("auracrm_" + required("AURA_WORKSPACE_SLOT"), database);
        var dataSource = new DriverManagerDataSource(
                "jdbc:postgresql://" + required("POSTGRES_HOST") + ":" + required("POSTGRES_PORT") + "/" + database,
                required("DATABASE_USERNAME"), required("DATABASE_PASSWORD"));
        var jdbc = spy(new JdbcTemplate(dataSource));
        var manager = new DataSourceTransactionManager(dataSource);
        for (String scenario : List.of("commit", "publication-rollback", "import-rollback")) {
            String table = "mt_soft_delete_it_" + UUID.randomUUID().toString().replace("-", "");
            String invalid = table + "_bad";
            jdbc.execute("CREATE TABLE public." + table + " (marker text)");
            jdbc.execute("CREATE TABLE public." + invalid + " (deleted_flag text)");
            // The managed development database is retained; discovery cannot publish other models.
            doReturn(scenario.equals("publication-rollback") ? List.of(table, invalid) : List.of(table))
                    .when(jdbc).queryForList(anyString(), eq(String.class));
            try (var context = new AnnotationConfigApplicationContext()) {
                context.registerBean(SoftDeleteColumnInitializer.class,
                        () -> new SoftDeleteColumnInitializer(jdbc, manager));
                context.refresh();
                var initializer = context.getBean(SoftDeleteColumnInitializer.class);
                assertThrows(IllegalStateException.class, initializer::onApplicationReady);
                assertFalse(columnExists(jdbc, table));
                Runnable publish = () -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                    jdbc.update("INSERT INTO public." + table + " VALUES (?)", "retained-import");
                    context.publishEvent(new PluginImportCompletedEvent(this, 991L, "fixture"));
                    assertFalse(columnExists(jdbc, table), "publication must wait for import commit");
                    if (scenario.equals("import-rollback")) status.setRollbackOnly();
                });
                if (scenario.equals("publication-rollback")) assertThrows(IllegalStateException.class, publish::run);
                else publish.run();
                assertEquals(scenario.equals("import-rollback") ? 0 : 1,
                        jdbc.queryForObject("SELECT count(*) FROM public." + table, Integer.class));
                assertEquals(scenario.equals("commit"), columnExists(jdbc, table));
                if (scenario.equals("commit")) {
                    initializer.onApplicationReady();
                    assertEquals(Boolean.FALSE, jdbc.queryForObject(
                            "SELECT deleted_flag FROM public." + table, Boolean.class));
                    initializer.onPluginImportCompleted(new PluginImportCompletedEvent(this, 991L, "fixture"));
                    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM public." + table, Integer.class));
                } else {
                    assertThrows(IllegalStateException.class, initializer::onApplicationReady);
                }
            } finally {
                jdbc.execute("DROP TABLE public." + invalid);
                jdbc.execute("DROP TABLE public." + table);
            }
        }
    }
    private static boolean columnExists(JdbcTemplate jdbc, String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema='public' AND table_name=? AND column_name='deleted_flag')
                """, Boolean.class, table));
    }
    private static String required(String name) {
        String value = System.getenv(name);
        assertNotNull(value, name + " required");
        assertFalse(value.isBlank(), name + " must not be blank");
        return value;
    }
}
