package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator;
import com.auraboot.framework.application.security.MachineDataAccessContext;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.plugin.extension.CommandRecordReadLockAccessor.RecordRef;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Hermetic authority/resource protocol. Physical contention is a separate real-stack obligation. */
class CommandRecordReadLockAccessorImplTest {
    final MetaModelService models=mock(MetaModelService.class);
    final DynamicDataService data=mock(DynamicDataService.class);
    final DynamicDataMapper mapper=mock(DynamicDataMapper.class);
    final DataSource source=mock(DataSource.class);
    final Connection connection=mock(Connection.class);
    final RecordRef a=new RecordRef("lock_a",id(1)),b=new RecordRef("lock_b",id(2));
    CommandRecordReadLockAccessorImpl service;
    static String id(int n) { return String.format("%026d",n); }
    static ModelDefinition model(String code,String table) {
        return ModelDefinition.builder().code(code).tableName(table).modelType("entity").sourceType("physical").primaryKey("pid").build();
    }
    @BeforeEach void prepare() {
        MetaContext.setContext(7L,20L,"actor","Actor");MetaContext.setMemberId(30L);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        var holder=new ConnectionHolder(connection);holder.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.bindResource(source,holder);
        var sessions=mock(SqlSessionFactory.class);var config=new Configuration();
        config.setEnvironment(new Environment("unit",mock(org.apache.ibatis.transaction.TransactionFactory.class),source));
        when(sessions.getConfiguration()).thenReturn(config);
        service=new CommandRecordReadLockAccessorImpl(models,data,mapper,sessions);
        when(models.getModelDefinition(a.modelCode())).thenReturn(Optional.of(model(a.modelCode(),"mt_z")));
        when(models.getModelDefinition(b.modelCode())).thenReturn(Optional.of(model(b.modelCode(),"mt_a")));
        when(data.getById(anyString(),anyString())).thenAnswer(call->Map.<String,Object>of("pid",call.<String>getArgument(1),"tenant_id",7L));
        when(mapper.selectTargetVersionForUpdate(anyString(),eq("pid"),eq(7L),anyString()))
                .thenReturn(List.of(Map.of("row_version",1L)));
    }
    @AfterEach void clear() {
        if(TransactionSynchronizationManager.hasResource(source))TransactionSynchronizationManager.unbindResource(source);
        if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);MetaContext.clear();
    }
    void commitCheck() { TransactionSynchronizationManager.getSynchronizations().forEach(s->s.beforeCommit(false)); }
    @Test void locksPhysicalTargetsInOrderAndRechecksOrdinaryReadsWithoutAnyMutation() {
        service.requireReadableRecords(List.of(a,b));
        var order=inOrder(data,mapper);
        order.verify(data).getById(b.modelCode(),b.recordPid());
        order.verify(mapper).selectTargetVersionForUpdate("mt_a","pid",7L,b.recordPid());
        order.verify(data).getById(b.modelCode(),b.recordPid());
        order.verify(data).getById(a.modelCode(),a.recordPid());
        order.verify(mapper).selectTargetVersionForUpdate("mt_z","pid",7L,a.recordPid());
        order.verify(data).getById(a.modelCode(),a.recordPid());
        verifyNoMoreInteractions(data,mapper);commitCheck();
    }
    @Test void aliasesStillAuthorizeIndividuallyButLockTheSamePhysicalRowOnce() {
        var alias=new RecordRef("lock_alias",a.recordPid());
        when(models.getModelDefinition(alias.modelCode())).thenReturn(Optional.of(model(alias.modelCode(),"mt_z")));
        service.requireReadableRecords(List.of(alias,a));
        verify(mapper,times(1)).selectTargetVersionForUpdate("mt_z","pid",7L,a.recordPid());
        verify(data,times(2)).getById(a.modelCode(),a.recordPid());
        verify(data,times(2)).getById(alias.modelCode(),alias.recordPid());commitCheck();
    }
    @Test void commandPermitIsRemovedForEveryReadAndRestoredExactly() {
        when(data.getById(anyString(),anyString())).thenAnswer(call->{
            assertFalse(MetaContext.hasCommandPermitScope());return Map.<String,Object>of("pid",call.<String>getArgument(1),"tenant_id",7L);
        });
        MetaContext.runWithCommandPermitPlan("ALL",9L,a.modelCode(),a.recordPid(),()->{
            service.requireReadableRecords(List.of(a));assertEquals("ALL",MetaContext.getCommandPermitScopeFor(a.modelCode()));return null;
        });commitCheck();
    }
    @ParameterizedTest @ValueSource(strings={"no-tx","read-only","no-sync","foreign-resource","not-synchronized","autocommit","physical-read-only","closed"})
    void refusesAnUnrelatedOrNonWritableTransaction(String fault) throws Exception {
        switch(fault) {
            case "no-tx" -> TransactionSynchronizationManager.setActualTransactionActive(false);
            case "read-only" -> TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            case "no-sync" -> TransactionSynchronizationManager.clearSynchronization();
            case "foreign-resource" -> TransactionSynchronizationManager.unbindResource(source);
            case "not-synchronized" -> ((ConnectionHolder)TransactionSynchronizationManager.getResource(source)).setSynchronizedWithTransaction(false);
            case "autocommit" -> when(connection.getAutoCommit()).thenReturn(true);
            case "physical-read-only" -> when(connection.isReadOnly()).thenReturn(true);
            case "closed" -> when(connection.isClosed()).thenReturn(true);
        }
        assertThrows(IllegalStateException.class,()->service.requireReadableRecords(List.of(a)));
        verifyNoInteractions(models,data,mapper);
    }
    @Test void rejectsMachineAndMissingHumanMembershipBeforeReadingOrLocking() {
        try(var ignored=MachineDataAccessContext.begin(new ExternalMachineAuthenticator.MachinePrincipal(7L,"machine","Machine",Set.of()))) {
            assertThrows(AccessDeniedException.class,()->service.requireReadableRecords(List.of(a)));
        }
        MetaContext.setMemberId(null);assertThrows(AccessDeniedException.class,()->service.requireReadableRecords(List.of(a)));
        MetaContext.clear();assertThrows(AccessDeniedException.class,()->service.requireReadableRecords(List.of(a)));
        verifyNoInteractions(models,data,mapper);
    }
    @Test void deniedBeforeLockDoesNotAcquireItAndCaughtFailureStillCannotCommit() {
        when(data.getById(a.modelCode(),a.recordPid())).thenThrow(new AccessDeniedException("revoked"));
        assertThrows(AccessDeniedException.class,()->service.requireReadableRecords(List.of(a)));
        verifyNoInteractions(mapper);assertThrows(IllegalStateException.class,this::commitCheck);
        assertThrows(IllegalStateException.class,()->service.requireReadableRecords(List.of(a)));
    }
    @Test void denialAfterLockCannotBecomeAQualificationOrACommit() {
        when(data.getById(a.modelCode(),a.recordPid())).thenReturn(Map.of("pid",a.recordPid())).thenThrow(new AccessDeniedException("revoked"));
        assertThrows(AccessDeniedException.class,()->service.requireReadableRecords(List.of(a)));
        verify(mapper).selectTargetVersionForUpdate("mt_z","pid",7L,a.recordPid());
        assertThrows(IllegalStateException.class,this::commitCheck);
    }
    @ParameterizedTest @ValueSource(strings={"missing","wrong-pid","wrong-tenant"})
    void incompleteOrForeignReadResultsNeverLock(String fault) {
        Map<String,Object> row=switch(fault) {
            case "missing" -> null;
            case "wrong-pid" -> Map.of("pid",id(99));
            default -> Map.of("pid",a.recordPid(),"tenant_id",8L);
        };
        when(data.getById(a.modelCode(),a.recordPid())).thenReturn(row);
        assertThrows(AccessDeniedException.class,()->service.requireReadableRecords(List.of(a)));verifyNoInteractions(mapper);
    }
    @Test void aMissingPhysicalTargetCannotPassTheSecondRead() {
        when(mapper.selectTargetVersionForUpdate(anyString(),anyString(),anyLong(),anyString())).thenReturn(List.of());
        assertThrows(AccessDeniedException.class,()->service.requireReadableRecords(List.of(a)));
        verify(data,times(1)).getById(a.modelCode(),a.recordPid());assertThrows(IllegalStateException.class,this::commitCheck);
    }
    @ParameterizedTest @ValueSource(strings={"view","remote","other-key","unsafe-table"})
    void unsupportedModelShapesFailBeforeDataAccess(String shape) {
        var value=model(a.modelCode(),"mt_z");switch(shape) {
            case "view" -> value.setModelType("view");case "remote" -> value.setSourceType("remote");
            case "other-key" -> value.setPrimaryKey("external_key");default -> value.setTableName("mt_z; DROP TABLE ab_user");
        }
        when(models.getModelDefinition(a.modelCode())).thenReturn(Optional.of(value));
        assertThrows(IllegalArgumentException.class,()->service.requireReadableRecords(List.of(a)));verifyNoInteractions(data,mapper);
    }
    @Test void subsetReuseReauthorizesWithoutGrowingThePhysicalLockSet() {
        service.requireReadableRecords(List.of(a,b));service.requireReadableRecords(List.of(a));
        verify(mapper,times(1)).selectTargetVersionForUpdate("mt_z","pid",7L,a.recordPid());
        verify(data,times(4)).getById(a.modelCode(),a.recordPid());
        var extra=new RecordRef(a.modelCode(),id(3));
        assertThrows(IllegalStateException.class,()->service.requireReadableRecords(List.of(extra)));
        assertThrows(IllegalStateException.class,this::commitCheck);
    }
    @Test void changedActorAndReboundModelCannotBorrowHeldLocks() {
        service.requireReadableRecords(List.of(a));
        MetaContext.setContext(7L,21L,"other","Other");MetaContext.setMemberId(31L);
        assertThrows(IllegalStateException.class,()->service.requireReadableRecords(List.of(a)));
        assertThrows(IllegalStateException.class,this::commitCheck);
    }
    @Test void aModelReboundDuringTheFinalReadInvalidatesTheLockScope() {
        var value=models.getModelDefinition(a.modelCode()).orElseThrow();
        when(data.getById(a.modelCode(),a.recordPid())).thenReturn(Map.of("pid",a.recordPid())).thenAnswer(call->{
            value.setTableName("mt_rebound");return Map.of("pid",a.recordPid());
        });
        assertThrows(IllegalStateException.class,()->service.requireReadableRecords(List.of(a)));
        assertThrows(IllegalStateException.class,this::commitCheck);
    }
    @Test void firstAcquisitionCannotReenterWithALaterPhysicalRoot() {
        doAnswer(call->{
            assertThrows(IllegalStateException.class,()->service.requireReadableRecords(List.of(a)));
            return Map.of("pid",b.recordPid(),"tenant_id",7L);
        }).when(data).getById(b.modelCode(),b.recordPid());
        assertThrows(IllegalStateException.class,()->service.requireReadableRecords(List.of(a,b)));
        verifyNoInteractions(mapper);assertThrows(IllegalStateException.class,this::commitCheck);
    }
    @Test void subsetRevalidationCannotRecursivelyBorrowTheActiveScope() {
        service.requireReadableRecords(List.of(a,b));
        doAnswer(call->{
            assertThrows(IllegalStateException.class,()->service.requireReadableRecords(List.of(b)));
            return Map.of("pid",a.recordPid(),"tenant_id",7L);
        }).when(data).getById(a.modelCode(),a.recordPid());
        assertThrows(IllegalStateException.class,()->service.requireReadableRecords(List.of(a)));
        verify(mapper,times(1)).selectTargetVersionForUpdate("mt_z","pid",7L,a.recordPid());
        verify(mapper,times(1)).selectTargetVersionForUpdate("mt_a","pid",7L,b.recordPid());
        assertThrows(IllegalStateException.class,this::commitCheck);
    }
    @Test void metadataResolutionCannotCreateASecondScopeBeforePhysicalOrdering() {
        doAnswer(call->{
            assertThrows(IllegalStateException.class,()->service.requireReadableRecords(List.of(b)));
            return Optional.of(model(a.modelCode(),"mt_z"));
        }).when(models).getModelDefinition(a.modelCode());
        assertThrows(IllegalStateException.class,()->service.requireReadableRecords(List.of(a,b)));
        verifyNoInteractions(data,mapper);assertThrows(IllegalStateException.class,this::commitCheck);
    }
    @Test void invalidCompleteSetsAndMalformedPublicIdentitiesAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new RecordRef("bad/model",id(1)));
        assertThrows(IllegalArgumentException.class,()->new RecordRef("lock_a","../row"));
        assertThrows(IllegalArgumentException.class,()->service.requireReadableRecords(null));
        assertThrows(IllegalArgumentException.class,()->service.requireReadableRecords(List.of()));
        assertThrows(IllegalArgumentException.class,()->service.requireReadableRecords(List.of(a,a)));
        assertThrows(IllegalArgumentException.class,()->service.requireReadableRecords(java.util.stream.IntStream.rangeClosed(1,33).mapToObj(n->new RecordRef(a.modelCode(),id(n))).toList()));
        verifyNoInteractions(models,data,mapper);
    }
}
