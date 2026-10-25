package com.auraboot.framework.notification.sourcebinding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.session.TransactionIsolationLevel;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exact test-owned teardown only. No application context, poller, replay, delivery or production API. */
class RegisteredSourceNativeFixtureCleanupIT {
    static final Set<Long> FAILED_TENANTS = Set.of(368350341458169856L, 368350381450858496L);
    static final String TYPE = "notification.in-app.delivery-requested.v1";
    record Item(long tenant, long id, String event, String locator, String envelopeSha, JsonNode expected) {}

    static String envelopeHash(JsonNode row) {
        ObjectNode value = new ObjectMapper().createObjectNode();
        for (String key : List.of("tenant_id", "event_id", "event_type", "schema_version", "event_source",
                "command_code", "subject", "correlation_id", "causation_id", "ordering_key", "event_sequence", "payload", "headers")) {
            assertThat(row.has(key)).isTrue();
            value.set(key, row.get(key));
        }
        value.put("occurred_at", OffsetDateTime.parse(row.path("occurred_at").asText()).toInstant().toString());
        return SourceBindingCanonical.hash(value);
    }
    static void validate(Item item, JsonNode actual) {
        assertThat(FAILED_TENANTS).contains(item.tenant());
        assertThat(actual).isNotNull();
        assertThat(actual.path("tenant_id").longValue()).isEqualTo(item.tenant());
        assertThat(actual.path("id").longValue()).isEqualTo(item.id());
        assertThat(actual.path("event_id").asText()).isEqualTo(item.event());
        assertThat(actual.path("event_type").asText()).isEqualTo(TYPE);
        assertThat(actual.path("event_source").asText()).isEqualTo("core.notification");
        assertThat(actual.path("status").asText()).isEqualTo("failed");
        assertThat(actual.path("replay_count").intValue()).isZero();
        assertThat(actual.path("lease_token").isNull()).isTrue();
        assertThat(actual.path("payload").path("resolutionReference").asText()).isEqualTo(item.locator());
        assertThat(envelopeHash(actual)).isEqualTo(item.envelopeSha());
    }
    static Item item(JsonNode row) {
        return new Item(row.path("tenant_id").longValue(), row.path("id").longValue(),
                row.path("event_id").asText(), row.path("payload").path("resolutionReference").asText(), envelopeHash(row), row);
    }
    @Test
    void wrongTupleCannotPassTeardownAdmission() {
        var row = sample();
        var expected = item(row);
        ((ObjectNode) row).put("tenant_id", 368350363843170304L);
        assertThatThrownBy(() -> validate(expected, row)).isInstanceOf(AssertionError.class);
    }
    @Test
    void foreignOrChangedPayloadAndDeliveredStateCannotPassAdmission() {
        var row = sample();
        var expected = item(row);
        ((ObjectNode) row.path("payload")).put("content", "different content");
        assertThatThrownBy(() -> validate(expected, row)).isInstanceOf(AssertionError.class);
        JsonNode delivered = sample(); ((ObjectNode) delivered).put("status", "delivered");
        assertThatThrownBy(() -> validate(expected, delivered)).isInstanceOf(AssertionError.class);
    }
    static JsonNode sample() {
        ObjectNode row = (ObjectNode) SourceBindingCanonical.tree(Map.ofEntries(Map.entry("tenant_id", 368350341458169856L),
                Map.entry("id", 27), Map.entry("event_id", "notification-test"), Map.entry("event_type", TYPE),
                Map.entry("schema_version", "1.0"), Map.entry("event_source", "core.notification"),
                Map.entry("subject", "source"), Map.entry("correlation_id", "notification-test"),
                Map.entry("ordering_key", "notification-test"), Map.entry("event_sequence", 0),
                Map.entry("payload", Map.of("resolutionReference", "ns1.test", "content", "original")),
                Map.entry("headers", Map.of()), Map.entry("occurred_at", "2026-10-13T11:00:00.123456+08:00"),
                Map.entry("status", "failed"), Map.entry("replay_count", 0)));
        row.putNull("command_code"); row.putNull("causation_id"); row.putNull("lease_token");
        return row;
    }
    @Test
    @EnabledIfEnvironmentVariable(named = "S06_NATIVE_EXACT_CLEANUP", matches = "true")
    void removeOnlyAdmittedNative006FailedEventsAndOpenDeadLetters() throws Exception {
        Path directory = Path.of(System.getenv("S06_NATIVE_CLEANUP_EVIDENCE")).toRealPath();
        JsonNode admission = new ObjectMapper().readTree(Files.readString(directory.resolve("admission.json")));
        Path original = Path.of(admission.path("originalSnapshotPath").asText()).toRealPath();
        assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(original))))
                .isEqualTo("ee5344e8cb6458cc6224be48ca6195047538a6ee9335ced2e42bf6155e60d328");
        assertThat(SourceBindingCanonical.hash(new ObjectMapper().readTree(Files.readString(original))))
                .isEqualTo(admission.path("originalSnapshotSemanticSha256").asText());
        JsonNode originalRows = new ObjectMapper().readTree(Files.readString(original));
        List<JsonNode> originalFailed = new ArrayList<>();
        for (JsonNode row : originalRows.path("ab_outbox")) {
            if ("failed".equals(row.path("status").asText())) originalFailed.add(row);
        }
        assertThat(admission.path("failedEvents")).isEqualTo(new ObjectMapper().valueToTree(originalFailed));
        List<Item> items = originalFailed.stream().map(RegisteredSourceNativeFixtureCleanupIT::item).toList();
        assertThat(items).hasSize(3);
        assertThat(items.stream().map(Item::id).collect(java.util.stream.Collectors.toSet())).containsExactlyInAnyOrder(27L, 33L, 34L);
        assertThat(items.stream().map(Item::event).distinct().count()).isEqualTo(3);
        assertThat(System.getenv("TEST_DATABASE_URL").split("\\?", 2)[0]).isEqualTo("jdbc:postgresql://127.0.0.1:5432/enterprise_22");
        var source = new DriverManagerDataSource(System.getenv("TEST_DATABASE_URL"), System.getenv("TEST_DATABASE_USERNAME"), System.getenv("TEST_DATABASE_PASSWORD"));
        source.setDriverClassName("org.postgresql.Driver");
        var config = new Configuration(new Environment("exact-native-cleanup", new JdbcTransactionFactory(), source));
        config.addMapper(CleanupMapper.class);
        var sessions = new SqlSessionFactoryBuilder().build(config);
        Map<String, List<JsonNode>> before;
        Map<String, List<JsonNode>> after;
        try (SqlSession session = sessions.openSession(TransactionIsolationLevel.SERIALIZABLE)) {
            var mapper = session.getMapper(CleanupMapper.class);
            before = snapshot(mapper);
            for (var item : items) {
                JsonNode row = SourceBindingCanonical.parse(mapper.lockOutbox(item.tenant(), item.id(), item.event()));
                validate(item, row);
                Path tracePath = Path.of(admission.path("tracePaths").path(row.path("payload").path("sourceType").asText()).asText()).toRealPath();
                Path native006 = NativeProcurementWorkspace.root().resolve(".workspace/evidence/procurement-web-stack-20261010/parallel-web-20261013-s06-authority-audit/host-source-native-attempt-006").toRealPath();
                assertThat(tracePath.getParent()).isEqualTo(native006.resolve("test-traces"));
                Path classificationPath = native006.resolve("classification.json");
                assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(classificationPath))))
                        .isEqualTo(admission.path("originalClassificationSha256").asText());
                JsonNode classification = new ObjectMapper().readTree(Files.readString(classificationPath));
                List<JsonNode> exactTrace = new ArrayList<>();
                for (JsonNode classified : classification.path("cases")) {
                    if (Files.isSameFile(tracePath, Path.of(classified.path("tracePath").asText()))) exactTrace.add(classified);
                }
                assertThat(exactTrace).hasSize(1);
                assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(tracePath))))
                        .isEqualTo(exactTrace.getFirst().path("traceSha256").asText());
                JsonNode trace = new ObjectMapper().readTree(Files.readString(tracePath));
                assertThat(trace.path("identities").path("tenantId").longValue()).isEqualTo(item.tenant());
                assertThat(trace.path("identities").path("sourcePid").asText()).isEqualTo(row.path("subject").asText());
                assertThat(trace.path("approvedBinding").path("code").asText()).isEqualTo(row.path("payload").path("sourceType").asText());
                assertThat(mapper.replays(item.tenant(), item.event())).isZero();
                JsonNode dead = SourceBindingCanonical.parse(mapper.lockDeadLetter(item.tenant(), item.id(), item.event()));
                assertThat(dead.path("payload")).isEqualTo(row.path("payload"));
                assertThat(dead.path("event_type").asText()).isEqualTo(TYPE);
                assertThat(dead.path("status").asText()).isEqualTo("open");
            }
            Files.writeString(directory.resolve("transaction-before.json"), new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(before), StandardOpenOption.CREATE_NEW);
            for (var item : items) assertThat(mapper.deleteDeadLetter(item.tenant(), item.id(), item.event())).isEqualTo(1);
            for (var item : items) assertThat(mapper.deleteOutbox(item.tenant(), item.id(), item.event())).isEqualTo(1);
            after = snapshot(mapper);
            unchanged(before, after, items);
            session.commit();
        }
        try (SqlSession session = sessions.openSession()) {
            Map<String, List<JsonNode>> persisted = snapshot(session.getMapper(CleanupMapper.class));
            assertThat(persisted).isEqualTo(after);
            Files.writeString(directory.resolve("persisted-after.json"), new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(persisted), StandardOpenOption.CREATE_NEW);
        }
        Files.writeString(directory.resolve("cleanup-receipt.json"), new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "removedFailedOutbox", 3, "removedOpenDeadLetters", 3, "remainingTablesUnchanged", true,
                "noDeliveryOrReplay", true, "originalEvidencePreserved", true)), StandardOpenOption.CREATE_NEW);
    }
    static void unchanged(Map<String, List<JsonNode>> before, Map<String, List<JsonNode>> after, List<Item> items) {
        Set<Long> ids = items.stream().map(Item::id).collect(java.util.stream.Collectors.toSet());
        assertThat(before.get("outbox").size() - after.get("outbox").size()).isEqualTo(3);
        assertThat(before.get("deadLetter").size() - after.get("deadLetter").size()).isEqualTo(3);
        assertThat(after.get("outbox")).isEqualTo(before.get("outbox").stream().filter(r -> !ids.contains(r.path("id").longValue())).toList());
        assertThat(after.get("deadLetter")).isEqualTo(before.get("deadLetter").stream().filter(r -> !ids.contains(r.path("outbox_id").longValue())).toList());
        for (String table : List.of("inbox", "receipt", "replay")) assertThat(after.get(table)).isEqualTo(before.get(table));
    }
    static Map<String, List<JsonNode>> snapshot(CleanupMapper mapper) {
        return Map.of("outbox", parsed(mapper.outbox()), "deadLetter", parsed(mapper.deadLetters()),
                "inbox", parsed(mapper.inbox()), "receipt", parsed(mapper.receipts()), "replay", parsed(mapper.replayRows()));
    }
    static List<JsonNode> parsed(List<String> rows) { return rows.stream().map(SourceBindingCanonical::parse).toList(); }
    interface CleanupMapper {
        @Select("SELECT to_jsonb(r)::text FROM ab_outbox r WHERE tenant_id=#{tenant} AND id=#{id} AND event_id=#{event} FOR UPDATE")
        String lockOutbox(@Param("tenant") long tenant, @Param("id") long id, @Param("event") String event);
        @Select("SELECT to_jsonb(r)::text FROM ab_integration_dead_letter r WHERE tenant_id=#{tenant} AND outbox_id=#{id} AND event_id=#{event} AND status='open' FOR UPDATE")
        String lockDeadLetter(@Param("tenant") long tenant, @Param("id") long id, @Param("event") String event);
        @Select("SELECT count(*) FROM ab_integration_dead_letter_replay WHERE tenant_id=#{tenant} AND event_id=#{event}")
        long replays(@Param("tenant") long tenant, @Param("event") String event);
        @Delete("DELETE FROM ab_integration_dead_letter WHERE tenant_id=#{tenant} AND outbox_id=#{id} AND event_id=#{event} AND status='open'")
        int deleteDeadLetter(@Param("tenant") long tenant, @Param("id") long id, @Param("event") String event);
        @Delete("DELETE FROM ab_outbox WHERE tenant_id=#{tenant} AND id=#{id} AND event_id=#{event} AND status='failed' AND event_type='notification.in-app.delivery-requested.v1' AND replay_count=0 AND lease_token IS NULL")
        int deleteOutbox(@Param("tenant") long tenant, @Param("id") long id, @Param("event") String event);
        @Select("SELECT to_jsonb(r)::text FROM ab_outbox r ORDER BY id") List<String> outbox();
        @Select("SELECT to_jsonb(r)::text FROM ab_integration_dead_letter r ORDER BY id") List<String> deadLetters();
        @Select("SELECT to_jsonb(r)::text FROM ab_notification r ORDER BY id") List<String> inbox();
        @Select("SELECT to_jsonb(r)::text FROM ab_integration_receipt r ORDER BY tenant_id,event_id,consumer_code") List<String> receipts();
        @Select("SELECT to_jsonb(r)::text FROM ab_integration_dead_letter_replay r ORDER BY tenant_id,event_id,replay_attempt") List<String> replayRows();
    }
}
