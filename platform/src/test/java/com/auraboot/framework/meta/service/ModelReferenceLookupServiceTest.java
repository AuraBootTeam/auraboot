package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.permission.engine.PermissionEvaluator;
import com.auraboot.framework.permission.service.FieldPermissionService;
import com.auraboot.framework.permission.engine.model.FieldPermissionSet;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ModelReferenceLookupServiceTest {
    private final MetaModelService models = mock(MetaModelService.class);
    private final DynamicDataService data = mock(DynamicDataService.class);
    private final PermissionEvaluator permissions = mock(PermissionEvaluator.class);
    private final FieldPermissionService fieldPermissions = mock(FieldPermissionService.class);
    private final ModelReferenceLookupService service = new ModelReferenceLookupService(models, data, permissions, fieldPermissions);
    @BeforeEach void setup() {
        MetaContext.setContext(20L, 100L, "user", "Tester");
        when(fieldPermissions.getFieldPermissions(anyLong(), anyString())).thenReturn(FieldPermissionSet.allAllowed(Set.of()));
        when(permissions.canAction(anyLong(), anyString(), eq("read"))).thenReturn(true);
        var field = FieldDefinition.builder().code("customer_ref").dataType("reference")
                .refTarget(FieldDefinition.RefTarget.builder().targetEntity("customer")
                        .valueField("pid").targetField("customer_name").build()).build();
        when(models.getModelDefinition("project")).thenReturn(Optional.of(ModelDefinition.builder().fields(List.of(field)).build()));
    }
    @AfterEach void clear() { MetaContext.clear(); }
    @Test void usesAuthoritativeReferenceAndExactVisibleRecord() {
        when(data.list(eq("customer"), any())).thenReturn(PaginationResult.of(List.of(Map.of("pid", "customer-pid", "private_note", "never return")),1L,1,2));
        var resolved = service.resolve("project", "customer_ref", "Manufacturing");
        assertThat(resolved).containsEntry("storedValue", "customer-pid").containsEntry("targetModel", "customer");
        assertThat(resolved).doesNotContainKey("private_note");
        var request = ArgumentCaptor.forClass(DynamicQueryRequest.class); verify(data).list(eq("customer"), request.capture());
        assertThat(request.getValue().getPageSize()).isEqualTo(2);
        assertThat(request.getValue().getConditions()).singleElement().satisfies(condition -> {
            assertThat(condition.getFieldName()).isEqualTo("customer_name");
            assertThat(condition.getOperator()).isEqualTo(QueryCondition.Operator.EQ);
            assertThat(condition.getValue()).isEqualTo("Manufacturing");
        });
    }
    @Test void missingValueDoesNotGuessAnId() {
        when(data.list(eq("customer"),any())).thenReturn(PaginationResult.empty(1,2));
        assertThatThrownBy(() -> service.resolve("project","customer_ref","Unknown")).hasMessageContaining("does not exist or is not visible");
    }
    @Test void duplicateLabelsRequireHumanSelection() {
        when(data.list(eq("customer"),any())).thenReturn(PaginationResult.of(List.of(Map.of("pid","one"),Map.of("pid","two")),2L,1,2));
        assertThatThrownBy(() -> service.resolve("project","customer_ref","Duplicate")).hasMessageContaining("ambiguous");
    }
    @Test void revokedSourcePermissionStopsBeforeMetadataAndData() {
        when(permissions.canAction(100L,"project","read")).thenReturn(false);
        assertThatThrownBy(() -> service.resolve("project","customer_ref","Manufacturing")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(data); verify(models,never()).getModelDefinition(anyString());
    }
    @Test void revokedTargetPermissionStopsBeforeQuery() {
        when(permissions.canAction(100L,"customer","read")).thenReturn(false);
        assertThatThrownBy(() -> service.resolve("project","customer_ref","Manufacturing")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(data);
    }
    @Test void hiddenSourceFieldStopsBeforeMetadata() {
        when(fieldPermissions.getFieldPermissions(100L,"project")).thenReturn(new FieldPermissionSet(Set.of(),Set.of(),Set.of("customer_ref")));
        assertThatThrownBy(() -> service.resolve("project","customer_ref","Manufacturing")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(models, data);
    }
    @Test void hiddenTargetLabelCannotBeUsedAsAQueryOracle() {
        when(fieldPermissions.getFieldPermissions(100L,"customer")).thenReturn(new FieldPermissionSet(Set.of(),Set.of(),Set.of("customer_name")));
        assertThatThrownBy(() -> service.resolve("project","customer_ref","Manufacturing")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(data);
    }
    @Test void unavailableFieldPermissionsFailClosed() {
        when(fieldPermissions.getFieldPermissions(100L,"customer")).thenReturn(null);
        assertThatThrownBy(() -> service.resolve("project","customer_ref","Manufacturing")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(data);
    }
    @Test void undeclaredFieldCannotBeUsedForArbitraryLookup() {
        assertThatThrownBy(() -> service.resolve("project","unconfigured","Manufacturing")).hasMessageContaining("field is unavailable");
        verifyNoInteractions(data);
    }
}
