package com.auraboot.framework.behavior.sitekey;

import com.auraboot.framework.meta.dto.IndexType;
import com.auraboot.framework.meta.dto.SchemaOperationResult;
import com.auraboot.framework.meta.service.SchemaManagementService;
import com.auraboot.framework.plugin.event.PluginImportCompletedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL DDL and Spring transaction callbacks; model-service dispatch is a fixture. */
class SiteKeyPublicationPostgresIT {
    @Test
    void publicationCommitRollbackAndImportRollbackUseIndependentTransactions() {
        assertEquals("crm-release-foundation", System.getenv("AURA_RUNTIME_NAME"), "managed runtime required");
        String database = required("POSTGRES_DB");
        assertEquals("auracrm_" + required("AURA_WORKSPACE_SLOT"), database);
        var dataSource = new DriverManagerDataSource(
                "jdbc:postgresql://" + required("POSTGRES_HOST") + ":" + required("POSTGRES_PORT") + "/" + database,
                required("DATABASE_USERNAME"), required("DATABASE_PASSWORD"));
        var jdbc = new JdbcTemplate(dataSource);
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='public' AND c.relkind IN ('r','p','v','m','f','S')
                """, Integer.class), "fixture refuses a populated schema");
        var manager = new DataSourceTransactionManager(dataSource);
        for (String scenario : new String[]{"commit", "publication-rollback", "import-rollback"}) {
            jdbc.execute("CREATE TABLE public.mt_behavior_site_key(site_key text, tenant_id bigint)");
            try {
                var schema = mock(SchemaManagementService.class);
                when(schema.createFieldIndex("behavior_site_key", "site_key", IndexType.UNIQUE)).thenAnswer(invocation -> {
                    jdbc.execute("CREATE UNIQUE INDEX site_key_publication_fixture ON public.mt_behavior_site_key(site_key)");
                    // A later publication failure must roll back even an already successful DDL statement.
                    return SchemaOperationResult.builder().success(!scenario.equals("publication-rollback")).build();
                });
                try (var context = new AnnotationConfigApplicationContext()) {
                    context.registerBean(SiteKeyIndexInitializer.class,
                            () -> new SiteKeyIndexInitializer(schema, jdbc, manager));
                    context.refresh();
                    Runnable publish = () -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                        jdbc.update("INSERT INTO public.mt_behavior_site_key VALUES (?, ?)", "retained-import-row", 991L);
                        context.publishEvent(new PluginImportCompletedEvent(this, 991L, "behavior"));
                        verifyNoInteractions(schema);
                        assertNull(jdbc.queryForObject("SELECT to_regclass('public.site_key_publication_fixture')", String.class));
                        if (scenario.equals("import-rollback")) status.setRollbackOnly();
                    });
                    if (scenario.equals("publication-rollback")) assertThrows(IllegalStateException.class, publish::run);
                    else publish.run();
                    assertEquals(scenario.equals("import-rollback") ? 0 : 1,
                            jdbc.queryForObject("SELECT count(*) FROM public.mt_behavior_site_key", Integer.class));
                    String index = jdbc.queryForObject("SELECT to_regclass('public.site_key_publication_fixture')", String.class);
                    var initializer = context.getBean(SiteKeyIndexInitializer.class);
                    if (scenario.equals("commit")) {
                        assertNotNull(index);
                        initializer.onApplicationReady();
                        verify(schema, times(1)).createFieldIndex("behavior_site_key", "site_key", IndexType.UNIQUE);
                    } else {
                        assertNull(index);
                        assertThrows(IllegalStateException.class, initializer::onApplicationReady);
                        if (scenario.equals("import-rollback")) verifyNoInteractions(schema);
                    }
                }
            } finally {
                // Only the table created by this test is removed; runtime and database are retained.
                jdbc.execute("DROP TABLE public.mt_behavior_site_key");
            }
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        assertNotNull(value, name + " is required");
        assertFalse(value.isBlank(), name + " must not be blank");
        return value;
    }
}
