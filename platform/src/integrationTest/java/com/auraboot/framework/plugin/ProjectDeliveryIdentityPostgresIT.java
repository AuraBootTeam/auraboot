package com.auraboot.framework.plugin;

import com.auraboot.framework.test.util.OwnedTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.assertThat;

/** Compare the exported query against its previous join predicate on real PostgreSQL fixtures. */
class ProjectDeliveryIdentityPostgresIT {
    @Test
    void publicMembershipPidPreservesOwnerLabelsAndTenantAndTombstoneRules() throws Exception {
        String url = System.getenv("TEST_DATABASE_URL");
        OwnedTestDatabase.require(url, OwnedTestDatabase.AURABOOT_SLOT_PATTERN, "An owned migrated slot is required");
        var definitions = new ObjectMapper().readTree(Files.readString(Path.of("../plugins/project-management/config/named-queries.json")));
        String query = null;
        for (var definition : definitions) {
            if ("tpm_delivery_brief".equals(definition.path("code").asText())) query = definition.path("fromSql").asText();
        }
        assertThat(query).isNotNull().contains("m.pid IS NOT NULL").doesNotContain("m.id IS NOT NULL");
        try (var connection = DriverManager.getConnection(url, System.getenv("TEST_DATABASE_USERNAME"), System.getenv("TEST_DATABASE_PASSWORD"))) {
            connection.setAutoCommit(false);
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_schema='public' AND table_name='ab_tenant_member' AND column_name='pid'", String.class))
                    .isEqualTo("NO");
            jdbc.execute("CREATE TEMP TABLE ab_user(id BIGINT PRIMARY KEY,pid TEXT,user_name TEXT,nick_name TEXT,deleted_flag BOOLEAN) ON COMMIT DROP");
            jdbc.execute("CREATE TEMP TABLE ab_tenant_member(id BIGINT PRIMARY KEY,pid TEXT NOT NULL,tenant_id BIGINT,user_id BIGINT,status TEXT,deleted_flag BOOLEAN) ON COMMIT DROP");
            jdbc.execute("CREATE TEMP TABLE mt_tpm_customer(pid TEXT,tenant_id BIGINT,tpm_cu_name TEXT) ON COMMIT DROP");
            jdbc.execute("CREATE TEMP TABLE mt_tpm_project(pid TEXT,tenant_id BIGINT,tpm_pj_code TEXT,tpm_pj_name TEXT,tpm_pj_customer_id TEXT,tpm_pj_owner_user TEXT,tpm_pj_status TEXT,tpm_pj_progress INT,tpm_pj_end_date DATE,tpm_pj_budget NUMERIC,tpm_pj_risk_summary TEXT,tpm_pj_next_action TEXT) ON COMMIT DROP");
            jdbc.update("INSERT INTO ab_user VALUES (1,'active-owner','active-login','Active owner',FALSE),(2,'wrong-tenant','wrong-login','Other tenant owner',FALSE),(3,'inactive-owner','inactive-login','Inactive owner',FALSE),(4,'deleted-owner','deleted-login','Deleted owner',TRUE)");
            jdbc.update("INSERT INTO ab_tenant_member VALUES (11,'member-active',7,1,'active',FALSE),(12,'member-other',8,2,'active',FALSE),(13,'member-inactive',7,3,'inactive',FALSE),(14,'member-deleted-user',7,4,'active',FALSE)");
            int sequence = 0;
            for (String owner : List.of("active-owner", "wrong-tenant", "inactive-owner", "deleted-owner", "missing-owner")) {
                jdbc.update("INSERT INTO mt_tpm_project(pid,tenant_id,tpm_pj_code,tpm_pj_name,tpm_pj_owner_user,tpm_pj_status) VALUES (?,7,?,?,?,'active')",
                        "project-" + sequence, "code-" + sequence, "Project " + sequence++, owner);
            }
            jdbc.update("INSERT INTO mt_tpm_project(pid,tenant_id,tpm_pj_code) VALUES ('other-project',8,'other-code')");
            String selected = query.replace("#{params.tenantId}", "?");
            var current = jdbc.queryForList(selected, 7L);
            var previous = jdbc.queryForList(selected.replace("m.pid IS NOT NULL", "m.id IS NOT NULL"), 7L);
            assertThat(current).isEqualTo(previous).hasSize(5);
            assertThat(current).extracting(row -> row.get("owner_name"))
                    .containsExactly("Active owner", "待分配", "待分配", "待分配", "待分配");
            assertThat(current).allSatisfy(row -> assertThat(row).doesNotContainKeys("id", "tenant_id", "user_id"));
            connection.rollback();
        }
    }
}
