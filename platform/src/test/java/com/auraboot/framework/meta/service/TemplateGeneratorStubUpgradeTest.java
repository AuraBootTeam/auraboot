package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.ConflictException;
import com.auraboot.framework.application.exception.ResourceNotFoundException;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.*;
import com.auraboot.framework.meta.mapper.*;
import com.auraboot.framework.meta.template.dto.CrudTemplateConfig;
import com.auraboot.framework.meta.template.generator.DslGenerator;
import com.auraboot.framework.meta.template.service.impl.TemplateGeneratorServiceImpl;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Service / hermetic / blocking-commit / unit: exact placeholder upgrade boundaries. */
@ExtendWith(MockitoExtension.class)
class TemplateGeneratorStubUpgradeTest {
    @Mock MetaModelService models;
    @Mock MetaModelMapper modelMapper;
    @Mock MetaFieldMapper fields;
    @Mock PageSchemaService pages;
    @Mock PageSchemaMapper pageMapper;
    @Mock DslGenerator dsl;
    @Mock PermissionService permissions;
    @Mock UserPermissionService userPermissions;
    @Spy ObjectMapper json = new ObjectMapper();
    @InjectMocks TemplateGeneratorServiceImpl generator;

    @BeforeEach void setup() {
        MetaContext.setContext(7L, 99L, "actor", "tester");
        when(models.findByCode("customer")).thenReturn(MetaModelDTO.builder()
                .id(1L).code("customer").tenantId(7L).displayName("Customer").build());
    }
    @AfterEach void clear() { MetaContext.clear(); }

    private CrudTemplateConfig config(String kind) {
        var config = new CrudTemplateConfig();
        config.setGenerateList("list".equals(kind));
        config.setGenerateForm("form".equals(kind));
        config.setGenerateDetail("detail".equals(kind));
        config.setCreateMenu(false); config.setCreatePermissions(false); config.setAssignRoles(false);
        return config;
    }
    private PageSchemaDTO stub(String kind) {
        var page = new PageSchemaDTO();
        page.setPid("stub-" + kind); page.setId(10L); page.setTenantId(7L);
        page.setStatus("published");
        page.setModelCode("customer"); page.setPageKey("customer_" + kind); page.setKind(kind);
        page.setRowVersion(7); page.setExtension(Map.of("auto_created", true, "source", "platform"));
        return page;
    }
    private void bindFields() {
        when(modelMapper.findCurrentByCode("customer")).thenReturn(new Model());
        var binding = new ModelFieldBinding(); binding.setFieldId(2L);
        when(models.getModelFieldBindings(1L, true)).thenReturn(List.of(binding));
        when(fields.findByIds(List.of(2L))).thenReturn(List.of(new Field()));
    }

    private CrudTemplateConfig permissionGeneration() {
        var config = config("form");
        config.setCreatePermissions(true);
        when(userPermissions.hasPermission(99L, MetaPermission.PERMISSION_MANAGE)).thenReturn(true);
        bindFields();
        var generated = new PageSchema();
        generated.setKind("form"); generated.setName("Customer form"); generated.setBlocks("[]");
        when(dsl.generateFormPage(any(), anyList(), anyList(), any())).thenReturn(generated);
        var created = stub("form");
        when(pages.create(any())).thenReturn(created);
        when(pages.publish(created.getPid())).thenReturn(created);
        return config;
    }

    private PermissionDTO permission(String action, long id) {
        var value = new PermissionDTO();
        value.setId(id); value.setPid("permission-" + id); value.setTenantId(7L);
        value.setCode("model.customer." + action); value.setName("Customer " + action);
        value.setResourceType("model"); value.setResourceCode("customer"); value.setAction(action);
        value.setStatus(StatusConstants.ACTIVE); value.setParentId(77L); value.setSource("generated");
        value.setDataScopeType("SELF");
        return value;
    }

    @Test void reusesRegisteredModelPermissionsWithoutChangingTheirPolicyOrHierarchy() {
        var config = permissionGeneration();
        List<String> actions = List.of("read", "create", "update", "delete");
        var existing = new ArrayList<PermissionDTO>();
        for (int i = 0; i < actions.size(); i++) {
            var value = permission(actions.get(i), 100L + i); existing.add(value);
            when(permissions.findByCode(value.getCode())).thenReturn(value);
        }
        when(permissions.findByCode("model.customer.export"))
                .thenThrow(new ResourceNotFoundException("Permission not found: model.customer.export"));
        when(permissions.create(argThat(request -> "export".equals(request.getAction())
                && "model".equals(request.getResourceType()) && "customer".equals(request.getResourceCode()))))
                .thenReturn(permission("export", 104L));
        var result = generator.generateCrudPages("customer", config);
        assertThat(result.getGeneratedResources().getPermissions()).extracting(value -> value.getId())
                .containsExactly("100", "101", "102", "103", "104");
        for (var value : existing) {
            assertThat(value.getParentId()).isEqualTo(77L);
            assertThat(value.getDataScopeType()).isEqualTo("SELF");
            assertThat(value.getSource()).isEqualTo("generated");
        }
        verify(permissions, times(1)).create(any());
        verify(permissions, never()).update(anyLong(), any());
    }

    @ParameterizedTest @ValueSource(strings = {"tenant", "type", "resource", "action", "status", "code"})
    void refusesRebindingOrReactivatingExistingPermissions(String mismatch) {
        var config = permissionGeneration();
        var existing = permission("read", 100L);
        switch (mismatch) {
            case "tenant" -> existing.setTenantId(8L);
            case "type" -> existing.setResourceType("command");
            case "resource" -> existing.setResourceCode("another_model");
            case "action" -> existing.setAction("delete");
            case "status" -> existing.setStatus(StatusConstants.DEPRECATED);
            default -> existing.setCode("model.another_model.read");
        }
        when(permissions.findByCode("model.customer.read")).thenReturn(existing);
        assertThatThrownBy(() -> generator.generateCrudPages("customer", config))
                .isInstanceOf(ConflictException.class);
        verify(permissions, never()).create(any());
        verify(permissions, never()).update(anyLong(), any());
    }

    @ParameterizedTest @ValueSource(strings = {"list", "form", "detail"})
    void upgradesOnlyTheLockedStubKeepingIdentityMetadataAndExpectedVersion(String kind) {
        var existing = stub(kind);
        when(pages.findByPageKey("customer_" + kind)).thenReturn(existing);
        when(pageMapper.lockAutoCreatedPage(existing.getPid(), 7L, "customer", kind,
                "customer_" + kind, 7)).thenReturn(existing.getPid());
        bindFields();
        var generated = new PageSchema();
        generated.setKind(kind); generated.setName("Customer " + kind); generated.setProfile("admin");
        generated.setTitle("{\"en\":\"Customer\"}");
        generated.setBlocks("[{\"blockType\":\"form-section\",\"fields\":[\"customer_name\"]}]");
        switch (kind) {
            case "list" -> when(dsl.generateListPage(any(), anyList(), anyList(), any())).thenReturn(generated);
            case "form" -> when(dsl.generateFormPage(any(), anyList(), anyList(), any())).thenReturn(generated);
            default -> when(dsl.generateDetailPage(any(), anyList(), anyList(), any())).thenReturn(generated);
        }
        when(pages.update(eq(existing.getPid()), any())).thenReturn(existing);
        when(pages.publish(existing.getPid())).thenReturn(existing);
        var result = generator.generateCrudPages("customer", config(kind));
        ArgumentCaptor<PageSchemaUpdateRequest> update = ArgumentCaptor.forClass(PageSchemaUpdateRequest.class);
        verify(pages).update(eq(existing.getPid()), update.capture());
        assertThat(update.getValue().getRowVersion()).isEqualTo(7);
        assertThat(update.getValue().getExtension()).containsExactlyEntriesOf(Map.of("source", "platform"));
        assertThat(update.getValue().getBlocks()).isNotEmpty();
        assertThat(update.getValue().getModelCode()).isEqualTo("customer");
        assertThat(update.getValue().getTitle()).isEqualTo("{\"en\":\"Customer\"}");
        assertThat(result.getGeneratedResources().getPages().getFirst().getPid()).isEqualTo(existing.getPid());
        String expectedRoute = switch (kind) {
            case "form" -> "/p/customer/new";
            case "detail" -> "/p/customer/view/:recordPid";
            default -> "/p/customer";
        };
        assertThat(result.getGeneratedResources().getPages().getFirst().getRoute()).isEqualTo(expectedRoute);
        assertThat(result.getAccessLinks().getListPage()).isEqualTo("/p/customer");
        assertThat(result.getAccessLinks().getFormPage()).isEqualTo("/p/customer/new");
        assertThat(result.getAccessLinks().getDetailPage()).isEqualTo("/p/customer/view/:recordPid");
        verify(pages, never()).create(any());
        InOrder order = inOrder(pageMapper, pages);
        order.verify(pageMapper).lockAutoCreatedPage(existing.getPid(), 7L, "customer", kind, "customer_" + kind, 7);
        order.verify(pages).unpublish(existing.getPid());
        order.verify(pages).update(eq(existing.getPid()), any());
        order.verify(pages).publish(existing.getPid());
    }

    @Test void rejectsAuthoredPagesBeforeAnyLockOrWrite() {
        var existing = stub("list"); existing.setExtension(Map.of("source", "author"));
        when(pages.findByPageKey("customer_list")).thenReturn(existing);
        assertThatThrownBy(() -> generator.generateCrudPages("customer", config("list")))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(pageMapper, modelMapper, fields, dsl);
        verify(pages, never()).update(anyString(), any()); verify(pages, never()).create(any());
    }

    @ParameterizedTest @ValueSource(strings = {"tenant", "model", "kind", "key"})
    void rejectsMismatchedIdentityBeforeWriting(String mismatch) {
        var existing = stub("list");
        switch (mismatch) {
            case "tenant" -> existing.setTenantId(8L);
            case "model" -> existing.setModelCode("another_model");
            case "kind" -> existing.setKind("form");
            default -> existing.setPageKey("another_key");
        }
        when(pages.findByPageKey("customer_list")).thenReturn(existing);
        assertThatThrownBy(() -> generator.generateCrudPages("customer", config("list")))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(pageMapper, modelMapper, fields, dsl);
        verify(pages, never()).update(anyString(), any());
    }

    @Test void checksEverySelectedPageBeforeUpgradingAnyStub() {
        var config = config("list"); config.setGenerateForm(true);
        when(pages.findByPageKey("customer_list")).thenReturn(stub("list"));
        var authored = stub("form"); authored.setExtension(Map.of());
        when(pages.findByPageKey("customer_form")).thenReturn(authored);
        assertThatThrownBy(() -> generator.generateCrudPages("customer", config))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(pageMapper, modelMapper, fields, dsl);
        verify(pages, never()).update(anyString(), any()); verify(pages, never()).publish(anyString());
        verify(pages, never()).unpublish(anyString());
    }

    @Test void refusesConcurrentVersionOrMarkerChangeBeforeAnyUpgrade() {
        var config = config("list"); config.setGenerateForm(true);
        var list = stub("list"); var form = stub("form");
        when(pages.findByPageKey("customer_list")).thenReturn(list);
        when(pages.findByPageKey("customer_form")).thenReturn(form);
        when(pageMapper.lockAutoCreatedPage("stub-list", 7L, "customer", "list", "customer_list", 7))
                .thenReturn("stub-list");
        // Second row no longer matches the exact version/stub predicate.
        assertThatThrownBy(() -> generator.generateCrudPages("customer", config))
                .isInstanceOf(ConflictException.class).hasMessageContaining("changed");
        verify(pageMapper).lockAutoCreatedPage("stub-form", 7L, "customer", "form", "customer_form", 7);
        verifyNoInteractions(modelMapper, fields, dsl);
        verify(pages, never()).update(anyString(), any()); verify(pages, never()).publish(anyString());
        verify(pages, never()).unpublish(anyString());
    }
}
