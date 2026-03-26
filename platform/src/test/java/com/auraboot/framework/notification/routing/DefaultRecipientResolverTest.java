package com.auraboot.framework.notification.routing;

import com.auraboot.module.meta.event.CommandCompletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for DefaultRecipientResolver.
 * <p>
 * OPERATOR strategy depends on MetaContext (ThreadLocal) — tested indirectly via
 * the no-context path (returns empty). RECORD_OWNER and unknown strategy are fully tested.
 */
class DefaultRecipientResolverTest {

    private DefaultRecipientResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new DefaultRecipientResolver();
    }

    // =========================================================
    // RECORD_OWNER strategy
    // =========================================================

    @Test
    void resolve_recordOwner_numericCreatedBy_returnsList() {
        CommandCompletedEvent event = buildEvent(Map.of("created_by", 42L));

        List<Long> result = resolver.resolve(event, "record_owner", null);

        assertThat(result).containsExactly(42L);
    }

    @Test
    void resolve_recordOwner_intCreatedBy_returnsList() {
        CommandCompletedEvent event = buildEvent(Map.of("created_by", 99));

        List<Long> result = resolver.resolve(event, "record_owner", null);

        assertThat(result).containsExactly(99L);
    }

    @Test
    void resolve_recordOwner_stringCreatedBy_parsesAndReturnsList() {
        CommandCompletedEvent event = buildEvent(Map.of("created_by", "123"));

        List<Long> result = resolver.resolve(event, "record_owner", null);

        assertThat(result).containsExactly(123L);
    }

    @Test
    void resolve_recordOwner_invalidStringCreatedBy_returnsEmpty() {
        CommandCompletedEvent event = buildEvent(Map.of("created_by", "not-a-number"));

        List<Long> result = resolver.resolve(event, "record_owner", null);

        assertThat(result).isEmpty();
    }

    @Test
    void resolve_recordOwner_missingCreatedBy_returnsEmpty() {
        CommandCompletedEvent event = buildEvent(Map.of("status", "done"));

        List<Long> result = resolver.resolve(event, "record_owner", null);

        assertThat(result).isEmpty();
    }

    // =========================================================
    // OPERATOR strategy — no MetaContext in test environment
    // =========================================================

    @Test
    void resolve_operator_noMetaContext_returnsEmpty() {
        // MetaContext is not set in unit test environment → graceful empty return
        CommandCompletedEvent event = buildEvent(Map.of("recordId", "r-001"));

        List<Long> result = resolver.resolve(event, "operator", null);

        assertThat(result).isEmpty();
    }

    @Test
    void resolve_nullStrategy_defaultsToOperator_returnsEmpty() {
        CommandCompletedEvent event = buildEvent(Map.of());

        List<Long> result = resolver.resolve(event, null, null);

        // Default is OPERATOR, and no MetaContext is present
        assertThat(result).isEmpty();
    }

    // =========================================================
    // Unknown strategy
    // =========================================================

    @Test
    void resolve_unknownStrategy_returnsEmpty() {
        CommandCompletedEvent event = buildEvent(Map.of("created_by", 10L));

        List<Long> result = resolver.resolve(event, "manager_chain", null);

        assertThat(result).isEmpty();
    }

    @Test
    void resolve_anotherUnknownStrategy_returnsEmpty() {
        CommandCompletedEvent event = buildEvent(Map.of());

        List<Long> result = resolver.resolve(event, "broadcast_all", null);

        assertThat(result).isEmpty();
    }

    // =========================================================
    // Helper
    // =========================================================

    private CommandCompletedEvent buildEvent(Map<String, Object> payload) {
        Map<String, Object> mutablePayload = new HashMap<>(payload);
        return new CommandCompletedEvent(100L, "rec-001", "crm_lead", mutablePayload, "test_cmd", "create");
    }
}
