package com.auraboot.framework.file.security;

import com.auraboot.framework.meta.service.impl.BusinessResultProjectionPlan;
import com.auraboot.framework.meta.service.impl.CommandPublicOutputProjectionPlan;
import com.auraboot.framework.meta.service.impl.CommandPublicOutputProjectionPlan.ModelFields;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext.BusinessTargetAuthorization;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class BusinessArtifactAuthorizationTest {
    @Test void root_relationships_are_typed_immutable_hashes_without_raw_business_values() {
        var facts = new java.util.HashMap<String,Object>(); facts.put("project", "PRIVATE-PROJECT"); facts.put("run",null);
        var binding = BusinessArtifactAuthorization.binding(Set.of("project","run"),facts);
        var stamp = BusinessArtifactAuthorization.capture(plan(fields(Set.of("name"),List.of())),"export_file",binding);
        assertThat(stamp.encode()).doesNotContain("PRIVATE-PROJECT");
        assertThat(BusinessArtifactAuthorization.parse(stamp.encode()).rootBinding()).isEqualTo(binding);
        facts.put("project","OTHER-PROJECT");
        assertThat(BusinessArtifactAuthorization.binding(Set.of("project","run"),facts)).isNotEqualTo(binding);
        assertThatThrownBy(() -> binding.put("project","forged")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(BusinessArtifactAuthorization.binding(Set.of("project"),Map.of("project",1)))
                .isNotEqualTo(BusinessArtifactAuthorization.binding(Set.of("project"),Map.of("project","1")));
    }
    static final String PID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
    static final BusinessTargetAuthorization TARGET = new BusinessTargetAuthorization("orders:export", "orders", PID, "update", 7L, 20L, 30L);
    static ModelFields fields(Set<String> readable, List<BusinessResultProjectionPlan.PolicyMask> masks) {
        return new ModelFields(Set.of("name", "brand"), readable, masks, List.of());
    }
    static CommandPublicOutputProjectionPlan plan(ModelFields lines) {
        return new CommandPublicOutputProjectionPlan(TARGET, null, Map.of("orders", new ModelFields(Set.of("export_file"), Set.of("export_file"), List.of(), List.of()), "lines", lines));
    }

    @Test void immutable_source_view_round_trips_without_storing_raw_values() {
        var plan = plan(fields(Set.of("name", "brand"), List.of()));
        var stamp = BusinessArtifactAuthorization.capture(plan, "export_file");
        assertThat(BusinessArtifactAuthorization.parse(stamp.encode())).isEqualTo(stamp);
        assertThat(stamp.permits(plan)).isTrue();
        assertThat(stamp.encode()).doesNotContain("Private brand", "actorUserId");
    }
    @Test void a_new_hidden_field_or_new_mask_blocks_the_old_unmasked_artifact() {
        var stamp = BusinessArtifactAuthorization.capture(plan(fields(Set.of("name", "brand"), List.of())), "export_file");
        assertThat(stamp.permits(plan(fields(Set.of("name"), List.of())))).isFalse();
        assertThat(stamp.permits(plan(fields(Set.of("name", "brand"), List.of(new BusinessResultProjectionPlan.PolicyMask("brand", "hide", null)))))).isFalse();
    }
    @Test void a_reader_with_broader_visibility_may_read_an_older_restricted_artifact() {
        var before = fields(Set.of("name"), List.of(new BusinessResultProjectionPlan.PolicyMask("name", "partial", "prefix")));
        var stamp = BusinessArtifactAuthorization.capture(plan(before), "export_file");
        assertThat(stamp.permits(plan(fields(Set.of("name", "brand"), List.of())))).isTrue();
        assertThat(stamp.permits(plan(before))).isTrue();
        assertThat(stamp.permits(plan(fields(Set.of("name"), List.of(new BusinessResultProjectionPlan.PolicyMask("name", "partial", "different")))))).isFalse();
    }
    @Test void a_changed_source_contract_cannot_be_assumed_compatible() {
        var stamp = BusinessArtifactAuthorization.capture(plan(fields(Set.of("name"), List.of())), "export_file");
        var changed = new CommandPublicOutputProjectionPlan(TARGET, null, Map.of("orders", new ModelFields(Set.of("export_file"), Set.of("export_file"), List.of(), List.of())));
        assertThat(stamp.permits(changed)).isFalse();
    }
    @Test void oversized_provenance_is_rejected_before_it_can_be_persisted() {
        var oversized = fields(Set.of("name"), List.of(
                new BusinessResultProjectionPlan.PolicyMask("name", "partial", "x".repeat(262_144))));
        var stamp = BusinessArtifactAuthorization.capture(plan(oversized), "export_file");
        assertThatThrownBy(stamp::encode).isInstanceOf(AccessDeniedException.class);
    }
    @Test void malformed_unknown_duplicate_and_inconsistent_provenance_fail_closed() {
        var encoded = BusinessArtifactAuthorization.capture(plan(fields(Set.of("name"), List.of())), "export_file").encode();
        for (String value : List.of("", "{}", encoded + " {}", encoded.replace("\"version\":1", "\"version\":2"),
                encoded.replace("\"version\":1", "\"version\":1,\"version\":1"), encoded.replace("\"version\":1", "\"version\":1,\"unknown\":true"),
                encoded.replace("\"readable\":[\"name\"]", "\"readable\":[\"brand\"]"))) {
            assertThatThrownBy(() -> BusinessArtifactAuthorization.parse(value)).isInstanceOf(AccessDeniedException.class);
        }
    }
}
