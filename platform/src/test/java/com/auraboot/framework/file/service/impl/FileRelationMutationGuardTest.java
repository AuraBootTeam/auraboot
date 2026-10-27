package com.auraboot.framework.file.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.DataAccessAuthorizationHelper;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FileRelationMutationGuardTest {
    private final ApplicationContext beans=mock(ApplicationContext.class);
    private final DynamicDataMapper records=mock(DynamicDataMapper.class);
    private final MetaModelService models=mock(MetaModelService.class);
    private final DynamicDataService data=mock(DynamicDataService.class);
    private final DataAccessAuthorizationHelper permissions=mock(DataAccessAuthorizationHelper.class);
    private final FileRelationMutationGuard guard=new FileRelationMutationGuard(beans,records);
    private final FieldDefinition photos=FieldDefinition.builder().code("photos").columnName("physical_photos")
            .dataType("file").allowedWriterCommands(List.of("order:repair")).build();
    private final ModelDefinition model=ModelDefinition.builder().code("order").status("published")
            .tableName("mt_order").fields(List.of(photos)).build();
    @BeforeEach void setup() {
        MetaContext.setContext(1L,2L,"user","user");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(beans.getBean(MetaModelService.class)).thenReturn(models);
        when(beans.getBean(DynamicDataService.class)).thenReturn(data);
        when(beans.getBean(DataAccessAuthorizationHelper.class)).thenReturn(permissions);
        when(models.getModelDefinition("order")).thenReturn(Optional.of(model));
        when(records.selectRecordWriterTargetsForUpdate(anyString(),anyString(),anyString(),anyMap())).thenReturn(List.of(Map.of("pid","parent")));
        when(data.getById("order","parent")).thenReturn(Map.of("pid","parent","tenant_id",1L,"state","released","row_version",1L));
        when(permissions.authorizeRecord(eq("order"),eq("update"),anyMap())).thenReturn(true);
    }
    @AfterEach void clear() { TransactionSynchronizationManager.clear(); MetaContext.clear(); }
    @Test void broadParentUpdateDoesNotGrantAFieldWriterIncludingPhysicalAliases() {
        assertThatThrownBy(()->guard.authorize("order","parent","photos")).hasMessageContaining("FIELD_WRITER_DENIED");
        assertThatThrownBy(()->guard.authorize("order","parent","physical_photos")).hasMessageContaining("FIELD_WRITER_DENIED");
        verify(permissions,times(2)).authorizeRecord(eq("order"),eq("update"),anyMap());
    }
    @Test void exactWriterAllowsMutableFileButCannotOverrideFieldOrModelImmutability() {
        MetaContext.runWithCommandPermitPlan("TARGET",1L,"order","parent",()->
            MetaContext.runWithAuthorizedCommandCode("order:repair",()->{
                guard.authorize("order","parent","photos");
                photos.setImmutable(true); assertThatThrownBy(()->guard.authorize("order","parent","photos")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
                photos.setImmutable(false); photos.setImmutableWhen(new FieldDefinition.ImmutableWhen("state",List.of("released")));
                assertThatThrownBy(()->guard.authorize("order","parent","photos")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
                photos.setImmutableWhen(null); model.setImmutable(true);
                assertThatThrownBy(()->guard.authorize("order","parent","opaque-export-slot")).hasMessageContaining("immutable");
            }));
    }
    @Test void ordinaryLegacyFileAndOpaqueExportSlotsRetainTheirExistingMutableSemantics() {
        photos.setAllowedWriterCommands(null);
        guard.authorize("order","parent","photos"); guard.authorize("order","parent","version-attachment-slot");
    }
    @Test void modelWriterPolicyAppliesEvenToOpaqueLegacySlots() {
        model.setExtension(Map.of("commandWriters",Map.of("create",List.of("order:create"),"update",List.of("order:repair"),"delete",List.of("order:delete"))));
        assertThatThrownBy(()->guard.authorize("order","parent","opaque-slot")).hasMessageContaining("exact authorized command");
    }
}
