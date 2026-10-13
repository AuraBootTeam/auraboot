package com.auraboot.framework.notification.sourcebinding;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.session.TransactionIsolationLevel;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import static org.assertj.core.api.Assertions.assertThat;

/** Test-only lifecycle, after sealed case evidence. Never controls a shared application's dispatcher. */
final class TestOwnedNotificationIntentTeardown {
    private TestOwnedNotificationIntentTeardown() {
    }
    record Scope(long tenant, String binding, String source, Set<String> references) {
        Scope {
            assertThat(tenant).isPositive();
            assertThat(binding).matches("sn[a-f0-9]{12}_notice");
            SourceBindingTypes.pid(source);
            references = Set.copyOf(references);
            assertThat(references).hasSize(2);
            for (String reference : references) {
                var locator = SourceBindingCanonical.locator(reference);
                assertThat(locator.binding()).isEqualTo(binding);
                assertThat(locator.source()).isEqualTo(source);
            }
        }
    }
    static String eventId(long tenant, String reference) throws Exception {
        byte[] identity = (tenant + "\n" + reference + "\nin_app").getBytes(StandardCharsets.UTF_8);
        return "notification-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity));
    }
    static void identity(Scope scope, JsonNode row) throws Exception {
        assertThat(row.path("tenant_id").longValue()).isEqualTo(scope.tenant());
        assertThat(row.path("schema_version").isTextual()).isTrue();
        assertThat(row.path("schema_version").asText()).isEqualTo("1.0");
        assertThat(row.path("event_source").asText()).isEqualTo("core.notification");
        assertThat(row.path("event_type").asText()).isEqualTo(RegisteredSourceNativeFixtureCleanupIT.TYPE);
        assertThat(row.path("subject").asText()).isEqualTo(scope.source());
        assertThat(row.path("payload").path("sourceId").asText()).isEqualTo(scope.source());
        assertThat(row.path("payload").path("sourceType").asText()).isEqualTo(scope.binding());
        String reference = row.path("payload").path("resolutionReference").asText();
        assertThat(scope.references()).contains(reference);
        assertThat(row.path("event_id").asText()).isEqualTo(eventId(scope.tenant(), reference));
    }
    static void check(Scope scope, JsonNode row) throws Exception {
        identity(scope, row);
        assertThat(row.path("status").asText()).isIn("pending", "failed");
        idle(row);
    }
    static void idle(JsonNode row) {
        assertThat(row.path("replay_count").isIntegralNumber()).isTrue();
        assertThat(row.path("replay_count").intValue()).isZero();
        for (String field : List.of("lease_owner", "lease_token", "lease_until")) {
            assertThat(row.has(field) && row.path(field).isNull()).isTrue();
        }
    }
    static void validateSealedRows(Scope scope, List<JsonNode> rows) throws Exception {
        assertThat(rows.size()).isLessThanOrEqualTo(scope.references().size());
        Set<String> events = new java.util.HashSet<>();
        Set<String> references = new java.util.HashSet<>();
        for (JsonNode row : rows) {
            identity(scope, row);
            idle(row);
            assertThat(row.path("status").asText()).isIn("pending", "failed", "delivered");
            assertThat(events.add(row.path("event_id").asText())).isTrue();
            assertThat(references.add(row.path("payload").path("resolutionReference").asText())).isTrue();
        }
    }
    /** Source/class qualification and pre-pause identity belong to the granted runner, not fabricated HTTP here. */
    static void run(DataSource dataSource, Scope scope, List<JsonNode> sealedRows,
                    Path sealedTrace, Path receipt) throws Exception {
        assertThat(Files.isRegularFile(sealedTrace)).isTrue();
        assertThat(Files.exists(receipt)).isFalse();
        validateSealedRows(scope, sealedRows);
        var config = new Configuration(new Environment("owned-notification-teardown", new JdbcTransactionFactory(), dataSource));
        config.addMapper(OwnedMapper.class);
        var sessions = new SqlSessionFactoryBuilder().build(config);
        Map<String, List<JsonNode>> before;
        Map<String, List<JsonNode>> after;
        int removedDeadLetters = 0;
        List<JsonNode> removable = sealedRows.stream().filter(row -> !"delivered".equals(row.path("status").asText())).toList();
        try (var session = sessions.openSession(TransactionIsolationLevel.SERIALIZABLE)) {
            var mapper = session.getMapper(OwnedMapper.class);
            before = RegisteredSourceNativeFixtureCleanupIT.snapshot(mapper);
            List<JsonNode> actualOwned = before.get("outbox").stream().filter(r -> r.path("tenant_id").longValue() == scope.tenant()).toList();
            assertThat(actualOwned).isEqualTo(sealedRows);
            for (JsonNode row : sealedRows) {
                long id = row.path("id").longValue();
                String event = row.path("event_id").asText();
                JsonNode locked = SourceBindingCanonical.parse(mapper.lockOutbox(scope.tenant(), id, event));
                identity(scope, locked);
                idle(locked);
                assertThat(locked.path("status").asText()).isEqualTo(row.path("status").asText());
                assertThat(locked.path("status").asText()).isIn("pending", "failed", "delivered");
                assertThat(RegisteredSourceNativeFixtureCleanupIT.envelopeHash(locked))
                        .isEqualTo(RegisteredSourceNativeFixtureCleanupIT.envelopeHash(row));
                assertThat(mapper.replays(scope.tenant(), event)).isZero();
                String rawDeadLetter = mapper.lockAnyDeadLetter(scope.tenant(), id, event);
                if (rawDeadLetter != null) {
                    JsonNode dead = SourceBindingCanonical.parse(rawDeadLetter);
                    assertThat(locked.path("status").asText()).isEqualTo("failed");
                    assertThat(dead.path("status").asText()).isEqualTo("open");
                    assertThat(dead.path("payload")).isEqualTo(locked.path("payload"));
                    assertThat(dead.path("event_type").asText()).isEqualTo(RegisteredSourceNativeFixtureCleanupIT.TYPE);
                }
            }
            for (JsonNode row : removable) {
                long id = row.path("id").longValue();
                String event = row.path("event_id").asText();
                if (mapper.lockAnyDeadLetter(scope.tenant(), id, event) != null) {
                    assertThat(mapper.deleteDeadLetter(scope.tenant(), id, event)).isEqualTo(1);
                    removedDeadLetters++;
                }
                assertThat(mapper.deleteOutbox(scope.tenant(), id, event)).isEqualTo(1);
            }
            after = RegisteredSourceNativeFixtureCleanupIT.snapshot(mapper);
            Set<Long> ids = removable.stream().map(row -> row.path("id").longValue()).collect(java.util.stream.Collectors.toSet());
            assertThat(after.get("outbox")).isEqualTo(before.get("outbox").stream().filter(r -> !ids.contains(r.path("id").longValue())).toList());
            assertThat(after.get("deadLetter")).isEqualTo(before.get("deadLetter").stream().filter(r -> !ids.contains(r.path("outbox_id").longValue())).toList());
            for (String table : List.of("inbox", "receipt", "replay")) assertThat(after.get(table)).isEqualTo(before.get(table));
            session.commit();
        }
        try (var session = sessions.openSession()) {
            assertThat(RegisteredSourceNativeFixtureCleanupIT.snapshot(session.getMapper(OwnedMapper.class))).isEqualTo(after);
        }
        Map<String, Object> proof = Map.of("tenantId", scope.tenant(), "binding", scope.binding(), "sourcePid", scope.source(),
                "sealedTraceSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(sealedTrace))),
                "removedPendingOrFailedIntents", removable.size(), "removedOpenDeadLetters", removedDeadLetters,
                "before", before, "persistedAfter", after, "inboxReceiptReplayPreserved", true, "deliverySuccessClaimed", false);
        Files.writeString(receipt, SourceBindingCanonical.json(proof) + "\n", StandardOpenOption.CREATE_NEW);
    }
    interface OwnedMapper extends RegisteredSourceNativeFixtureCleanupIT.CleanupMapper {
        @Select("SELECT to_jsonb(r)::text FROM ab_integration_dead_letter r WHERE tenant_id=#{tenant} AND outbox_id=#{id} AND event_id=#{event} FOR UPDATE")
        String lockAnyDeadLetter(@Param("tenant") long tenant, @Param("id") long id, @Param("event") String event);
        @Override
        @Delete("DELETE FROM ab_outbox WHERE tenant_id=#{tenant} AND id=#{id} AND event_id=#{event} AND status IN ('pending','failed') AND event_type='notification.in-app.delivery-requested.v1' AND replay_count=0 AND lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL")
        int deleteOutbox(@Param("tenant") long tenant, @Param("id") long id, @Param("event") String event);
    }
}
