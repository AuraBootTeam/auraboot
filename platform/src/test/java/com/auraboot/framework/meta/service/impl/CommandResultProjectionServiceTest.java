package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.meta.service.impl.pipeline.*;
import com.auraboot.framework.permission.engine.model.*;
import com.auraboot.framework.permission.service.FieldPermissionService;
import com.auraboot.framework.permission.service.impl.PermissionSnapshotCache;
import com.auraboot.framework.plugin.extension.*;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommandResultProjectionServiceTest {
    final MetaModelService metadata=mock(MetaModelService.class);
    final FieldPermissionService fields=mock(FieldPermissionService.class);
    final DataPermissionEngine policies=mock(DataPermissionEngine.class);
    final FieldMaskService masks=mock(FieldMaskService.class);
    final PermissionSnapshotCache snapshots=mock(PermissionSnapshotCache.class);
    final BusinessResourceAuthorization owner=mock(BusinessResourceAuthorization.class);
    final CommandResultProjectionService service=new CommandResultProjectionService(metadata,fields,policies,masks,snapshots);
    CommandPipelineContext context;

    @BeforeEach void fixture() {
        MetaContext.setContext(7L,20L,"user","User");MetaContext.setMemberId(30L);
        var command=new CommandDefinition();command.setModelCode("orders");command.setCode("orders:inspect");
        var request=new CommandExecuteRequest();request.setTargetRecordId("root");
        context=CommandPipelineContext.builder().commandCode(command.getCode()).command(command).request(request).tenantId(7L).userId(20L).build();
        context.setAuthorizationVerdict(CommandAuthorizationVerdict.authorized("orders.inspect"));
        context.setTargetRecordReadable(true);
        context.setBusinessTargetAuthorization(new CommandPipelineContext.BusinessTargetAuthorization("orders:inspect","orders","root","update",7L,20L,30L));
        grant(Set.of("cost"));
        when(snapshots.withFreshReads(any())).thenAnswer(call->((Supplier<?>)call.getArgument(0)).get());
        when(owner.businessResultFields()).thenReturn(Set.of("success","amount"));
        when(owner.businessResultProjection()).thenReturn(Map.of("success",BusinessResultField.constant(true),"amount",BusinessResultField.rootField("cost")));
        when(metadata.getModelFields("orders")).thenReturn(List.of(FieldDefinition.builder().code("cost").columnName("cost_column").dataType("decimal").build()));
        when(fields.getFieldPermissions(30L,"orders")).thenReturn(FieldPermissionSet.allAllowed(Set.of("cost")));
    }
    @AfterEach void clear(){MetaContext.clear();}
    void grant(Set<String> read) {
        context.setBusinessGrantDecision(new BusinessGrantDecision(PermissionResult.allow(List.of()),7L,20L,30L,"orders","root",
                List.of(new BusinessGrantDecision.Grant(1L,2L,"orders.inspect",new BusinessGrantDecision.Fields(false,read,Set.of())))));
    }
    void prepare(){context.setBusinessResultProjection(service.prepare(context,owner));}
    Map<String,Object> outcome(){return Map.of("success",true,"amount",17,"recordPid","root");}

    @Test void allowedScalarIsCopiedWithoutChangingTheInternalOutcome() {
        prepare();var internal=new LinkedHashMap<>(outcome());var publicResult=service.project(context,internal);
        assertThat(publicResult).containsEntry("amount",17).containsEntry("recordPid","root");
        assertThat(publicResult).isNotSameAs(internal);assertThat(internal).containsEntry("amount",17);
        assertThatThrownBy(()->publicResult.put("amount",99)).isInstanceOf(UnsupportedOperationException.class);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"create", "update", "delete"})
    void nativeWriterOnlyReturnsTheExactRootAndAtMostOneAffectedRow(String action) {
        context.setBusinessTargetAuthorization(new CommandPipelineContext.BusinessTargetAuthorization("orders:inspect", "orders", "root", action, 7L, 20L, 30L));
        var contract = new com.auraboot.framework.meta.security.NativeBusinessCommandContract("orders", action, List.of("orders.inspect"));
        String count = "orders_" + ("create".equals(action) ? "inserted" : "update".equals(action) ? "updated" : "deleted");
        context.setBusinessResultProjection(service.prepareNative(context, contract));
        assertThat(service.project(context, Map.of("recordPid", "root", count, 1)))
                .containsEntry("recordPid", "root").containsEntry(count, 1);
        assertThatThrownBy(() -> service.project(context, Map.of("recordPid", "other", count, 1)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.project(context, Map.of("recordPid", "root", count, 2)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.project(context, Map.of("recordPid", "root", "amount", 17)))
                .isInstanceOf(AccessDeniedException.class);
    }
    @Test void matchingGrantAndOwnerVisibilityAreBothRequired() {
        grant(Set.of());prepare();assertThat(service.project(context,outcome())).doesNotContainKey("amount");
        grant(Set.of("cost"));when(fields.getFieldPermissions(30L,"orders")).thenReturn(new FieldPermissionSet(Set.of(),Set.of(),Set.of("cost")));
        prepare();assertThat(service.project(context,outcome())).doesNotContainKey("amount");
    }
    @Test void physicalPolicyAliasMasksTheLogicalSourceAndLeavesRawFactsIntact() {
        when(policies.getFieldMaskRules(7L,"orders",20L)).thenReturn(List.of(FieldMaskRule.builder().fieldCode("cost_column").maskType("hide").build()));
        when(policies.applyFieldMasking(anyList(),anyList())).thenAnswer(call->{
            List<Map<String,Object>> rows=call.getArgument(0);var copy=new LinkedHashMap<>(rows.getFirst());copy.put("cost",null);return List.of(copy);
        });
        prepare();var internal=outcome();assertThat(service.project(context,internal)).containsEntry("amount",null);assertThat(internal).containsEntry("amount",17);
    }
    @Test void sharedPhysicalColumnMasksEveryLogicalSource() {
        when(owner.businessResultFields()).thenReturn(Set.of("success","amount","margin"));
        when(owner.businessResultProjection()).thenReturn(Map.of("success",BusinessResultField.constant(true),
                "amount",BusinessResultField.rootField("cost"),"margin",BusinessResultField.rootField("margin")));
        when(metadata.getModelFields("orders")).thenReturn(List.of(
                FieldDefinition.builder().code("cost").columnName("cost_column").dataType("decimal").build(),
                FieldDefinition.builder().code("margin").columnName("cost_column").dataType("decimal").build()));
        grant(Set.of("cost","margin"));
        when(fields.getFieldPermissions(30L,"orders")).thenReturn(FieldPermissionSet.allAllowed(Set.of("cost","margin")));
        var config=new com.auraboot.framework.meta.entity.FieldMaskConfig();
        config.setFieldCode("cost_column");config.setMaskType("FULL");config.setReplacementChar("*");
        when(masks.getEffectiveConfigs("orders",20L,"detail")).thenReturn(List.of(config));
        when(masks.maskValue("17","FULL",null,"*")).thenReturn("**");
        prepare();
        assertThat(service.project(context,Map.of("success",true,"amount",17,"margin",17)))
                .containsEntry("amount","**").containsEntry("margin","**");
    }
    @Test void numericProtocolConstantSurvivesJsonIntegerWidthChanges() {
        when(owner.businessResultFields()).thenReturn(Set.of("version"));
        when(owner.businessResultProjection()).thenReturn(Map.of("version",BusinessResultField.constant(1L)));
        prepare();
        assertThat(service.project(context,Map.of("version",1))).containsEntry("version",1);
        assertThatThrownBy(()->service.project(context,Map.of("version",2))).isInstanceOf(AccessDeniedException.class);
    }
    @Test void detailMaskIsCapturedImmutablyAndAppliedWithoutMutatingTheOutcome() {
        var config=new com.auraboot.framework.meta.entity.FieldMaskConfig();config.setFieldCode("cost_column");config.setMaskType("FULL");config.setReplacementChar("*");
        when(masks.getEffectiveConfigs("orders",20L,"detail")).thenReturn(List.of(config));
        when(masks.maskValue("17","FULL",null,"*")).thenReturn("**");
        prepare();config.setMaskType("unknown");
        assertThat(service.project(context,outcome())).containsEntry("amount","**");
    }
    @Test void actorTargetOrGrantReplacementCannotReuseAProjection() {
        prepare();context.getRequest().setTargetRecordId("other");
        assertThatThrownBy(()->service.project(context,outcome())).isInstanceOf(AccessDeniedException.class);
        context.getRequest().setTargetRecordId("root");grant(Set.of());
        assertThatThrownBy(()->service.project(context,outcome())).isInstanceOf(AccessDeniedException.class);
    }
    @Test void unknownOutputOrFalseAcknowledgementCannotSmuggleData() {
        prepare();assertThatThrownBy(()->service.project(context,Map.of("success",false,"amount",17))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->service.project(context,Map.of("other",17))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->service.project(context,Map.of("recordPid","foreign"))).isInstanceOf(AccessDeniedException.class);
    }
    @Test void unclassifiedOrUnknownModelDataIsRejectedDuringPreparation() {
        when(owner.businessResultProjection()).thenReturn(Map.of());
        assertThatThrownBy(this::prepare).isInstanceOf(AccessDeniedException.class);
        when(owner.businessResultProjection()).thenReturn(Map.of("success",BusinessResultField.constant(true),"amount",BusinessResultField.rootField("missing")));
        assertThatThrownBy(this::prepare).isInstanceOf(AccessDeniedException.class);
    }
    private void derived() {
        when(owner.businessResultFields()).thenReturn(Set.of("success","suggestionId","version"));
        when(owner.businessResultProjection()).thenReturn(Map.of("success",BusinessResultField.constant(true),
                "suggestionId",BusinessResultField.derivedRootField("cost"),"version",BusinessResultField.derivedRootField("cost")));
    }
    private Map<String,Object> derivedOutcome() {return Map.of("success",true,"suggestionId","suggestion-1","version",2);}
    @Test void explicitlyDerivedScalarsShareASourceWithoutBeingConflictingRawAliases() {
        derived();prepare();assertThat(service.project(context,derivedOutcome())).containsAllEntriesOf(derivedOutcome());
    }
    @Test void derivedScalarsCannotDiscloseAHiddenOrUnallowedSource() {
        derived();grant(Set.of());prepare();
        assertThat(service.project(context,derivedOutcome())).containsExactly(Map.entry("success",true));
        grant(Set.of("cost"));when(fields.getFieldPermissions(30L,"orders")).thenReturn(new FieldPermissionSet(Set.of(),Set.of(),Set.of("cost")));
        prepare();assertThat(service.project(context,derivedOutcome())).containsExactly(Map.entry("success",true));
    }
    @Test void derivedScalarsDoNotTransformPartiallyMaskedSourcesIntoUnmaskedTokens() {
        derived();when(policies.getFieldMaskRules(7L,"orders",20L)).thenReturn(List.of(
                FieldMaskRule.builder().fieldCode("cost_column").maskType("partial").build()));
        prepare();assertThat(service.project(context,derivedOutcome())).containsExactly(Map.entry("success",true));
        when(policies.getFieldMaskRules(7L,"orders",20L)).thenReturn(List.of());
        var config=new com.auraboot.framework.meta.entity.FieldMaskConfig();config.setFieldCode("cost_column");config.setMaskType("FULL");
        when(masks.getEffectiveConfigs("orders",20L,"detail")).thenReturn(List.of(config));
        prepare();assertThat(service.project(context,derivedOutcome())).containsExactly(Map.entry("success",true));
    }
    @Test void rawAliasesStillCannotReturnConflictingValues() {
        when(owner.businessResultFields()).thenReturn(Set.of("amount","alias"));
        when(owner.businessResultProjection()).thenReturn(Map.of("amount",BusinessResultField.rootField("cost"),"alias",BusinessResultField.rootField("cost")));
        prepare();assertThatThrownBy(()->service.project(context,Map.of("amount",17,"alias",18))).isInstanceOf(AccessDeniedException.class);
    }
    @Test void derivedOutputsRemainScalarAndMustReferenceRealMetadata() {
        derived();prepare();assertThatThrownBy(()->service.project(context,Map.of("success",true,"version",List.of("secret"))))
                .isInstanceOf(AccessDeniedException.class);
        when(metadata.getModelFields("orders")).thenReturn(List.of());
        assertThatThrownBy(this::prepare).isInstanceOf(AccessDeniedException.class);
    }

}
