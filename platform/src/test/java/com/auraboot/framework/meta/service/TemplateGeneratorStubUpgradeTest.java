package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.ConflictException;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.*;
import com.auraboot.framework.meta.mapper.*;
import com.auraboot.framework.meta.template.dto.CrudTemplateConfig;
import com.auraboot.framework.meta.template.generator.DslGenerator;
import com.auraboot.framework.meta.template.service.impl.TemplateGeneratorServiceImpl;
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
