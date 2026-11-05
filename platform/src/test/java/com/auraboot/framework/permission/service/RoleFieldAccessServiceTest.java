package com.auraboot.framework.permission.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.entity.DataPermissionPolicy;
import com.auraboot.framework.meta.mapper.DataPermissionPolicyMapper;
import com.auraboot.framework.meta.mapper.DataPermissionRoleBindingMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.permission.dto.RoleFieldAccess.Change;
import com.auraboot.framework.rbac.entity.Role;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoleFieldAccessServiceTest {
    final DataPermissionPolicyMapper policies=mock(DataPermissionPolicyMapper.class);
    final DataPermissionRoleBindingMapper bindings=mock(DataPermissionRoleBindingMapper.class);
    final MetaModelService models=mock(MetaModelService.class);
    final DataPermissionAuthoringAccess authoring=mock(DataPermissionAuthoringAccess.class);
    final RoleFieldAccessService service=new RoleFieldAccessService(policies,bindings,models,authoring);
    final Role role=new Role();
    @BeforeEach void fixture(){
        MetaContext.setContext(7L,9L,"user","test");role.setTenantId(7L);role.setPid("role-a");
        var definition=ModelDefinition.builder().displayName("BOM project").build();
        when(models.getModelDefinition("bom")).thenReturn(Optional.of(definition));
        when(models.getModelFields("bom")).thenReturn(List.of(
            FieldDefinition.builder().code("remark").displayName("Engineering remark").build(),
            FieldDefinition.builder().code("pid").displayName("Identity").primaryKey(true).build()));
    }
    @AfterEach void clear(){MetaContext.clear();}
    @Test void previewUsesModelLabelsWithoutWritingOrGrantingBusinessAccess(){
        var result=service.preview(role,List.of(new Change("bom","remark","hidden")),Set.of("bom"));
        assertThat(result).singleElement().satisfies(r->{assertThat(r.fieldLabel()).isEqualTo("Engineering remark");assertThat(r.beforeAccess()).isEqualTo("inherit");assertThat(r.access()).isEqualTo("hidden");});
        verify(policies,never()).insert(any(DataPermissionPolicy.class));verify(authoring).requireWrite();verifyNoMoreInteractions(authoring);
        verify(bindings,never()).insertBinding(any(),any(),any());
    }
    @Test void restrictsOnlyFieldsInTheResultingBusinessResourceSet(){
        assertThatThrownBy(()->service.preview(role,List.of(new Change("bom","remark","read_only")),Set.of())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->service.preview(role,List.of(new Change("bom","pid","hidden")),Set.of("bom"))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->service.preview(role,List.of(new Change("bom","remark","all")),Set.of("bom"))).isInstanceOf(AccessDeniedException.class);
        verify(policies,never()).insert(any(DataPermissionPolicy.class));
    }
    @Test void omissionPreservesExistingRestrictionsWithoutRequestingPolicyManagement(){
        assertThat(service.preview(role,null,Set.of())).isEmpty();verifyNoInteractions(authoring,policies,bindings,models);
    }
    @Test void ownerAndDuplicateChecksPrecedeWrites(){
        assertThatThrownBy(()->service.preview(role,List.of(new Change("bom","remark","hidden"),new Change("bom","remark","read_only")),Set.of("bom"))).isInstanceOf(AccessDeniedException.class);
        role.setTenantId(8L);assertThatThrownBy(()->service.catalog(role,Set.of("bom"))).isInstanceOf(AccessDeniedException.class);
    }
}
