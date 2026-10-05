package com.auraboot.framework.meta.service.impl;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.user.mapper.UserMapper;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.permission.engine.model.FieldPermissionSet;
import com.auraboot.framework.permission.service.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Hidden fields are removed even when audit persistence is unavailable. */
class DynamicDataReadGovernanceTest {
    final FieldPermissionService fields=mock(FieldPermissionService.class);
    final PermissionAuditService audit=mock(PermissionAuditService.class);
    final DynamicDataReadSupport support=new DynamicDataReadSupport(mock(MetaModelService.class),mock(DynamicDataMapper.class),mock(UserMapper.class),mock(FileService.class),mock(DataPermissionEngine.class),fields,()->audit,()->mock(PermissionFacade.class),()->43L);
    @BeforeEach void context() { MetaContext.setContext(42L,43L,"u-43","tester"); }
    @AfterEach void clear() { MetaContext.clear(); }
    @Test void hiddenFieldsAreAuditedWithPublicIdentityThenRemovedDespiteAuditFailure() {
        when(fields.getFieldPermissions(43L,"invoice")).thenReturn(new FieldPermissionSet(Set.of("amount"),Set.of(),Set.of("secret")));
        doThrow(new IllegalStateException("audit offline")).when(audit).logFieldGovernanceFilter(42L,43L,"invoice","read",7L,"invoice-42",List.of("secret"));
        var record=new HashMap<String,Object>(Map.of("id","7","pid","invoice-42","secret","private","amount",10));
        assertThat(support.applyFieldPermissionFilterSingle("invoice",record)).doesNotContainKey("secret").containsEntry("amount",10);
        verify(audit).logFieldGovernanceFilter(42L,43L,"invoice","read",7L,"invoice-42",List.of("secret"));
    }
    @Test void malformedInternalIdDoesNotBlockFilteringAndEmptyPublicIdIsNotInvented() {
        when(fields.getFieldPermissions(43L,"invoice")).thenReturn(new FieldPermissionSet(Set.of(),Set.of(),Set.of("secret")));
        var record=new HashMap<String,Object>(Map.of("id","not-numeric","pid"," ","secret","private"));
        assertThat(support.applyFieldPermissionFilterSingle("invoice",record)).doesNotContainKey("secret");
        verify(audit).logFieldGovernanceFilter(42L,43L,"invoice","read",null,null,List.of("secret"));
    }
    @Test void fieldEvaluationFailuresDenyTheResponseBeforeAudit() {
        when(fields.getFieldPermissions(43L,"invoice")).thenThrow(new IllegalStateException("policy unavailable"));
        assertThatThrownBy(() -> support.applyFieldPermissionFilterSingle("invoice",new HashMap<>(Map.of("secret","private")))).isInstanceOf(MetaServiceException.class);
        verifyNoInteractions(audit);
    }
}
