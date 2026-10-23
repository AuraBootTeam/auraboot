package com.auraboot.framework.notification.sourcebinding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SourceBindingCanonicalOrderingTest {
    private SourceBindingTypes.Approval approval(Set<String> fields) {
        return new SourceBindingTypes.Approval("METADATA_SHAPE_V1", "source", "0".repeat(64),
                List.of(new SourceBindingTypes.Owner("owner_z", "com.example.z", "z", "1"),
                        new SourceBindingTypes.Owner("owner_a", "com.example.a", "a", "1")), fields);
    }
    @Test void nativeFieldIdentifiersHaveCanonicalOrderRatherThanJvmSetOrder() {
        var fields = new LinkedHashSet<>(List.of("z_field", "a_field", "m_field", "d_field", "x_field", "e_field", "w_field", "f_field",
                "v_field", "g_field", "u_field", "h_field", "t_field", "i_field", "s_field", "j_field"));
        var expected = fields.stream().sorted().toList();
        var actual = SourceBindingCanonical.tree(approval(fields)).path("nativeFieldPids");
        assertThat(actual).isEqualTo(SourceBindingCanonical.tree(expected));
    }
    @Test void canonicalSetIsFrozenWithoutChangingOrderedOwnerOrTargetLists() {
        var fields = new LinkedHashSet<>(List.of("field_z", "field_a"));
        var approval = approval(fields);fields.clear();
        assertThat(approval.nativeFieldPids()).containsExactlyInAnyOrder("field_a", "field_z");
        assertThatThrownBy(() -> approval.nativeFieldPids().add("other")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(SourceBindingCanonical.tree(approval).path("owners").get(0).path("pid").asText()).isEqualTo("owner_z");
        assertThat(SourceBindingCanonical.json(List.of("line_z", "line_a"))).isEqualTo("[\"line_z\",\"line_a\"]");
    }
    @Test void declarationOrderDoesNotChangeApprovalSerialization() {
        var first = approval(new LinkedHashSet<>(List.of("field_z", "field_a", "field_m")));
        var second = approval(new LinkedHashSet<>(List.of("field_m", "field_z", "field_a")));
        assertThat(SourceBindingCanonical.hash(first)).isEqualTo(SourceBindingCanonical.hash(second));
    }
}
