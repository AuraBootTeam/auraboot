package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.FieldMaskRule;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.entity.FieldMaskConfig;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.FieldMaskService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandAuthorizationVerdict;
import com.auraboot.framework.meta.service.impl.pipeline.CommandIdempotencyIntent;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.permission.engine.model.BusinessGrantDecision;
import com.auraboot.framework.permission.engine.model.FieldPermissionSet;
import com.auraboot.framework.permission.engine.model.PermissionResult;
import com.auraboot.framework.permission.service.FieldPermissionService;
import com.auraboot.framework.permission.service.impl.PermissionSnapshotCache;
import com.auraboot.framework.plugin.extension.BusinessResourceAuthorization;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class CommandPublicOutputProjectionServiceTest {
    private final MetaModelService metadata = mock(MetaModelService.class);
    private final FieldPermissionService fields = mock(FieldPermissionService.class);
    private final DataPermissionEngine policies = mock(DataPermissionEngine.class);
    private final FieldMaskService masks = mock(FieldMaskService.class);
    private final PermissionSnapshotCache snapshots = mock(PermissionSnapshotCache.class);
    private final BusinessResourceAuthorization owner = mock(BusinessResourceAuthorization.class);
    private final CommandPublicOutputProjectionService service = new CommandPublicOutputProjectionService(metadata, fields, policies, masks, snapshots);
    private CommandPipelineContext context;

    @BeforeEach void fixture() {
        MetaContext.setContext(7L, 20L, "actor", "Actor"); MetaContext.setMemberId(30L);
        var command = new CommandDefinition(); command.setCode("orders:export"); command.setModelCode("orders");
        var request = new CommandExecuteRequest(); request.setTargetRecordId("root");
        context = CommandPipelineContext.builder().commandCode(command.getCode()).command(command).request(request).tenantId(7L).userId(20L).payload(Map.of()).build();
        context.setAuthorizationVerdict(CommandAuthorizationVerdict.authorized("orders.export"));
        context.setBusinessTargetAuthorization(new CommandPipelineContext.BusinessTargetAuthorization(command.getCode(), "orders", "root", "update", 7L, 20L, 30L));
        context.setBusinessGrantDecision(new BusinessGrantDecision(PermissionResult.allow(List.of()), 7L, 20L, 30L, "orders", "root",
                List.of(new BusinessGrantDecision.Grant(1L, 2L, "orders.export", new BusinessGrantDecision.Fields(false, Set.of("title"), Set.of())))));
        context.setTargetRecordReadable(true);
        when(snapshots.withFreshReads(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
        when(owner.businessResourceModels()).thenReturn(Set.of("orders", "order_lines"));
        when(owner.publicBusinessResourceFields()).thenReturn(Map.of("order_lines", Set.of("name", "brand")));
        when(metadata.getModelFields("order_lines")).thenReturn(List.of(
                field("name", "name_col"), field("brand", "brand_col"), field("internal_cost", "cost_col")));
        when(fields.getFieldPermissions(30L, "order_lines")).thenReturn(FieldPermissionSet.allAllowed(Set.of("name", "brand", "internal_cost")));
    }
    @AfterEach void clear() { MetaContext.clear(); }
    private FieldDefinition field(String code, String column) { return FieldDefinition.builder().code(code).columnName(column).dataType("string").build(); }
    private void prepare() { context.setPublicOutputProjection(service.prepare(context, owner)); }
    private Map<String, Object> internal() { return new HashMap<>(Map.of("name", "Resistor", "brand", "Private brand", "internal_cost", 42)); }

    @Test void declared_child_fields_use_public_visibility_without_exposing_internal_dependencies() {
        prepare(); var internal = internal();
        var output = service.project(context, "order_lines", internal);
        assertThat(output).containsOnlyKeys("name", "brand").containsEntry("brand", "Private brand");
        assertThat(internal).containsEntry("internal_cost", 42);
        assertThatThrownBy(() -> output.put("name", "changed")).isInstanceOf(UnsupportedOperationException.class);
        verify(fields).getFieldPermissions(30L, "order_lines");
    }

    @Test void hidden_child_fields_are_removed_without_rewriting_internal_facts() {
        when(fields.getFieldPermissions(30L, "order_lines")).thenReturn(new FieldPermissionSet(Set.of("name"), Set.of(), Set.of("brand")));
        prepare(); var raw = internal();
        assertThat(service.project(context, "order_lines", raw)).containsOnlyKeys("name");
        assertThat(raw).containsEntry("brand", "Private brand");
    }

    @Test void current_root_grant_read_fields_also_restrict_root_artifact_sources() {
        when(owner.publicBusinessResourceFields()).thenReturn(Map.of("orders", Set.of("title", "cost")));
        when(metadata.getModelFields("orders")).thenReturn(List.of(field("title", "title_col"), field("cost", "cost_col")));
        when(fields.getFieldPermissions(30L, "orders")).thenReturn(FieldPermissionSet.allAllowed(Set.of("title", "cost")));
        prepare();
        assertThat(service.project(context, "orders", Map.of("title", "Board", "cost", 42))).containsOnlyKeys("title");
    }

    @Test void physical_alias_policy_applies_to_all_logical_output_aliases() {
        when(metadata.getModelFields("order_lines")).thenReturn(List.of(field("name", "shared_col"), field("brand", "shared_col")));
        when(policies.getFieldMaskRules(7L, "order_lines", 20L)).thenReturn(List.of(FieldMaskRule.builder().fieldCode("shared_col").maskType("hide").build()));
        when(policies.applyFieldMasking(anyList(), anyList())).thenAnswer(call -> {
            List<Map<String, Object>> rows = call.getArgument(0); List<FieldMaskRule> rules = call.getArgument(1);
            assertThat(rules).extracting(FieldMaskRule::getFieldCode).containsExactlyInAnyOrder("name", "brand");
            var row = new HashMap<>(rows.getFirst()); row.put("name", null); row.put("brand", null); row.put("internal_cost", 42);
            return List.of(row);
        });
        prepare(); var raw = internal();
        assertThat(service.project(context, "order_lines", raw)).containsEntry("name", null).containsEntry("brand", null).doesNotContainKey("internal_cost");
        assertThat(raw).containsEntry("brand", "Private brand");
    }

    @Test void detail_mask_is_immutable_and_changes_the_output_and_policy_fingerprint() {
        prepare(); String unmasked = context.getPublicOutputProjection().fingerprint();
        var config = new FieldMaskConfig(); config.setFieldCode("brand_col"); config.setMaskType("FULL"); config.setReplacementChar("*");
        when(masks.getEffectiveConfigs("order_lines", 20L, "detail")).thenReturn(List.of(config));
        when(masks.maskValue("Private brand", "FULL", null, "*")).thenReturn("***");
        prepare(); String masked = context.getPublicOutputProjection().fingerprint(); config.setMaskType("CUSTOM");
        assertThat(masked).isNotEqualTo(unmasked).matches("[0-9a-f]{64}");
        assertThat(service.project(context, "order_lines", internal())).containsEntry("brand", "***");
        assertThat(context.getPublicOutputProjection().fingerprint()).isEqualTo(masked);
    }

    @Test void effective_policy_change_changes_idempotency_intent_while_ordinary_intent_is_unchanged() {
        var ordinary = CommandIdempotencyIntent.snapshot(context);
        assertThat(ordinary).doesNotContainKey("publicOutputPolicy");
        prepare(); var before = CommandIdempotencyIntent.snapshot(context);
        when(fields.getFieldPermissions(30L, "order_lines")).thenReturn(new FieldPermissionSet(Set.of("name"), Set.of(), Set.of("brand")));
        prepare(); var after = CommandIdempotencyIntent.snapshot(context);
        assertThat(after).isNotEqualTo(before);
        assertThat(after.get("publicOutputPolicy")).isNotEqualTo(before.get("publicOutputPolicy"));
        assertThat(ordinary).doesNotContainKey("publicOutputPolicy");
    }

    @Test void export_only_masks_apply_and_a_shared_detail_export_mask_runs_once() {
        var config = new FieldMaskConfig(); config.setFieldCode("brand"); config.setMaskType("FULL"); config.setReplacementChar("*");
        when(masks.getEffectiveConfigs("order_lines", 20L, "export")).thenReturn(List.of(config));
        when(masks.maskValue("Private brand", "FULL", null, "*")).thenReturn("***");
        prepare(); String exportOnly = context.getPublicOutputProjection().fingerprint();
        assertThat(service.project(context, "order_lines", internal())).containsEntry("brand", "***");
        when(masks.getEffectiveConfigs("order_lines", 20L, "detail")).thenReturn(List.of(config));
        prepare();
        assertThat(context.getPublicOutputProjection().fingerprint()).isEqualTo(exportOnly);
        assertThat(service.project(context, "order_lines", internal())).containsEntry("brand", "***");
        verify(masks, times(2)).maskValue("Private brand", "FULL", null, "*");
    }

    @Test void equivalent_declarations_have_stable_fingerprints_independent_of_set_order() {
        prepare(); String first = context.getPublicOutputProjection().fingerprint();
        when(owner.publicBusinessResourceFields()).thenReturn(Map.of("order_lines", new java.util.LinkedHashSet<>(List.of("brand", "name"))));
        prepare(); assertThat(context.getPublicOutputProjection().fingerprint()).isEqualTo(first);
    }

    @Test void rejects_undeclared_models_and_complex_or_missing_source_definitions() {
        when(owner.publicBusinessResourceFields()).thenReturn(Map.of("unrelated", Set.of("name")));
        assertThatThrownBy(this::prepare).isInstanceOf(AccessDeniedException.class);
        when(owner.publicBusinessResourceFields()).thenReturn(Map.of("order_lines", Set.of("name", "unknown")));
        assertThatThrownBy(this::prepare).isInstanceOf(AccessDeniedException.class);
        when(owner.publicBusinessResourceFields()).thenReturn(Map.of("order_lines", Set.of("name")));
        when(metadata.getModelFields("order_lines")).thenReturn(List.of(FieldDefinition.builder().code("name").dataType("jsonb").build()));
        assertThatThrownBy(this::prepare).isInstanceOf(AccessDeniedException.class);
    }

    @Test void rejects_invalid_or_missing_policy_instead_of_returning_raw_values() {
        when(policies.getFieldMaskRules(7L, "order_lines", 20L)).thenReturn(List.of(FieldMaskRule.builder().fieldCode("brand_col").maskType("unknown").build()));
        assertThatThrownBy(this::prepare).isInstanceOf(AccessDeniedException.class);
        when(policies.getFieldMaskRules(7L, "order_lines", 20L)).thenReturn(null);
        assertThatThrownBy(this::prepare).isInstanceOf(AccessDeniedException.class);
        when(policies.getFieldMaskRules(7L, "order_lines", 20L)).thenReturn(List.of());
        when(fields.getFieldPermissions(30L, "order_lines")).thenReturn(null);
        assertThatThrownBy(this::prepare).isInstanceOf(AccessDeniedException.class);
    }

    @Test void projection_rejects_foreign_actor_changed_grant_unknown_model_and_complex_values() {
        prepare();
        assertThatThrownBy(() -> service.project(context, "unrelated", internal())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.project(context, "order_lines", Map.of("name", Map.of("secret", 42)))).isInstanceOf(AccessDeniedException.class);
        MetaContext.setMemberId(31L);
        assertThatThrownBy(() -> service.project(context, "order_lines", internal())).isInstanceOf(AccessDeniedException.class);
        MetaContext.setMemberId(30L); context.setTargetRecordReadable(false);
        assertThatThrownBy(() -> service.project(context, "order_lines", internal())).isInstanceOf(AccessDeniedException.class);
        context.setTargetRecordReadable(true); context.setBusinessGrantDecision(null);
        assertThatThrownBy(() -> service.project(context, "order_lines", internal())).isInstanceOf(AccessDeniedException.class);
    }
    @Test void a_document_json_source_is_an_atomic_field_dependency_not_an_unrestricted_public_object() {
        when(metadata.getModelFields("order_lines")).thenReturn(List.of(
                FieldDefinition.builder().code("name").columnName("name_col").dataType("jsonb").build()));
        when(owner.publicBusinessResourceFields()).thenReturn(Map.of("order_lines",Set.of("name")));
        assertThatThrownBy(()->service.prepare(context,owner)).isInstanceOf(AccessDeniedException.class);
        var document=service.prepareDocument(context,Map.of("order_lines",Set.of("name")));
        assertThat(document.models().get("order_lines").readable()).containsExactly("name");
        when(fields.getFieldPermissions(30L,"order_lines")).thenReturn(new FieldPermissionSet(Set.of(),Set.of(),Set.of("name")));
        var hidden=service.prepareDocument(context,Map.of("order_lines",Set.of("name")));
        assertThat(hidden.models().get("order_lines").readable()).isEmpty();
        assertThat(document.models().get("order_lines").declared()).isEqualTo(hidden.models().get("order_lines").declared());
        context.setPublicOutputProjection(document);
        assertThatThrownBy(()->service.project(context,"order_lines",Map.of("name",Map.of("private","value"))))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test void document_projection_removes_hidden_fields_and_freezes_structured_source_without_mutating_facts() {
        when(metadata.getModelFields("order_lines")).thenReturn(List.of(
                FieldDefinition.builder().code("name").columnName("name_col").dataType("jsonb").build(),field("brand","brand_col")));
        when(fields.getFieldPermissions(30L,"order_lines")).thenReturn(new FieldPermissionSet(Set.of("name"),Set.of(),Set.of("brand")));
        var plan=service.prepareDocument(context,Map.of("order_lines",Set.of("name","brand")));
        var nested=new HashMap<String,Object>(Map.of("source","Original"));
        var source=new HashMap<String,Object>(Map.of("pid","line-1","name",nested,"brand","Private","internal_cost",42));
        var output=service.projectDocument(plan,"order_lines",source);
        assertThat(output).containsOnlyKeys("pid","name").containsEntry("pid","line-1");
        nested.put("source","Changed");
        assertThat((Map<?,?>)output.get("name")).isEqualTo(Map.of("source","Original"));
        assertThat(source).containsEntry("brand","Private").containsEntry("internal_cost",42);
        assertThatThrownBy(()->output.put("brand","forged")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->service.projectDocument(plan,"foreign",source)).isInstanceOf(AccessDeniedException.class);
    }

}
