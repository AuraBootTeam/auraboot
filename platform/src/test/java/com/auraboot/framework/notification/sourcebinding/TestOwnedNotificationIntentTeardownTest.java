package com.auraboot.framework.notification.sourcebinding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure identity and terminal-state refusal, without creating a datasource or invoking teardown. */
class TestOwnedNotificationIntentTeardownTest {
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
    }
}
