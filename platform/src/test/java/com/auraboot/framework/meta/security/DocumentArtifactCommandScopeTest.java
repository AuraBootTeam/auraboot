package com.auraboot.framework.meta.security;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.impl.pipeline.CommandAuthorizationVerdict;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.permission.entity.Permission;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentArtifactCommandScopeTest {
    static final String COMMAND = "document:generate", DUTY = "example.cap.write", ROOT = "example_document", PID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
    final CommandPipelineContext context = mock(CommandPipelineContext.class);
    final Map<String,Object> definition = Map.of("code","example.standard","version","1.0.0",
            "dataset",ROOT+":snapshot_v1","versionPolicy","immutable-artifact","outputs",List.of("xlsx","pdf"));
    @BeforeEach void prepare() {
        MetaContext.setContext(100L,1L,"actor-pid","actor"); MetaContext.setMemberId(5L);
        var command = new CommandDefinition(); command.setModelCode(ROOT);
        var request = new CommandExecuteRequest(); request.setTargetRecordPid(PID);
        when(context.getCommand()).thenReturn(command);when(context.getRequest()).thenReturn(request);
        when(context.getCommandCode()).thenReturn(COMMAND);when(context.getTenantId()).thenReturn(100L);when(context.getUserId()).thenReturn(1L);
        when(context.getTargetRecordReadable()).thenReturn(true);
        when(context.getAuthorizationVerdict()).thenReturn(CommandAuthorizationVerdict.authorized(DUTY));
        when(context.getExecConfig()).thenReturn(Map.of("handlerParams",Map.of("documentDefinition",definition)));
    }
    @AfterEach void clear() { MetaContext.clear(); }
    private Permission permission(String action) {
        var p=new Permission();p.setCode(DUTY);p.setResourceType("operation");p.setAction(action);
        p.setExtension(Map.of("domainModels",Map.of(ROOT,List.of("read","update"))));return p;
    }
    private Map<String,Object> artifact() {
        return new HashMap<>(Map.of("pid","artifact-1","tenant_id",100L,"created_by",1L,
                "ab_da_source_model",ROOT,"ab_da_source_record_pid",PID,"ab_da_definition_code","example.standard",
                "ab_da_definition_version","1.0.0","ab_da_format","xlsx"));
    }
    private <T>T execute(Supplier<T> body) { return execute(null,body); }
    private <T>T execute(Supplier<com.auraboot.framework.meta.service.impl.CommandPublicOutputProjectionPlan> projection,Supplier<T> body) {
        return MetaContext.runWithCommandPermitPlan("TARGET",null,ROOT,PID,()->MetaContext.runWithCommandAuthority(DUTY,()->
                MetaContext.runWithCommandAggregate(PID,()->MetaContext.runWithAuthorizedCommandCode(COMMAND,()->{
                    try{return DocumentArtifactCommandScope.within(context,projection,body::get);}catch(RuntimeException e){throw e;}
                    catch(Exception e){throw new AssertionError(e);}
                }))));
    }
    @Test void borrowsOnlyTheAdmittedWriteOrManageDuty() {
        execute(()->{
            assertThat(DocumentArtifactCommandScope.authorityFor("ab_document_artifact",artifact(),"create")).isEqualTo(DUTY);
            assertThat(DocumentArtifactCommandScope.permits(permission("write"),"ab_document_artifact",artifact(),"create")).isTrue();
            assertThat(DocumentArtifactCommandScope.permits(permission("manage"),"ab_document_artifact",artifact(),"create")).isTrue();
            assertThat(DocumentArtifactCommandScope.permits(permission("read"),"ab_document_artifact",artifact(),"create")).isFalse();
            assertThat(DocumentArtifactCommandScope.permits(permission(null),"ab_document_artifact",artifact(),"create")).isFalse();
            return null;
        });
    }
    @Test void refusesOtherRecordsActorsDefinitionsFormatsModelsAndActions() {
        execute(()->{
            for(var change:Map.of("tenant_id",200L,"created_by",2L,"ab_da_source_model","foreign",
                    "ab_da_source_record_pid","foreign","ab_da_definition_code","foreign",
                    "ab_da_definition_version","2.0.0","ab_da_format","html","pid","").entrySet()) {
                var row=artifact();row.put(change.getKey(),change.getValue());
                assertThat(DocumentArtifactCommandScope.authorityFor("ab_document_artifact",row,"create")).as(change.getKey()).isNull();
            }
            assertThat(DocumentArtifactCommandScope.authorityFor("another_model",artifact(),"create")).isNull();
            assertThat(DocumentArtifactCommandScope.authorityFor("ab_document_artifact",artifact(),"update")).isNull();
            return null;
        });
    }
    @Test void cannotBorrowOutsideTheInvocationOrAfterACommandSwap() {
        assertThat(DocumentArtifactCommandScope.authorityFor("ab_document_artifact",artifact(),"create")).isNull();
        assertThatThrownBy(()->DocumentArtifactCommandScope.outputPlan(context))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        execute(()->MetaContext.runWithAuthorizedCommandCode("other:command",()->{
            assertThat(DocumentArtifactCommandScope.authorityFor("ab_document_artifact",artifact(),"create")).isNull();return null;
        }));
        assertThat(DocumentArtifactCommandScope.authorityFor("ab_document_artifact",artifact(),"create")).isNull();
    }
    @Test void confinesEachFormatToOnePhysicalInsert() {
        execute(()->{
            assertThat(DocumentArtifactCommandScope.authorityFor("ab_document_artifact",artifact(),"create")).isEqualTo(DUTY);
            var duplicate=artifact();duplicate.put("pid","artifact-2");
            assertThat(DocumentArtifactCommandScope.authorityFor("ab_document_artifact",duplicate,"create")).isNull();
            duplicate.put("ab_da_format","pdf");
            assertThat(DocumentArtifactCommandScope.authorityFor("ab_document_artifact",duplicate,"create")).isEqualTo(DUTY);return null;
        });
    }
    @Test void restoresTheScopeEvenWhenTheHandlerThrows() {
        assertThatThrownBy(()->execute(()->{throw new IllegalStateException("handler failed");})).isInstanceOf(IllegalStateException.class);
        assertThat(DocumentArtifactCommandScope.authorityFor("ab_document_artifact",artifact(),"create")).isNull();
    }
    @Test void missingDocumentMetadataDoesNotGrantAnEffect() {
        when(context.getExecConfig()).thenReturn(Map.of());
        execute(()->{assertThat(DocumentArtifactCommandScope.authorityFor("ab_document_artifact",artifact(),"create")).isNull();return null;});
    }
    @Test void nestedOrdinaryInvocationCannotInheritTheDocumentEffect() {
        execute(()->{
            when(context.getExecConfig()).thenReturn(Map.of());
            try {
                DocumentArtifactCommandScope.within(context,()->{
                    assertThat(DocumentArtifactCommandScope.authorityFor("ab_document_artifact",artifact(),"create")).isNull();
                    return null;
                });
            } catch(Exception e) { throw new AssertionError(e); }
            assertThat(DocumentArtifactCommandScope.authorityFor("ab_document_artifact",artifact(),"create")).isEqualTo(DUTY);
            return null;
        });
    }
    @Test void rejectsADefinitionForAnotherRootBeforeTheHandler() {
        var bad=new HashMap<>(definition);bad.put("dataset","foreign:snapshot");
        when(context.getExecConfig()).thenReturn(Map.of("handlerParams",Map.of("documentDefinition",bad)));
        assertThatThrownBy(()->execute(()->{throw new AssertionError("must not execute");})).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test void protectedFileCaptureRequiresTheOriginalWritableTransaction() {
        var protectedDefinition=new HashMap<>(definition);
        protectedDefinition.put("template","standard_v1");protectedDefinition.put("artifactRelation","formal_documents");
        protectedDefinition.put("publicFields",Map.of(ROOT,List.of("code")));
        when(context.getExecConfig()).thenReturn(Map.of("handlerParams",Map.of("documentDefinition",protectedDefinition)));
        execute(()->{
            assertThatThrownBy(()->DocumentArtifactCommandScope.captureFile(ROOT,PID,"formal_documents","quote.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            return null;
        });
    }
    @Test void legacyAndOrdinaryFilesDoNotAcquireDocumentProvenance() {
        assertThat(DocumentArtifactCommandScope.captureFile(ROOT,PID,"formal_documents","quote.pdf","application/pdf")).isNull();
        execute(()->{
            assertThat(DocumentArtifactCommandScope.captureFile(ROOT,PID,"formal_documents","quote.pdf","application/pdf")).isNull();
            return null;
        });
    }

    @Test void protectedFilesShareOneSnapshotAndCannotEscapeTheAdmittedRootOrFormat() {
        var definition=new HashMap<>(this.definition);
        definition.put("template","standard_v1");definition.put("artifactRelation","formal_documents");
        definition.put("publicFields",Map.of(ROOT,List.of("code")));
        when(context.getExecConfig()).thenReturn(Map.of("handlerParams",Map.of("documentDefinition",definition)));
        var target=new CommandPipelineContext.BusinessTargetAuthorization(COMMAND,ROOT,PID,"read",100L,1L,5L);
        var fields=new com.auraboot.framework.meta.service.impl.CommandPublicOutputProjectionPlan.ModelFields(
                Set.of("code"),Set.of("code"),List.of(),List.of());
        var plan=new com.auraboot.framework.meta.service.impl.CommandPublicOutputProjectionPlan(target,null,Map.of(ROOT,fields));
        var reads=new java.util.concurrent.atomic.AtomicInteger();
        Object resource=new Object();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        org.springframework.transaction.support.TransactionSynchronizationManager.bindResource(resource,
                new org.springframework.jdbc.datasource.ConnectionHolder(mock(java.sql.Connection.class)));
        try {
            execute(()->{reads.incrementAndGet();return plan;},()->{
                assertThatThrownBy(()->DocumentArtifactCommandScope.captureFile("foreign",PID,"formal_documents","a.pdf","application/pdf"))
                        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
                assertThatThrownBy(()->DocumentArtifactCommandScope.captureFile(ROOT,PID,"other","a.pdf","application/pdf"))
                        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
                assertThatThrownBy(()->DocumentArtifactCommandScope.captureFile(ROOT,PID,"formal_documents","a.pdf","text/plain"))
                        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
                var first=DocumentArtifactCommandScope.captureFile(ROOT,PID,"formal_documents","a.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                var second=DocumentArtifactCommandScope.captureFile(ROOT,PID,"formal_documents","a.pdf","application/pdf");
                assertThat(DocumentArtifactCommandScope.outputPlan(context)).isSameAs(plan);
                assertThatThrownBy(()->DocumentArtifactCommandScope.outputPlan(mock(CommandPipelineContext.class)))
                        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
                assertThat(first.version()).isEqualTo(2);
                assertThat(first.document().format()).isEqualTo("xlsx");
                assertThat(second.document().format()).isEqualTo("pdf");
                assertThat(first.fingerprint()).isEqualTo(second.fingerprint());
                assertThat(reads.get()).isEqualTo(1);
                try(var machine=com.auraboot.framework.application.security.MachineDataAccessContext.begin(
                        new com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal(
                                100L,"machine","Machine",Set.of(),null,null,null,null))) {
                    assertThatThrownBy(()->DocumentArtifactCommandScope.outputPlan(context))
                            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
                }
                assertThat(DocumentArtifactCommandScope.outputPlan(context)).isSameAs(plan);
                assertThatThrownBy(()->DocumentArtifactCommandScope.captureFile(ROOT,PID,"formal_documents","again.pdf","application/pdf"))
                        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
                return null;
            });
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.unbindResource(resource);
            org.springframework.transaction.support.TransactionSynchronizationManager.clear();
        }
    }

}
