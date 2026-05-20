package com.auraboot.framework.meta.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Command audit payload helpers")
class CommandAuditPayloadsTest {

    @Test
    @DisplayName("adds auditContext under a reserved key without mutating business payload")
    void withAuditContext_addsReservedContextWithoutMutatingPayload() {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("name", "Order export");

        Map<String, Object> auditPayload = CommandAuditPayloads.withAuditContext(
                payload,
                Map.of(
                        "source", "unified-designer-runtime-preview",
                        "pageId", "orders",
                        "blockId", "export_button"));

        assertThat(auditPayload)
                .containsEntry("name", "Order export")
                .containsKey("__auditContext");
        assertThat((Map<String, Object>) auditPayload.get("__auditContext"))
                .containsEntry("source", "unified-designer-runtime-preview")
                .containsEntry("pageId", "orders")
                .containsEntry("blockId", "export_button");
        assertThat(payload).doesNotContainKey("__auditContext");
    }

    @Test
    @DisplayName("strips spoofed reserved audit context from business payload")
    void withAuditContext_stripsSpoofedReservedContextFromPayload() {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("name", "Order export");
        payload.put("__auditContext", Map.of("source", "spoofed-client"));

        Map<String, Object> auditPayload = CommandAuditPayloads.withAuditContext(payload, null);

        assertThat(auditPayload)
                .containsEntry("name", "Order export")
                .doesNotContainKey("__auditContext");
        assertThat(payload).containsKey("__auditContext");
    }

    @Test
    @DisplayName("trusted audit context overrides spoofed payload reserved key")
    void withAuditContext_trustedContextOverridesSpoofedReservedKey() {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("name", "Order export");
        payload.put("__auditContext", Map.of("source", "spoofed-client"));

        Map<String, Object> auditPayload = CommandAuditPayloads.withAuditContext(
                payload,
                Map.of("source", "unified-designer-runtime-preview", "blockId", "export_button"));

        assertThat(auditPayload).containsEntry("name", "Order export");
        assertThat((Map<String, Object>) auditPayload.get("__auditContext"))
                .containsEntry("source", "unified-designer-runtime-preview")
                .containsEntry("blockId", "export_button")
                .doesNotContainEntry("source", "spoofed-client");
        assertThat(payload.get("__auditContext")).isEqualTo(Map.of("source", "spoofed-client"));
    }
}
