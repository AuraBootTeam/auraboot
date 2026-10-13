package com.auraboot.framework.notification.sourcebinding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import java.util.List;
import java.nio.file.Path;
import java.nio.file.Files;
import javax.sql.DataSource;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Pure identity and terminal-state refusal, without creating a datasource or invoking teardown. */
class TestOwnedNotificationIntentTeardownTest {
    @TempDir Path directory;
    private final String binding = "sn123456abcdef_notice";
    private final String source = "source";
    private final String reference = SourceBindingCanonical.reference(binding, source, 1);
    private final TestOwnedNotificationIntentTeardown.Scope scope = new TestOwnedNotificationIntentTeardown.Scope(
            368350341458169856L, binding, source,
            Set.of(reference, SourceBindingCanonical.reference(binding, source, 2)));
    private JsonNode row() throws Exception {
        ObjectNode row = (ObjectNode) RegisteredSourceNativeFixtureCleanupIT.sample();
        row.put("event_id", TestOwnedNotificationIntentTeardown.eventId(scope.tenant(), reference));
        row.put("subject", source);
        row.put("status", "pending");
        row.putNull("lease_owner");
        row.putNull("lease_until");
        ObjectNode payload = (ObjectNode) row.path("payload");
        payload.put("resolutionReference", reference).put("sourceId", source).put("sourceType", binding);
        return row;
    }
    @Test
    void onlyExactOwnedEnvelopeAndIdlePendingOrFailedStateAdmitted() throws Exception {
        JsonNode row = row();
        TestOwnedNotificationIntentTeardown.check(scope, row);
        ((ObjectNode) row).put("status", "failed");
        TestOwnedNotificationIntentTeardown.check(scope, row);
        ((ObjectNode) row).put("status", "processing");
        assertThatThrownBy(() -> TestOwnedNotificationIntentTeardown.check(scope, row)).isInstanceOf(AssertionError.class);
    }
    @Test
    void wrongTenantSourceBindingReferenceAndEventIdentityRefused() throws Exception {
        for (String field : new String[]{"tenant_id", "subject", "event_id"}) {
            ObjectNode row = (ObjectNode) row();
            if (field.equals("tenant_id")) row.put(field, scope.tenant() + 1); else row.put(field, "foreign");
            assertThatThrownBy(() -> TestOwnedNotificationIntentTeardown.check(scope, row)).isInstanceOf(AssertionError.class);
        }
        JsonNode row = row();
        ((ObjectNode) row.path("payload")).put("resolutionReference", SourceBindingCanonical.reference(binding, source, 3));
        assertThatThrownBy(() -> TestOwnedNotificationIntentTeardown.check(scope, row)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> new TestOwnedNotificationIntentTeardown.Scope(scope.tenant(), binding, source,
                Set.of(reference, SourceBindingCanonical.reference("snabcdef123456_notice", source, 2))))
                .isInstanceOf(AssertionError.class);
    }
    @Test
    void leaseReplayAndDeliveredIntentCannotBeDeleted() throws Exception {
        JsonNode row = row();
        ((ObjectNode) row).put("lease_owner", "foreign worker");
        assertThatThrownBy(() -> TestOwnedNotificationIntentTeardown.check(scope, row)).isInstanceOf(AssertionError.class);
        JsonNode replayed = row();
        ((ObjectNode) replayed).put("replay_count", 1);
        assertThatThrownBy(() -> TestOwnedNotificationIntentTeardown.check(scope, replayed)).isInstanceOf(AssertionError.class);
        JsonNode delivered = row();
        ((ObjectNode) delivered).put("status", "delivered");
        TestOwnedNotificationIntentTeardown.identity(scope, delivered);
        assertThatThrownBy(() -> TestOwnedNotificationIntentTeardown.check(scope, delivered)).isInstanceOf(AssertionError.class);
        assertThat(delivered.path("status").asText()).isEqualTo("delivered");
        TestOwnedNotificationIntentTeardown.validateSealedRows(scope, List.of(delivered));
        ObjectNode leasedDelivered = (ObjectNode) delivered.deepCopy();
        leasedDelivered.put("lease_until", "2026-10-13T00:00:00Z");
        assertThatThrownBy(() -> TestOwnedNotificationIntentTeardown.validateSealedRows(scope, List.of(leasedDelivered)))
                .isInstanceOf(AssertionError.class);
        ObjectNode replayedDelivered = (ObjectNode) delivered.deepCopy();
        replayedDelivered.put("replay_count", 1);
        assertThatThrownBy(() -> TestOwnedNotificationIntentTeardown.validateSealedRows(scope, List.of(replayedDelivered)))
                .isInstanceOf(AssertionError.class);
    }
    @Test
    void missingOrLegacySchemaCannotBeAdmitted() throws Exception {
        for (String schema : List.of("legacy", "1", "2.0")) {
            ObjectNode row = (ObjectNode) row();
            row.put("schema_version", schema);
            assertThatThrownBy(() -> TestOwnedNotificationIntentTeardown.identity(scope, row)).isInstanceOf(AssertionError.class);
        }
        ObjectNode missing = (ObjectNode) row();
        missing.remove("schema_version");
        assertThatThrownBy(() -> TestOwnedNotificationIntentTeardown.identity(scope, missing)).isInstanceOf(AssertionError.class);
    }
    @Test
    void excessOrDuplicateEnumeratedRowsRefusedBeforeDatasourceUse() throws Exception {
        Path trace = directory.resolve("sealed.json");
        Files.writeString(trace, "{}");
        DataSource datasource = mock(DataSource.class);
        JsonNode row = row();
        for (List<JsonNode> rows : List.of(List.of(row, row), List.of(row, row, row))) {
            assertThatThrownBy(() -> TestOwnedNotificationIntentTeardown.run(datasource, scope, rows,
                    trace, directory.resolve("receipt.json"))).isInstanceOf(AssertionError.class);
            verifyNoInteractions(datasource);
        }
    }

}
