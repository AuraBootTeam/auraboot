package com.auraboot.framework.file.security;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.file.entity.FileEntity;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.CommandPublicOutputProjectionService;
import com.auraboot.framework.permission.engine.model.BusinessGrantDecision;
import com.auraboot.framework.permission.engine.model.PermissionResult;
import com.auraboot.framework.permission.service.BusinessCommandRecordAuthorization;
import com.auraboot.framework.plugin.extension.BusinessResourceAuthorization;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.auraboot.framework.file.security.BusinessArtifactAuthorizationTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BusinessArtifactAccessGuardTest {
    final ExtensionRegistry extensions = mock(ExtensionRegistry.class);
    final CommandDefinitionMapper commands = mock(CommandDefinitionMapper.class);
    final DynamicDataService data = mock(DynamicDataService.class);
    final MetaModelService metadata = mock(MetaModelService.class);
    final BusinessCommandRecordAuthorization authorization = mock(BusinessCommandRecordAuthorization.class);
    final CommandPublicOutputProjectionService projection = mock(CommandPublicOutputProjectionService.class);
    final CommandHandlerExtension handler = mock(CommandHandlerExtension.class, withSettings().extraInterfaces(BusinessResourceAuthorization.class));
    final BusinessResourceAuthorization owner = (BusinessResourceAuthorization) handler;
    final BusinessArtifactAccessGuard guard = new BusinessArtifactAccessGuard(extensions, commands, data, metadata, authorization, projection, new ObjectMapper());
    final Map<String,Object> root = Map.of("pid", PID, "tenant_id", 7L);
    FileEntity file;
    CommandDefinition definition;
    BusinessGrantDecision allowed, denied;
    @BeforeEach void fixture() throws Exception {
        MetaContext.setContext(7L, 20L, "actor", "Actor"); MetaContext.setMemberId(30L);
        var plan = plan(fields(Set.of("name", "brand"), List.of()));
        file = new FileEntity(); file.setPid("01ARZ3NDEKTSV4RRFFQ69G5FAW"); file.setTenantId(7L); file.setCreatedBy(20L);
        file.setFileSize(100L); file.setStatus("success");
        file.setArtifactAuthorization(BusinessArtifactAuthorization.capture(plan, "export_file").encode());
        when(extensions.getBusinessResourceOwner("orders:export")).thenReturn(Optional.of(handler));
        when(owner.businessArtifactFields()).thenReturn(Map.of("export_file", 1_000));
        when(owner.publicBusinessResourceFields()).thenReturn(Map.of("orders", Set.of("export_file"), "lines", Set.of("name", "brand")));
        when(owner.businessBoundary()).thenReturn(new BusinessResourceAuthorization.Boundary("orders:export", "orders", "update", Set.of("orders.export")));
        definition = new CommandDefinition(); definition.setTenantId(7L); definition.setCode("orders:export"); definition.setModelCode("orders");
        definition.setIsCurrent(true); definition.setDeletedFlag(false); definition.setStatus("published");
        definition.setExecutionConfig(new ObjectMapper().writeValueAsString(Map.of("type", "custom", "permissions", List.of("orders.export"),
                "handlerParams", Map.of("targetRecordAuthorization", "business", "targetRecordAction", "update", "dslPersistence", false))));
        when(commands.selectOne(any())).thenReturn(definition);
        when(data.getByIdForAuthorization("orders", PID)).thenReturn(root);
        allowed = new BusinessGrantDecision(PermissionResult.allow(List.of()), 7L,20L,30L,"orders",PID,
                List.of(new BusinessGrantDecision.Grant(1L, 2L, "orders.export", BusinessGrantDecision.Fields.legacy())));
        denied = new BusinessGrantDecision(PermissionResult.deny("denied", List.of()),7L,20L,30L,"orders",PID,List.of());
        when(authorization.resolve(eq(30L), anyList(), eq("orders"), eq(root))).thenReturn(allowed);
        when(authorization.resolveModelRead(30L, "orders", root)).thenReturn(denied);
        when(metadata.getModelFields("orders")).thenReturn(List.of(FieldDefinition.builder().code("export_file").dataType("file").build()));
        when(metadata.getModelDefinition("orders")).thenReturn(Optional.of(com.auraboot.framework.meta.dto.ModelDefinition.builder().code("orders").status("published").build()));
        when(projection.prepare(any(), same(owner))).thenReturn(plan);
    }
    @AfterEach void clear() { MetaContext.clear(); }
    @Test void creator_is_checked_against_current_authorization() {
        guard.authorize(file);
        when(authorization.resolve(eq(30L), anyList(), eq("orders"), eq(root))).thenReturn(denied);
        assertThatThrownBy(() -> guard.authorize(file)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void a_valid_model_reader_does_not_need_the_generation_permission_or_creator_identity() {
        file.setCreatedBy(999L);
        when(authorization.resolve(eq(30L), anyList(), eq("orders"), eq(root))).thenReturn(denied);
        when(authorization.resolveModelRead(30L, "orders", root)).thenReturn(allowed);
        guard.authorize(file);
        verify(projection).prepare(any(), same(owner));
    }
    @Test void hidden_source_fields_and_hidden_file_references_block_old_bytes() {
        when(projection.prepare(any(), same(owner))).thenReturn(plan(fields(Set.of("name"), List.of())));
        assertThatThrownBy(() -> guard.authorize(file)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void source_definition_withdrawal_or_wrong_root_cannot_authorize_old_files() {
        definition.setStatus("draft");
        assertThatThrownBy(() -> guard.authorize(file)).isInstanceOf(AccessDeniedException.class);
        definition.setStatus("published");
        when(data.getByIdForAuthorization("orders", PID)).thenReturn(Map.of("pid", PID, "tenant_id", 8L));
        assertThatThrownBy(() -> guard.authorize(file)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void foreign_tenant_and_undeclared_artifacts_deny_before_any_root_read() {
        file.setTenantId(8L);
        assertThatThrownBy(() -> guard.authorize(file)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(data);
        file.setTenantId(7L); when(owner.businessArtifactFields()).thenReturn(Map.of());
        assertThatThrownBy(() -> guard.authorize(file)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(data);
    }
    @Test void ordinary_uploads_do_not_enter_the_artifact_policy() {
        file.setArtifactAuthorization(null); guard.authorize(file);
        verifyNoInteractions(data, commands, authorization, projection);
    }
    @Test void hidden_file_reference_and_its_mask_block_bytes_even_when_other_sources_are_visible() {
        var lines = fields(Set.of("name","brand"),List.of());
        for (var rootFields : List.of(
                new com.auraboot.framework.meta.service.impl.CommandPublicOutputProjectionPlan.ModelFields(Set.of("export_file"),Set.of(),List.of(),List.of()),
                new com.auraboot.framework.meta.service.impl.CommandPublicOutputProjectionPlan.ModelFields(Set.of("export_file"),Set.of("export_file"),
                        List.of(new com.auraboot.framework.meta.service.impl.BusinessResultProjectionPlan.PolicyMask("export_file","hide",null)),List.of()))) {
            when(projection.prepare(any(),same(owner))).thenReturn(new com.auraboot.framework.meta.service.impl.CommandPublicOutputProjectionPlan(TARGET,null,Map.of("orders",rootFields,"lines",lines)));
            assertThatThrownBy(() -> guard.authorize(file)).isInstanceOf(AccessDeniedException.class);
        }
    }
    @Test void unpublished_root_or_invalid_execution_configuration_is_not_a_read_authority() {
        when(metadata.getModelDefinition("orders")).thenReturn(Optional.of(com.auraboot.framework.meta.dto.ModelDefinition.builder().code("orders").status("draft").build()));
        assertThatThrownBy(() -> guard.authorize(file)).isInstanceOf(AccessDeniedException.class);
        definition.setExecutionConfig("null");
        assertThatThrownBy(() -> guard.authorize(file)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(data);
    }
    @Test void missing_principal_is_rejected_before_loading_any_authorization_record() {
        MetaContext.clear();
        assertThatThrownBy(() -> guard.authorize(file)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(data,commands,authorization,projection);
    }
}
