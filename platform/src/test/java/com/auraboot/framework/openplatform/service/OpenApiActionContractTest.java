package com.auraboot.framework.openplatform.service;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class OpenApiActionContractTest {
    private OpenApiActionContract command(int version, int timeout, String fingerprint) {
        return new OpenApiActionContract("office.devices.assign", "command", "tasset:assign_asset", "office.devices", version,
                fingerprint, "tasset.asset.manage", Map.of("execute", "office.devices.assign.execute"),
                Map.of("assignedTo", new OpenApiQueryContract.Parameter(OpenApiResourceContract.Type.STRING, true, false, null)),
                Map.of("assignedTo", "assignedTo"), Set.of("assignedTo", "status"), Map.of(), timeout, 2);
    }
    @Test void typedCommandInputRejectsUnknownMissingNullAndWrongTypes() {
        var contract = command(1, 5000, "a".repeat(64));
        assertThat(contract.validateInput(Map.of("assignedTo", "Alice"))).containsExactlyEntriesOf(Map.of("assignedTo", "Alice"));
        for (Map<String, Object> input : List.of(Map.<String, Object>of(), Map.<String, Object>of("tenantId", 1),
                Map.<String, Object>of("assignedTo", 7), Collections.<String, Object>singletonMap("assignedTo", null)))
            assertThatThrownBy(() -> contract.validateInput(input)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void immutableSourceAndBudgetsRemainCompatibleOnlyWithTheSameBusinessSemantics() {
        var original = command(1, 5000, "a".repeat(64));
        assertThat(original.storageCode()).isEqualTo("action:office.devices.assign");
        command(2, 6000, "a".repeat(64)).requireCompatibleWith(original);
        assertThatThrownBy(() -> command(2, 4000, "a".repeat(64)).requireCompatibleWith(original)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> command(2, 6000, "b".repeat(64)).requireCompatibleWith(original)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void explicitEffectGrantDoesNotImplyGenericFieldWriteAuthority() {
        var contract = command(1, 5000, "a".repeat(64));
        var complete = new OpenApiActionPolicy(true, Set.of("assignedTo", "status")); complete.validate(contract);
        assertThat(complete.permits(contract)).isTrue();
        assertThat(new OpenApiActionPolicy(true, Set.of("assignedTo")).permits(contract)).isFalse();
        assertThat(new OpenApiActionPolicy(false, Set.of("assignedTo", "status")).permits(contract)).isFalse();
        assertThatThrownBy(() -> new OpenApiActionPolicy(true, Set.of("privateField")).validate(contract)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void contractRequiresPinnedSourcePositiveVersionAndBoundedExecution() {
        assertThatThrownBy(() -> command(0, 5000, "a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> command(1, 0, "a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> command(1, 30001, "a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> command(1, 5000, "unapproved")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void readOnlyHandlerContractHasTypedOutputsAndNoWritePermissionOrEffects() {
        var contract = new OpenApiActionContract("office.devices.summary", "handler", "core.visible-page", "office.devices", 1,
                "a".repeat(64), null, Map.of("execute", "office.devices.summary.execute"),
                Map.of("limit", new OpenApiQueryContract.Parameter(OpenApiResourceContract.Type.INTEGER, true, false, null)),
                Map.of(), Set.of(), Map.of("countOnPage", OpenApiResourceContract.Type.INTEGER, "hasMore", OpenApiResourceContract.Type.BOOLEAN), 5000, 2);
        assertThat(contract.effectFields()).isEmpty();
        assertThat(contract.validateInput(Map.of("limit", 10))).containsKey("limit");
        assertThatThrownBy(() -> new OpenApiActionContract(contract.code(), contract.sourceKind(), contract.sourceCode(), contract.resourceCode(),
                1, contract.sourceFingerprint(), "not.read.only", contract.scopes(), contract.parameters(), Map.of(), Set.of(), contract.outputFields(), 5000, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
