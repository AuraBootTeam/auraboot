package com.auraboot.framework.plugin;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.constant.DataType;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.*;
import com.auraboot.framework.meta.entity.payload.*;
import com.auraboot.framework.meta.mapper.*;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.meta.service.impl.pipeline.*;
import com.auraboot.framework.permission.service.PermissionFacade;
import com.auraboot.framework.plugin.extension.*;
import com.auraboot.framework.plugin.pf4j.BusinessCommandDataAccessorFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;

/** Real native persistence and transaction rollback; the root witness is a host fixture, not a product command journey. */
@SpringBootTest(classes={TestApplication.class,BusinessCommandResourcesPostgresIT.ObserverFixture.class}, properties="spring.data.redis.host=")
@Transactional(propagation=Propagation.NEVER)
class BusinessCommandResourcesPostgresIT extends BaseIntegrationTest {
    @Autowired BusinessCommandDataAccessorFactory factory;
    @Autowired DynamicDataService data;
    @Autowired SchemaManagementService schema;
    @Autowired MetaModelService metadata;
    @Autowired MetaModelMapper models;
    @Autowired MetaFieldMapper fields;
    @Autowired MetaModelFieldBindingMapper bindings;
    @Autowired PermissionFacade permissions;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.auraboot.framework.meta.service.impl.CommandPublicOutputProjectionService publicProjection;
    @Autowired com.auraboot.framework.meta.service.impl.CommandResultProjectionService resultProjection;
    @Autowired FieldMaskService masks;
    @Autowired com.auraboot.framework.permission.service.BusinessCommandRecordAuthorization recordAuthorization;
    @Autowired org.springframework.context.ApplicationContext relationContext;
    @Autowired com.auraboot.framework.permission.service.UserPermissionService relationPermissions;
    @Autowired com.auraboot.framework.file.dao.mapper.FileMapper sourceFiles;
    @Autowired com.auraboot.framework.file.dao.mapper.FileRelationMapper sourceRelations;
    @Autowired com.auraboot.framework.infrastructure.storage.StorageProvider sourceStorage;
    final List<com.auraboot.framework.file.entity.FileEntity> ownedSourceFiles = new ArrayList<>();
    final List<Long> maskIds = new ArrayList<>();
    final List<Model> ownedModels = new ArrayList<>();
    final List<Long> ownedFields = new ArrayList<>();
    final Map<String,String> physicalTables = new HashMap<>();
    String rootModel, lineModel, ledgerModel, rootLabel, parent, qty, cost, ledgerParent, ledgerQty;
    String rootPid, linePid, foreignLinePid;
    long rootVersion;
    Owner owner;
    static final AtomicReference<Runnable> nestedEffect = new AtomicReference<>();
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class ObserverFixture {
        @org.springframework.context.annotation.Bean
        ModelMutationObserver businessResourceNestedObserver() {
            return (model,pid,operation)->{var effect=nestedEffect.getAndSet(null);if(effect!=null)effect.run();};
        }
    }

    @BeforeEach void fixtures() {
        String configured=System.getenv("TEST_DATABASE_URL");
        assertThat(configured).as("Explicit isolated PostgreSQL test target").startsWith("jdbc:postgresql://");
        String configuredDatabase=java.net.URI.create(configured.substring(5)).getPath().substring(1);
        assertThat(jdbc.queryForObject("SELECT current_database()",String.class)).isEqualTo(configuredDatabase);
        String suffix=Long.toUnsignedString(System.nanoTime());
        rootModel="br_root_"+suffix;lineModel="br_line_"+suffix;ledgerModel="br_ledger_"+suffix;
        rootLabel="br_label_"+suffix;parent="br_parent_"+suffix;qty="br_qty_"+suffix;
        cost="br_cost_"+suffix;ledgerParent="br_origin_"+suffix;ledgerQty="br_amount_"+suffix;
        model(rootModel,List.of(rootLabel));model(lineModel,List.of(parent,qty,cost));model(ledgerModel,List.of(ledgerParent,ledgerQty));
        var root=seed(rootModel,Map.of(rootLabel,"Draft"));rootPid=(String)root.get("pid");rootVersion=((Number)root.get("row_version")).longValue();
        linePid=(String)seed(lineModel,Map.of(parent,rootPid,qty,"2",cost,"INTERNAL-COST")).get("pid");
        foreignLinePid=(String)seed(lineModel,Map.of(parent,UniqueIdGenerator.generate(),qty,"7",cost,"FOREIGN-COST")).get("pid");
        owner=new Owner();
        assertThat(permissions.canAction(getTestTenantMember().getId(),lineModel,"update")).isFalse();
        assertThat(permissions.canAction(getTestTenantMember().getId(),ledgerModel,"create")).isFalse();
    }
    @AfterEach void cleanup() {
        nestedEffect.set(null);
        for (var file : ownedSourceFiles) {
            jdbc.update("DELETE FROM ab_file_relation WHERE tenant_id=? AND file_id=?",getTestTenant().getId(),file.getId().toString());
            jdbc.update("DELETE FROM ab_file WHERE tenant_id=? AND id=?",getTestTenant().getId(),file.getId());
            sourceStorage.delete(file.getFileName());
        }
        maskIds.forEach(masks::deleteConfig);
        for (var model:ownedModels) {
            assertThat(model.getCode()).matches("br_(root|line|ledger)_[0-9]+");
            String table=physicalTables.get(model.getCode());
            if(table!=null)jdbc.execute("DROP TABLE IF EXISTS "+table);
            jdbc.update("DELETE FROM ab_meta_model_field_binding WHERE tenant_id=? AND model_id=?",getTestTenant().getId(),model.getId());
            jdbc.update("DELETE FROM ab_meta_model WHERE tenant_id=? AND id=?",getTestTenant().getId(),model.getId());
        }
        for (Long id:ownedFields) jdbc.update("DELETE FROM ab_meta_field WHERE tenant_id=? AND id=?",getTestTenant().getId(),id);
        metadata.clearAllCache();
    }
    void model(String code,List<String> codes) {
        var model=new Model();model.setPid(UniqueIdGenerator.generate());model.setTenantId(getTestTenant().getId());
        model.setCode(code);model.setVersion(1);model.setIsCurrent(true);model.setStatus(Status.PUBLISHED.getCode());
        model.setCreatedAt(Instant.now());model.setUpdatedAt(Instant.now());model.setDeletedFlag(false);
        var extension=new ExtensionBean();extension.setExtension(new HashMap<>(Map.of("modelType","entity","softDelete",true,"displayName","Business resource fixture")));
        model.setExtension(extension);models.insert(model);ownedModels.add(model);
        for(String fieldCode:codes) {
            var field=new Field();field.setPid(UniqueIdGenerator.generate());field.setTenantId(getTestTenant().getId());
            field.setCode(fieldCode);field.setDataType(DataType.STRING.getCode());field.setVersion(1);field.setIsCurrent(true);
            field.setStatus(Status.PUBLISHED.getCode());field.setCreatedAt(Instant.now());field.setUpdatedAt(Instant.now());
            field.setDeletedFlag(false);field.setFeature(new FieldFeatureBean());fields.insert(field);ownedFields.add(field.getId());
            var binding=new ModelFieldBinding();binding.setTenantId(getTestTenant().getId());binding.setModelId(model.getId());
            binding.setFieldId(field.getId());binding.setFieldOrder(codes.indexOf(fieldCode));bindings.insert(binding);
        }
        var result=schema.createTableByModel(code);assertThat(result.isSuccess()).as(result.getErrorMessage()).isTrue();
        String table=metadata.getModelDefinition(code).orElseThrow().getTableName();
        assertThat(table).matches("mt_br_(root|line|ledger)_[0-9]+");physicalTables.put(code,table);
    }
    Map<String,Object> seed(String model,Map<String,Object> input) {
        return MetaContext.runWithCommandPermitPlan("ALL",null,model,null,()->data.create(model,new HashMap<>(input)));
    }
    CommandPipelineContext context() {
        var command=new CommandDefinition();command.setCode("fixture:receipt_confirm");command.setModelCode(rootModel);
        var request=new CommandExecuteRequest();request.setTargetRecordId(rootPid);
        var context = CommandPipelineContext.builder().commandCode(command.getCode()).command(command).request(request)
                .tenantId(getTestTenant().getId()).userId(getTestUser().getId()).targetRecordReadable(true).targetRecordVersion(rootVersion)
                .execConfig(Map.of("type","custom","permissions",List.of("fixture.receipt.confirm"),"handlerParams",Map.of("targetRecordAuthorization","business","targetRecordAction","update","dslPersistence",false)))
                .authorizationVerdict(CommandAuthorizationVerdict.authorized("fixture.receipt.confirm"))
                .businessTargetAuthorization(new CommandPipelineContext.BusinessTargetAuthorization(command.getCode(),rootModel,rootPid,"update",
                        getTestTenant().getId(),getTestUser().getId(),getTestTenantMember().getId()))
                .permitPlan(new CommandPermitPlan(CommandPermitPlan.Decision.PERMIT,null,null,rootPid,CommandPermitPlan.ScopeGrade.TARGET,rootVersion)).build();
        if (owner.independentRead) {
            var canonical=MetaContext.runWithCommandPermitScope("ALL",()->data.getByIdForAuthorization(rootModel,rootPid));
            var decision=recordAuthorization.resolve(getTestTenantMember().getId(),List.of("fixture.receipt.confirm"),rootModel,canonical);
            assertThat(decision.result().granted()).as("Real root grant remains admitted").isTrue();
            context.setBusinessGrantDecision(decision);
        }
        if (owner.publicOutput) {
            context.setBusinessGrantDecision(new com.auraboot.framework.permission.engine.model.BusinessGrantDecision(
                    com.auraboot.framework.permission.engine.model.PermissionResult.allow(List.of()),
                    getTestTenant().getId(), getTestUser().getId(), getTestTenantMember().getId(), rootModel, rootPid, List.of()));
            context.setPublicOutputProjection(publicProjection.prepare(context, owner));
        }
        if(owner.jsonResult) {
            context.setBusinessGrantDecision(new com.auraboot.framework.permission.engine.model.BusinessGrantDecision(
                    com.auraboot.framework.permission.engine.model.PermissionResult.allow(List.of()),
                    getTestTenant().getId(),getTestUser().getId(),getTestTenantMember().getId(),rootModel,rootPid,
                    List.of(new com.auraboot.framework.permission.engine.model.BusinessGrantDecision.Grant(1L,2L,"fixture.receipt.confirm",
                            new com.auraboot.framework.permission.engine.model.BusinessGrantDecision.Fields(false,Set.of(rootLabel),Set.of(rootLabel))))));
            var rootFacts=MetaContext.runWithCommandPermitScope("ALL",()->data.getById(rootModel,rootPid));
            context.setBusinessResultProjection(resultProjection.prepare(context,owner).bindRootDocuments(rootFacts));
        }
        return context;
    }
    <T> T command(Function<BusinessCommandDataAccessorFactory.Scope,T> operation) {
        var template = new TransactionTemplate(transactions);
        if (owner.paged || owner.publicOutput || owner.jsonResult || owner.independentRead) template.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return template.execute(tx->MetaContext.runWithCommandPermitPlan("TARGET",rootVersion,rootModel,rootPid,()->
                MetaContext.runWithAuthorizedCommandCode("fixture:receipt_confirm",()->
                    MetaContext.runWithCommandAuthority("fixture.receipt.confirm",()->{
                        try(var scope=factory.open(context(),owner)) {return operation.apply(scope);}
                    }))));
    }
    String storedQty() {return jdbc.queryForObject("SELECT "+qty+" FROM "+physicalTables.get(lineModel)+" WHERE tenant_id=? AND pid=?",String.class,getTestTenant().getId(),linePid);}
    long ledgerCount() {return jdbc.queryForObject("SELECT COUNT(*) FROM "+physicalTables.get(ledgerModel)+" WHERE tenant_id=?",Long.class,getTestTenant().getId());}

    String storedRootDocument() {
        return jdbc.queryForObject("SELECT "+rootLabel+" FROM "+physicalTables.get(rootModel)+" WHERE tenant_id=? AND pid=?",
                String.class,getTestTenant().getId(),rootPid);
    }
    void jsonSourceFixture() {
        owner.jsonResult=true;
        jdbc.update("UPDATE "+physicalTables.get(rootModel)+" SET "+rootLabel+"=? WHERE tenant_id=? AND pid=?",
                "{\"dryRun\":{\"quality\":{\"passed\":false,\"count\":0},\"privateSibling\":\"PRIVATE\"}}",getTestTenant().getId(),rootPid);
    }

    @Test void structuredPublicResultUsesTheActualPostWriteRootWithoutModelReadGrant() {
        jsonSourceFixture();
        assertThat(permissions.canAction(getTestTenantMember().getId(),rootModel,"read")).isFalse();
        var transaction=new TransactionTemplate(transactions);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var output=transaction.execute(tx -> MetaContext.runWithCommandPermitPlan("TARGET",rootVersion,rootModel,rootPid,() ->
                MetaContext.runWithAuthorizedCommandCode("fixture:receipt_confirm",() -> {
                    var ctx=context();Map<String,Object> internal;
                    try(var scope=factory.open(ctx,owner)) {
                        scope.accessor().update(rootModel,rootPid,Map.of(rootLabel,"{\"dryRun\":{\"quality\":{\"passed\":true,\"count\":3},\"privateSibling\":\"PRIVATE\"}}"));
                        internal=scope.projectResult(Map.of("quality",Map.of("passed",true,"count",3)));
                    }
                    return resultProjection.project(ctx,internal);
                })));
        assertThat(output).containsExactly(Map.entry("quality",Map.of("passed",true,"count",3)));
        assertThat(storedRootDocument()).contains("\"passed\":true");
        assertThat(output.toString()).doesNotContain("PRIVATE");
        assertThat(permissions.canAction(getTestTenantMember().getId(),rootModel,"read")).isFalse();
    }

    @Test void repeatedRootWritesAdvanceOnlyInsideTheLockedInvocationAndRollBackTogether() {
        jsonSourceFixture();
        String first="{\"dryRun\":{\"quality\":{\"passed\":false,\"count\":1}}}";
        String last="{\"dryRun\":{\"quality\":{\"passed\":true,\"count\":2}}}";
        var output=command(scope -> {
            scope.accessor().update(rootModel,rootPid,Map.of(rootLabel,first));
            scope.accessor().update(rootModel,rootPid,Map.of(rootLabel,last));
            return scope.projectResult(Map.of("quality",Map.of("passed",true,"count",2)));
        });
        assertThat(output).containsEntry("quality",Map.of("passed",true,"count",2));
        assertThat(storedRootDocument()).isEqualTo(last);
        long storedVersion=jdbc.queryForObject("SELECT row_version FROM "+physicalTables.get(rootModel)+" WHERE pid=?",Long.class,rootPid);
        assertThat(storedVersion).isEqualTo(rootVersion+2);
        rootVersion=storedVersion;
        assertThatThrownBy(() -> command(scope -> {
            scope.accessor().update(rootModel,rootPid,Map.of(rootLabel,first));
            scope.accessor().update(rootModel,rootPid,Map.of(rootLabel,last));
            scope.accessor().delete(lineModel,linePid);
            return null;
        })).isInstanceOf(DataAccessorException.class).hasMessageContaining("PERMISSION_DENIED");
        assertThat(storedRootDocument()).isEqualTo(last);
        assertThat(jdbc.queryForObject("SELECT row_version FROM "+physicalTables.get(rootModel)+" WHERE pid=?",Long.class,rootPid)).isEqualTo(storedVersion);
    }

    @Test void initialNullJsonSourceRequiresAnActualPostWriteDocumentAndRollsBackForgedOutput() {
        jsonSourceFixture();
        jdbc.update("UPDATE "+physicalTables.get(rootModel)+" SET "+rootLabel+"=NULL WHERE pid=?",rootPid);
        var output=command(scope -> {
            scope.accessor().update(rootModel,rootPid,Map.of(rootLabel,"{\"dryRun\":{\"quality\":{\"passed\":true}}}"));
            return scope.projectResult(Map.of("quality",Map.of("passed",true)));
        });
        assertThat(output).containsEntry("quality",Map.of("passed",true));
        assertThat(storedRootDocument()).contains("\"passed\":true");
        jdbc.update("UPDATE "+physicalTables.get(rootModel)+" SET "+rootLabel+"=NULL WHERE pid=?",rootPid);
        rootVersion=jdbc.queryForObject("SELECT row_version FROM "+physicalTables.get(rootModel)+" WHERE pid=?",Long.class,rootPid);
        assertThatThrownBy(() -> command(scope -> {
            scope.accessor().create(ledgerModel,Map.of(ledgerParent,rootPid,ledgerQty,"1"));
            return scope.projectResult(Map.of("quality",Map.of("passed",true)));
        })).isInstanceOf(DataAccessorException.class).hasMessageContaining("PERMISSION_DENIED");
        assertThat(storedRootDocument()).isNull();assertThat(ledgerCount()).isZero();
    }

    @Test void forgedStructuredResultPoisonsAndRollsBackActualEarlierRootAndChildWrites() {
        jsonSourceFixture();String before=storedRootDocument();
        assertThatThrownBy(() -> command(scope -> {
            scope.accessor().update(lineModel,linePid,Map.of(qty,"3"));
            scope.accessor().update(rootModel,rootPid,Map.of(rootLabel,"{\"dryRun\":{\"quality\":{\"passed\":true}}}"));
            assertThatThrownBy(() -> scope.projectResult(Map.of("quality",Map.of("passed",true,"cost",99))))
                    .isInstanceOf(DataAccessorException.class);
            return null;
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(storedRootDocument()).isEqualTo(before);assertThat(storedQty()).isEqualTo("2");
        command(scope -> {
            scope.accessor().update(lineModel,linePid,Map.of(qty,"3"));
            assertThat(scope.projectResult(Map.of("quality",Map.of("passed",false,"count",0))))
                    .containsExactly(Map.entry("quality",Map.of("passed",false,"count",0)));
            return null;
        });
        assertThat(storedQty()).isEqualTo("3");assertThat(storedRootDocument()).isEqualTo(before);
    }

    @Test void currentDatabaseSourceRejectsPreviouslyValidStructuredCachedData() {
        jsonSourceFixture();
        var transaction=new TransactionTemplate(transactions);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var old=Map.<String,Object>of("quality",Map.of("passed",false,"count",0));
        var initial=transaction.execute(tx -> resultProjection.project(context(),old));
        assertThat(initial).isEqualTo(old);
        jdbc.update("UPDATE "+physicalTables.get(rootModel)+" SET "+rootLabel+"=? WHERE tenant_id=? AND pid=?",
                "{\"dryRun\":{\"quality\":{\"passed\":true,\"count\":1}}}",getTestTenant().getId(),rootPid);
        assertThatThrownBy(() -> transaction.execute(tx -> resultProjection.project(context(),old)))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(storedRootDocument()).contains("\"passed\":true");
    }

    @Test void newlyConfiguredRealSourceMaskRemovesTheWholeStructuredCachedValue() {
        jsonSourceFixture();
        var transaction=new TransactionTemplate(transactions);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var old=Map.<String,Object>of("quality",Map.of("passed",false,"count",0));
        var initial=transaction.execute(tx -> resultProjection.project(context(),old));assertThat(initial).isEqualTo(old);
        var config=new FieldMaskConfig();config.setModelCode(rootModel);config.setFieldCode(rootLabel);config.setEnabled(true);
        config.setApplyToDetail(true);config.setMaskType("FULL");config.setReplacementChar("*");masks.saveConfig(config);maskIds.add(config.getId());
        var hidden=transaction.execute(tx -> resultProjection.project(context(),old));assertThat(hidden).isEmpty();
        assertThat(storedRootDocument()).contains("\"passed\":false");
        masks.deleteConfig(config.getId());maskIds.remove(config.getId());
        var restored=transaction.execute(tx -> resultProjection.project(context(),old));assertThat(restored).isEqualTo(old);
    }

    @Test void nativeRelatedUpdateAndAppendCommitWithoutGenericModelGrants() {
        command(scope->{
            var line=scope.accessor().getById(lineModel,linePid);assertThat(line).containsEntry(qty,"2").doesNotContainKey(cost);
            var query=scope.accessor().query(lineModel,Map.of(parent,rootPid));assertThat(query).hasSize(1);assertThat(query.getFirst()).doesNotContainKey(cost);
            scope.accessor().update(lineModel,linePid,Map.of(qty,"3"));
            scope.accessor().create(ledgerModel,Map.of(ledgerParent,rootPid,ledgerQty,"1"));return null;
        });
        assertThat(storedQty()).isEqualTo("3");assertThat(ledgerCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT created_by FROM "+physicalTables.get(ledgerModel)+" WHERE tenant_id=?",Long.class,getTestTenant().getId())).isEqualTo(getTestUser().getId());
        assertThat(permissions.canAction(getTestTenantMember().getId(),lineModel,"update")).isFalse();
    }
    @Test void caughtAuthorizationFailureRollsBackActualEarlierUpdateAndAppend() {
        assertThatThrownBy(()->command(scope->{
            scope.accessor().update(lineModel,linePid,Map.of(qty,"3"));
            scope.accessor().create(ledgerModel,Map.of(ledgerParent,rootPid,ledgerQty,"1"));
            assertThatThrownBy(()->scope.accessor().update(lineModel,linePid,Map.of(cost,"EXFILTRATED"))).isInstanceOf(DataAccessorException.class);
            return null;
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(storedQty()).isEqualTo("2");assertThat(ledgerCount()).isZero();
    }
    @Test void boundedInReadReturnsOnlyApprovedChildrenWithoutGenericModelReadGrant() {
        command(scope->{
            var rows=scope.accessor().queryIn(lineModel,parent,List.of(rootPid,rootPid));
            assertThat(rows).hasSize(1);assertThat(rows.getFirst()).containsEntry("pid",linePid).doesNotContainKey(cost);
            assertThat(rows.getFirst()).doesNotContainValue("FOREIGN-COST");return null;
        });
        assertThat(permissions.canAction(getTestTenantMember().getId(),lineModel,"read")).isFalse();
    }
    @Test void unapprovedInCandidatePoisonsAndRollsBackActualEarlierBusinessWrites() {
        assertThatThrownBy(()->command(scope->{
            scope.accessor().update(lineModel,linePid,Map.of(qty,"3"));
            scope.accessor().create(ledgerModel,Map.of(ledgerParent,rootPid,ledgerQty,"1"));
            assertThatThrownBy(()->scope.accessor().queryIn(lineModel,parent,List.of(rootPid,"other-root")))
                    .isInstanceOf(DataAccessorException.class);
            return null;
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(storedQty()).isEqualTo("2");assertThat(ledgerCount()).isZero();
    }
    @Test void anotherRootRecordIsDeniedUsingRealStoredRelationships() {
        assertThatThrownBy(()->command(scope->{scope.accessor().getById(lineModel,foreignLinePid);return null;})).isInstanceOf(DataAccessorException.class);
        assertThat(storedQty()).isEqualTo("2");assertThat(ledgerCount()).isZero();
    }
    @Test void concurrentRelationshipChangeCannotUseAnEarlierOwnerAllow() {
        owner.onUpdate=()->{
            var writer=new TransactionTemplate(transactions);writer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            writer.executeWithoutResult(tx->jdbc.update("UPDATE "+physicalTables.get(lineModel)+" SET "+parent+"=?,row_version=row_version+1 WHERE tenant_id=? AND pid=?",
                    UniqueIdGenerator.generate(),getTestTenant().getId(),linePid));
        };
        assertThatThrownBy(()->command(scope->{scope.accessor().update(lineModel,linePid,Map.of(qty,"3"));return null;}))
                .isInstanceOf(com.auraboot.framework.meta.exception.RecordVersionConflictException.class);
        assertThat(storedQty()).isEqualTo("2");assertThat(ledgerCount()).isZero();
    }
    @Test void rootChangeAfterBoundaryDecisionPreventsResourceExecution() {
        jdbc.update("UPDATE "+physicalTables.get(rootModel)+" SET row_version=row_version+1 WHERE tenant_id=? AND pid=?",getTestTenant().getId(),rootPid);
        assertThatThrownBy(()->command(scope->{scope.accessor().update(lineModel,linePid,Map.of(qty,"3"));return null;}))
                .isInstanceOf(com.auraboot.framework.meta.exception.RecordVersionConflictException.class);
        assertThat(storedQty()).isEqualTo("2");
    }
    @Test void capturedAccessorCannotRunInALaterTransaction() {
        var captured=new AtomicReference<DataAccessor>();command(scope->{captured.set(scope.accessor());return null;});
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(tx->captured.get().update(lineModel,linePid,Map.of(qty,"3"))))
                .isInstanceOf(DataAccessorException.class);
        assertThat(storedQty()).isEqualTo("2");assertThat(ledgerCount()).isZero();
    }

    @Test void nestedServiceWriteCannotBorrowAnApprovedNativeUpdateEvenWhenObserverCatchesIt() {
        nestedEffect.set(()->{
            try {data.update(lineModel,foreignLinePid,new HashMap<>(Map.of(qty,"999")));}
            catch(org.springframework.security.access.AccessDeniedException expected) { /* CATCH: prove outer rollback survives swallowed denial. */ }
        });
        assertThatThrownBy(()->command(scope->{
            assertThatThrownBy(()->scope.accessor().update(lineModel,linePid,Map.of(qty,"3"))).isInstanceOf(DataAccessorException.class);
            return null;
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(nestedEffect.get()).isNull();assertThat(storedQty()).isEqualTo("2");
        assertThat(jdbc.queryForObject("SELECT "+qty+" FROM "+physicalTables.get(lineModel)+" WHERE pid=?",String.class,foreignLinePid)).isEqualTo("7");
    }
    @Test void nestedServiceReadCannotUseTheApprovedUpdateToReadAnotherRecord() {
        var leaked=new AtomicReference<Object>();
        nestedEffect.set(()->{
            try {leaked.set(data.getById(lineModel,foreignLinePid));}
            catch(org.springframework.security.access.AccessDeniedException expected) { /* CATCH: prove original transaction is poisoned. */ }
        });
        assertThatThrownBy(()->command(scope->{
            assertThatThrownBy(()->scope.accessor().update(lineModel,linePid,Map.of(qty,"3"))).isInstanceOf(DataAccessorException.class);
            return null;
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(nestedEffect.get()).isNull();assertThat(leaked.get()).isNull();assertThat(storedQty()).isEqualTo("2");
    }

    @Test void nestedCreateCannotBorrowTheModelPermitOfAnApprovedAppend() {
        nestedEffect.set(()->{
            try {data.create(ledgerModel,new HashMap<>(Map.of(ledgerParent,"FOREIGN-ROOT",ledgerQty,"999")));}
            catch(org.springframework.security.access.AccessDeniedException expected) { /* CATCH: the original append must still roll back. */ }
        });
        Throwable failure=catchThrowable(()->command(scope->{
            try {scope.accessor().create(ledgerModel,Map.of(ledgerParent,rootPid,ledgerQty,"1"));}
            catch(DataAccessorException expected) { /* CATCH: prove the transaction cannot silently commit. */ }
            return null;
        }));
        assertThat(nestedEffect.get()).isNull();assertThat(ledgerCount()).isZero();
        assertThat(failure).isInstanceOf(UnexpectedRollbackException.class);
    }

    @Test void moreThanOneThousandRowsPageWithoutModelGrantsOrSecretFields() {
        owner.paged = true;
        jdbc.update("INSERT INTO " + physicalTables.get(lineModel)
                + " (id,pid,tenant_id," + parent + "," + qty + "," + cost + ",row_version,deleted_flag,created_by,created_at,updated_at)"
                + " SELECT ?+g,'paged-'||lpad(g::text,6,'0'),?,?,?,'PAGED-SECRET',0,false,?,now(),now() FROM generate_series(1,1004) g",
                com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),getTestTenant().getId(),rootPid,"5",getTestUser().getId());
        command(scope -> {
            var identities = new HashSet<String>();
            for(int number=1;number<=3;number++) {
                var page=scope.accessor().queryPage(lineModel,new DataPageQuery(Map.of(parent,rootPid),Map.of(),List.of(),number,500));
                assertThat(page.total()).isEqualTo(1005);
                assertThat(page.records()).hasSize(number<3 ? 500 : 5).allSatisfy(row -> {
                    assertThat(row).containsEntry(parent,rootPid).doesNotContainKey(cost);
                    assertThat(identities.add((String)row.get("pid"))).isTrue();
                });
            }
            assertThat(identities).hasSize(1005);return null;
        });
        assertThat(permissions.canAction(getTestTenantMember().getId(),lineModel,"read")).isFalse();
    }
    @Test void aDeniedRowBeyondTheRequestedPageRollsBackEarlierWrites() {
        owner.paged=true;
        var forbidden=seed(lineModel,Map.of(parent,rootPid,qty,"9",cost,"FORBIDDEN-SECRET"));
        owner.deniedLine=(String)forbidden.get("pid");
        assertThatThrownBy(()->command(scope->{
            scope.accessor().update(lineModel,linePid,Map.of(qty,"3"));
            scope.accessor().queryPage(lineModel,new DataPageQuery(Map.of(parent,rootPid),Map.of(),List.of(),1,1));return null;
        })).isInstanceOf(DataAccessorException.class);
        assertThat(storedQty()).isEqualTo("2");assertThat(ledgerCount()).isZero();
    }
    @Test void writesInvalidateAnAlreadyReadPagedSnapshotAndRollBack() {
        owner.paged=true;
        assertThatThrownBy(()->command(scope->{
            var query=new DataPageQuery(Map.of(parent,rootPid),Map.of(),List.of(),1,1);
            scope.accessor().queryPage(lineModel,query);
            scope.accessor().update(lineModel,linePid,Map.of(qty,"3"));
            scope.accessor().queryPage(lineModel,query);return null;
        })).isInstanceOf(DataAccessorException.class);
        assertThat(storedQty()).isEqualTo("2");
    }

    void exportOnlyMask() {
        var config = new FieldMaskConfig(); config.setModelCode(lineModel); config.setFieldCode(qty);
        config.setEnabled(true); config.setApplyToDetail(false); config.setApplyToList(false); config.setApplyToExport(true);
        config.setMaskType("FULL"); config.setReplacementChar("*"); masks.saveConfig(config); maskIds.add(config.getId());
        assertThat(masks.getEffectiveConfigs(lineModel, getTestUser().getId(), "detail")).isEmpty();
        assertThat(masks.getEffectiveConfigs(lineModel, getTestUser().getId(), "export")).hasSize(1);
    }

    @Test void publicExportProjectionMasksRealSourceWithoutChangingDerivedBusinessWrites() {
        owner.publicOutput = true; exportOnlyMask();
        command(scope -> {
            var internal = scope.accessor().getById(lineModel, linePid);
            assertThat(internal).containsEntry(qty, "2");
            var output = scope.accessor().projectForPublicOutput(lineModel, List.of(linePid));
            assertThat(output.get(linePid)).containsOnlyKeys(qty).containsEntry(qty, "*");
            assertThat(internal).containsEntry(qty, "2");
            scope.accessor().update(lineModel, linePid, Map.of(qty, "3"));
            return null;
        });
        assertThat(storedQty()).isEqualTo("3");
        assertThat(permissions.canAction(getTestTenantMember().getId(), lineModel, "read")).isFalse();
        assertThat(permissions.canAction(getTestTenantMember().getId(), lineModel, "update")).isFalse();
    }

    @Test void publicViewsOfMoreThanOneThousandRowsRemainCompleteAndMaskedWithoutModelGrants() {
        owner.publicOutput = true; owner.paged = true; exportOnlyMask();
        jdbc.update("INSERT INTO " + physicalTables.get(lineModel)
                + " (id,pid,tenant_id," + parent + "," + qty + "," + cost + ",row_version,deleted_flag,created_by,created_at,updated_at)"
                + " SELECT ?+g,'output-'||lpad(g::text,6,'0'),?,?,?,'OUTPUT-SECRET',0,false,?,now(),now() FROM generate_series(1,1004) g",
                com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), getTestTenant().getId(), rootPid, "5", getTestUser().getId());
        command(scope -> {
            var identities = new HashSet<String>();
            for (int number = 1; number <= 3; number++) {
                var page = scope.accessor().queryPage(lineModel, new DataPageQuery(Map.of(parent, rootPid), Map.of(), List.of(), number, 500));
                assertThat(page.total()).isEqualTo(1005);
                var output = scope.accessor().projectForPublicOutput(lineModel, page.records().stream().map(row -> (String) row.get("pid")).toList());
                assertThat(output).hasSize(number < 3 ? 500 : 5);
                output.forEach((pid, row) -> {
                    assertThat(identities.add(pid)).isTrue();
                    assertThat(row).containsOnlyKeys(qty).containsEntry(qty, "*");
                });
                assertThat(page.records()).allSatisfy(row -> assertThat(row.get(qty)).isIn("2", "5"));
            }
            assertThat(identities).hasSize(1005); return null;
        });
        assertThat(permissions.canAction(getTestTenantMember().getId(), lineModel, "read")).isFalse();
    }

    @Test void projectingAnInvalidatedSourceRollsBackActualWritesEvenWhenTheHandlerCatchesDenial() {
        owner.publicOutput = true;
        assertThatThrownBy(() -> command(scope -> {
            scope.accessor().getById(lineModel, linePid);
            scope.accessor().update(lineModel, linePid, Map.of(qty, "3"));
            assertThatThrownBy(() -> scope.accessor().projectForPublicOutput(lineModel, List.of(linePid))).isInstanceOf(DataAccessorException.class);
            return null;
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(storedQty()).isEqualTo("2");
    }

    boolean deleted(String model,String pid) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT deleted_flag FROM "+physicalTables.get(model)
                +" WHERE tenant_id=? AND pid=?",Boolean.class,getTestTenant().getId(),pid));
    }
    @Test void approvedChildDeleteUsesRealNativePersistenceWithoutModelDeleteGrant() {
        owner.allowDelete=true; owner.paged=true;
        assertThat(permissions.canAction(getTestTenantMember().getId(),lineModel,"delete")).isFalse();
        command(scope->{scope.accessor().delete(lineModel,linePid);return null;});
        assertThat(deleted(lineModel,linePid)).isTrue(); assertThat(deleted(rootModel,rootPid)).isFalse();
        assertThat(deleted(lineModel,foreignLinePid)).isFalse();
    }
    @Test void childDeleteThenCaughtForeignDeletePoisonsAndRollsBackOriginalTransaction() {
        owner.allowDelete=true; owner.paged=true;
        assertThatThrownBy(()->command(scope->{
            scope.accessor().delete(lineModel,linePid);
            assertThatThrownBy(()->scope.accessor().delete(lineModel,foreignLinePid)).isInstanceOf(DataAccessorException.class);
            return null;
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(deleted(lineModel,linePid)).isFalse(); assertThat(deleted(lineModel,foreignLinePid)).isFalse();
    }
    @Test void nestedDeleteFromMutationObserverCannotReuseTheExactChildLoan() {
        owner.allowDelete=true; owner.paged=true;
        nestedEffect.set(()->data.delete(lineModel,foreignLinePid));
        assertThatThrownBy(()->command(scope->{scope.accessor().delete(lineModel,linePid);return null;}))
                .isInstanceOf(RuntimeException.class);
        assertThat(nestedEffect.get()).isNull();
        assertThat(deleted(lineModel,linePid)).isFalse(); assertThat(deleted(lineModel,foreignLinePid)).isFalse();
    }

    private com.auraboot.framework.file.entity.FileEntity sourceAttachment() {
        owner.sourceRead=true;
        var field=fields.selectById(ownedFields.getFirst());field.setDataType("file");fields.updateById(field);metadata.clearAllCache();
        var file=new com.auraboot.framework.file.entity.FileEntity();file.setId(com.baomidou.mybatisplus.core.toolkit.IdWorker.getId());
        file.setPid(UniqueIdGenerator.generate());file.setTenantId(getTestTenant().getId());
        file.setFileName("business-source-"+file.getPid()+".xlsx");file.setOriginalName("original-bom.xlsx");file.setFileSize(3L);
        file.setStatus("success");file.setStorageType(sourceStorage.type());file.setDeletedFlag(false);file.setRetentionLocked(false);
        file.setCreatedBy(getTestUser().getId());file.setCreatedTime(Instant.now());file.setUpdatedTime(Instant.now());file.setUploadTime(Instant.now());
        sourceStorage.upload(file.getFileName(),new java.io.ByteArrayInputStream(new byte[]{1,2,3}),3,"application/octet-stream");
        assertThat(sourceFiles.insert(file)).isEqualTo(1);ownedSourceFiles.add(file);
        var relation=new com.auraboot.framework.file.entity.FileRelationEntity().setId(UUID.randomUUID().toString())
                .setTenantId(getTestTenant().getId()).setFileId(file.getId().toString()).setEntityType(rootModel).setEntityId(rootPid)
                .setFieldName(rootLabel).setSortOrder(0).setDeletedFlag(false);
        assertThat(sourceRelations.insert(relation)).isEqualTo(1);
        jdbc.update("UPDATE "+physicalTables.get(rootModel)+" SET "+rootLabel+"=? WHERE tenant_id=? AND pid=?",file.getPid(),getTestTenant().getId(),rootPid);
        return file;
    }
    @Test void exactOriginalSourceAttachmentReadsRealStorageWithoutNativeModelReadPermission() {
        sourceAttachment();
        assertThat(permissions.canAction(getTestTenantMember().getId(),rootModel,"read")).isFalse();
        byte[] bytes=command(scope -> scope.accessor().readBusinessSourceFile(rootLabel));
        assertThat(bytes).containsExactly(1,2,3);
    }
    @Test void anotherRootRelationDoesNotAuthorizeTheSameTenantFile() {
        var file=sourceAttachment();
        jdbc.update("UPDATE ab_file_relation SET entity_id=? WHERE tenant_id=? AND file_id=?",UniqueIdGenerator.generate(),getTestTenant().getId(),file.getId().toString());
        assertThatThrownBy(() -> command(scope -> scope.accessor().readBusinessSourceFile(rootLabel))).isInstanceOf(DataAccessorException.class);
    }
    @Test void generatedArtifactCannotBeReinterpretedAsAnOriginalSourceAttachment() {
        var file=sourceAttachment();
        jdbc.update("UPDATE ab_file SET artifact_authorization='{}' WHERE tenant_id=? AND id=?",getTestTenant().getId(),file.getId());
        assertThatThrownBy(() -> command(scope -> scope.accessor().readBusinessSourceFile(rootLabel))).isInstanceOf(DataAccessorException.class);
    }
    @Test void missingSourceRelationPoisonsAndRollsBackEarlierApprovedInternalWritesEvenIfCaught() {
        var file=sourceAttachment();
        jdbc.update("UPDATE ab_file_relation SET deleted_flag=true WHERE tenant_id=? AND file_id=?",getTestTenant().getId(),file.getId().toString());
        assertThatThrownBy(() -> command(scope -> {
            scope.accessor().create(ledgerModel,Map.of(ledgerParent,rootPid,ledgerQty,"1"));
            assertThatThrownBy(() -> scope.accessor().readBusinessSourceFile(rootLabel)).isInstanceOf(DataAccessorException.class);
            return null;
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(ledgerCount()).isZero();
    }
    @Test void realStorageLengthMustMatchTheLockedSourceMetadata() {
        var file=sourceAttachment();
        sourceStorage.delete(file.getFileName());
        sourceStorage.upload(file.getFileName(),new java.io.ByteArrayInputStream(new byte[]{1,2}),2,"application/octet-stream");
        assertThatThrownBy(() -> command(scope -> scope.accessor().readBusinessSourceFile(rootLabel))).isInstanceOf(DataAccessorException.class);
    }

    @Test void fileRelationControllerOwnsTheCoherentReadAndWriteTransaction() {
        var file=sourceAttachment();
        jdbc.update("DELETE FROM ab_file_relation WHERE tenant_id=? AND file_id=?",getTestTenant().getId(),file.getId().toString());
        long role=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        var permissionIds=new ArrayList<Long>();
        jdbc.update("INSERT INTO ab_role(id,pid,tenant_id,code,name,type,status,deleted_flag,default_data_scope_type) VALUES(?,?,?,?,?,'custom','active',FALSE,'all')",
                role,UniqueIdGenerator.generate(),getTestTenant().getId(),rootModel+"_files","Owned file relation fixture");
        jdbc.update("INSERT INTO ab_user_role(id,pid,tenant_id,member_id,role_id,status,deleted_flag) VALUES(?,?,?,?,?,'active',FALSE)",
                com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),UniqueIdGenerator.generate(),getTestTenant().getId(),getTestTenantMember().getId(),role);
        try {
            for(String action:List.of("read","update")) {
                Long permission=jdbc.queryForObject("INSERT INTO ab_permission(pid,tenant_id,code,name,resource_type,resource_code,action,source,status,deleted_flag) VALUES(?,?,?,?,'model',?,?,'test','active',FALSE) RETURNING id",
                        Long.class,UniqueIdGenerator.generate(),getTestTenant().getId(),"model."+rootModel+"."+action,"File relation "+action,rootModel,action);
                permissionIds.add(permission);
                jdbc.update("INSERT INTO ab_role_permission(id,pid,tenant_id,role_id,permission_id,grant_type,status,deleted_flag) VALUES(?,?,?,?,?,'grant','active',FALSE)",
                        com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),UniqueIdGenerator.generate(),getTestTenant().getId(),role,permission);
            }
            relationPermissions.evictPermissionDefinitions(getTestTenant().getId());
            relationPermissions.evictUserPermissions(getTestTenant().getId(),getTestUser().getId());
            assertThat(permissions.canAction(getTestTenantMember().getId(),rootModel,"read")).isTrue();
            assertThat(permissions.canAction(getTestTenantMember().getId(),rootModel,"update")).isTrue();
            var request=new com.auraboot.framework.file.dto.FileRelationRequestDTO();request.setEntityType(rootModel);
            request.setEntityId(rootPid);request.setFieldName(rootLabel);request.setFileIds(new String[]{file.getPid()});
            // Call the actual Spring controller proxy. MVC permission interception is covered by the BOM HTTP journey.
            var response=relationContext.getBean(com.auraboot.framework.file.controller.FileUploadController.class).createFileRelation(request,getTestUser().getId());
            assertThat(response.getData()).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_file_relation WHERE tenant_id=? AND entity_type=? AND entity_id=? AND field_name=? AND file_id=? AND deleted_flag=FALSE",
                    Long.class,getTestTenant().getId(),rootModel,rootPid,rootLabel,file.getId().toString())).isEqualTo(1);
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var files=relationContext.getBean(com.auraboot.framework.file.service.FileService.class);
            assertThat(files.removeFileRelation(rootModel,rootPid,rootLabel)).isTrue();
            assertThat(files.appendFileRelation(request,getTestUser().getId())).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_file_relation WHERE tenant_id=? AND entity_id=? AND file_id=? AND deleted_flag=FALSE",
                    Long.class,getTestTenant().getId(),rootPid,file.getId().toString())).isEqualTo(1);
            jdbc.update("UPDATE ab_role_permission SET status='inactive' WHERE tenant_id=? AND role_id=? AND permission_id=?",
                    getTestTenant().getId(),role,permissionIds.get(1));
            relationPermissions.evictRoleUsers(getTestTenant().getId(),role);
            relationPermissions.evictUserPermissions(getTestTenant().getId(),getTestUser().getId());
            assertThatThrownBy(()->relationContext.getBean(com.auraboot.framework.file.controller.FileUploadController.class)
                    .createFileRelation(request,getTestUser().getId()))
                    .isInstanceOfAny(org.springframework.security.access.AccessDeniedException.class,com.auraboot.framework.exception.PermissionDeniedException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_file_relation WHERE tenant_id=? AND entity_id=? AND file_id=? AND deleted_flag=FALSE",
                    Long.class,getTestTenant().getId(),rootPid,file.getId().toString())).isEqualTo(1);
            String fieldsJson=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(Map.of("modelCode",rootModel,"readFields",List.of(rootLabel),"writeFields",List.of())).toString();
            jdbc.update("UPDATE ab_role_permission SET status='active',field_scope=?::jsonb WHERE tenant_id=? AND role_id=? AND permission_id=?",
                    fieldsJson,getTestTenant().getId(),role,permissionIds.get(1));
            relationPermissions.evictRoleUsers(getTestTenant().getId(),role);relationPermissions.evictUserPermissions(getTestTenant().getId(),getTestUser().getId());
            assertThatThrownBy(()->relationContext.getBean(com.auraboot.framework.file.controller.FileUploadController.class)
                    .createFileRelation(request,getTestUser().getId())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
                    .hasMessageContaining("FIELD_WRITE_DENIED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_file_relation WHERE tenant_id=? AND entity_id=? AND file_id=? AND deleted_flag=FALSE",
                    Long.class,getTestTenant().getId(),rootPid,file.getId().toString())).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM ab_role_permission WHERE tenant_id=? AND role_id=?",getTestTenant().getId(),role);
            jdbc.update("DELETE FROM ab_user_role WHERE tenant_id=? AND role_id=?",getTestTenant().getId(),role);
            jdbc.update("DELETE FROM ab_role WHERE tenant_id=? AND id=?",getTestTenant().getId(),role);
            for(Long id:permissionIds)jdbc.update("DELETE FROM ab_permission WHERE tenant_id=? AND id=?",getTestTenant().getId(),id);
            relationPermissions.evictPermissionDefinitions(getTestTenant().getId());
            relationPermissions.evictUserPermissions(getTestTenant().getId(),getTestUser().getId());
        }
    }

    @Test void independentReferenceReadRetainsItsOwnScopeWithoutASecondFunctionGrant() throws Exception {
        owner.independentRead=true;
        long tenant=getTestTenant().getId(),role=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        Long permission=null;
        jdbc.update("INSERT INTO ab_role(id,pid,tenant_id,code,name,type,status,deleted_flag,default_data_scope_type) VALUES(?,?,?,?,?,'custom','active',FALSE,'none')",
                role,UniqueIdGenerator.generate(),tenant,rootModel+"_reference","Owned reference scope fixture");
        jdbc.update("INSERT INTO ab_user_role(id,pid,tenant_id,member_id,role_id,status,deleted_flag) VALUES(?,?,?,?,?,'active',FALSE)",
                com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),UniqueIdGenerator.generate(),tenant,getTestTenantMember().getId(),role);
        try {
            String extension=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                    "domainModels",Map.of(rootModel,List.of("read","update")),
                    "commandEffects",Map.of(lineModel,List.of("read"))));
            permission=jdbc.queryForObject("INSERT INTO ab_permission(pid,tenant_id,code,name,resource_type,resource_code,action,source,status,deleted_flag,extension) VALUES(?,?,?,?,'operation',?,'write','test','active',FALSE,?::jsonb) RETURNING id",
                    Long.class,UniqueIdGenerator.generate(),tenant,"fixture.receipt.confirm","Root domain write",rootModel,extension);
            jdbc.update("INSERT INTO ab_role_permission(id,pid,tenant_id,role_id,permission_id,grant_type,status,deleted_flag) VALUES(?,?,?,?,?,'grant','active',FALSE)",
                    com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),UniqueIdGenerator.generate(),tenant,role,permission);
            for(String model:List.of(rootModel,lineModel))
                jdbc.update("INSERT INTO ab_role_data_scope(pid,tenant_id,role_id,resource_code,action_code,scope_type,merge_strategy) VALUES(?,?,?,?,'read',?,'MAX')",
                        UniqueIdGenerator.generate(),tenant,role,model,model.equals(rootModel)?"all":"none");
            relationPermissions.evictPermissionDefinitions(tenant);relationPermissions.evictUserPermissions(tenant,getTestUser().getId());
            assertThat(permissions.canAction(getTestTenantMember().getId(),lineModel,"read"))
                    .as("No standalone child model-read permission is granted").isFalse();
            assertThatThrownBy(()->command(scope->scope.accessor().getById(lineModel,linePid)))
                    .as("A root relationship must not override independent object NONE").isInstanceOf(DataAccessorException.class);
            jdbc.update("UPDATE ab_role_data_scope SET scope_type='self' WHERE tenant_id=? AND role_id=? AND resource_code=?",tenant,role,lineModel);
            assertThat(this.<Map<String,Object>>command(scope->scope.accessor().getById(lineModel,linePid))).containsEntry(qty,"2");
            assertThatThrownBy(()->command(scope->scope.accessor().update(lineModel,linePid,Map.of(qty,"9"))))
                    .as("An independent read contract cannot mutate that object").isInstanceOf(DataAccessorException.class);
            jdbc.update("UPDATE "+physicalTables.get(lineModel)+" SET created_by=? WHERE tenant_id=? AND pid=?",getTestUser().getId()+1,tenant,linePid);
            assertThatThrownBy(()->command(scope->scope.accessor().getById(lineModel,linePid)))
                    .as("SELF still rejects another creator even under the same root").isInstanceOf(DataAccessorException.class);
            assertThatThrownBy(()->command(scope->scope.accessor().query(lineModel,Map.of(parent,rootPid))))
                    .as("A bounded query cannot bypass the independent row policy").isInstanceOf(DataAccessorException.class);
            long before=ledgerCount();
            assertThatThrownBy(()->command(scope->{
                scope.accessor().create(ledgerModel,Map.of(ledgerParent,rootPid,ledgerQty,"3"));
                try {scope.accessor().getById(lineModel,linePid);} catch(DataAccessorException denied) { /* CATCH: prove poison survives a swallowed denial. */ }
                return true;
            })).as("Denied source poisons earlier effects in the same transaction").isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
            assertThat(ledgerCount()).isEqualTo(before);
            jdbc.update("UPDATE ab_role_data_scope SET scope_type='all' WHERE tenant_id=? AND role_id=? AND resource_code=?",tenant,role,lineModel);
            assertThat(this.<Map<String,Object>>command(scope->scope.accessor().getById(lineModel,linePid))).containsEntry(qty,"2");
            String fieldScope=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("version",2,"models",Map.of(
                    rootModel,Map.of("unrestricted",true),lineModel,Map.of("readFields",List.of(parent),"writeFields",List.of()))));
            jdbc.update("UPDATE ab_role_permission SET field_scope=?::jsonb WHERE tenant_id=? AND role_id=? AND permission_id=?",fieldScope,tenant,role,permission);
            relationPermissions.evictRoleUsers(tenant,role);relationPermissions.evictUserPermissions(tenant,getTestUser().getId());
            assertThat(this.<Map<String,Object>>command(scope->scope.accessor().getById(lineModel,linePid)))
                    .containsEntry(parent,rootPid).doesNotContainKey(qty).doesNotContainKey(cost);
            assertThat(storedQty()).isEqualTo("2");
        } finally {
            jdbc.update("DELETE FROM ab_role_data_scope WHERE tenant_id=? AND role_id=?",tenant,role);
            jdbc.update("DELETE FROM ab_role_permission WHERE tenant_id=? AND role_id=?",tenant,role);
            jdbc.update("DELETE FROM ab_user_role WHERE tenant_id=? AND role_id=?",tenant,role);
            jdbc.update("DELETE FROM ab_role WHERE tenant_id=? AND id=?",tenant,role);
            if(permission!=null)jdbc.update("DELETE FROM ab_permission WHERE tenant_id=? AND id=?",tenant,permission);
            relationPermissions.evictPermissionDefinitions(tenant);relationPermissions.evictUserPermissions(tenant,getTestUser().getId());
        }
    }

    class Owner implements CommandHandlerExtension,BusinessResourceAuthorization {
        Runnable onUpdate;
        boolean paged;
        boolean independentRead;
        public Set<String> independentBusinessReadModels(){return independentRead ? Set.of(lineModel) : Set.of();}
        boolean publicOutput;
        boolean allowDelete;
        boolean jsonResult;
        boolean sourceRead;
        public Map<String,Integer> businessSourceFileFields(){return sourceRead ? Map.of(rootLabel,16) : Map.of();}
        int pagedLimit = 2000;
        String deniedLine;
        public ReadIsolation resourceReadIsolation(){return paged || publicOutput || jsonResult || independentRead ? ReadIsolation.REPEATABLE_READ : null;}
        public Map<String,Set<String>> publicBusinessResourceFields(){return publicOutput ? Map.of(lineModel, Set.of(qty)) : Map.of();}
        public Map<String,Integer> pagedBusinessResourceRowLimits(){return paged ? Map.of(lineModel,pagedLimit) : Map.of();}
        public String getCommandType(){return "fixture:receipt_confirm";}
        public Object execute(CommandContext context){throw new AssertionError("Not a product handler fixture");}
        public Boundary businessBoundary(){return new Boundary("fixture:receipt_confirm",rootModel,"update",Set.of("fixture.receipt.confirm"));}
        public Set<String> businessResourceModels(){return Set.of(rootModel,lineModel,ledgerModel);}
        public Set<String> businessResultFields(){return jsonResult ? Set.of("quality") : Set.of("success");}
        public Map<String,BusinessResultField> businessResultProjection(){return jsonResult ? Map.of("quality",BusinessResultField.rootJsonPath(rootLabel,"dryRun","quality")) : Map.of();}
        public Set<String> businessDeletableResourceModels(){return allowDelete ? Set.of(lineModel) : Set.of();}
        Set<String> read(String... fields){var set=new HashSet<>(Set.of("pid","tenant_id","row_version"));set.addAll(List.of(fields));return set;}
        public Grant authorizeResource(TargetAuthorizationContext root,Request request){
            if(jsonResult && request.action()==Action.UPDATE && rootModel.equals(request.modelCode())
                    && rootPid.equals(request.recordId()) && request.changes().keySet().equals(Set.of(rootLabel)))
                return new Grant(read(rootLabel),Set.of(rootLabel),1);
            if(request.action()==Action.READ && rootModel.equals(request.modelCode()) && rootPid.equals(request.recordId()))return new Grant(read(rootLabel),Set.of(),1);
            if(request.action()==Action.QUERY && lineModel.equals(request.modelCode()) && Map.of(parent,rootPid).equals(request.filters()))return new Grant(read(parent,qty),Set.of(),paged ? 500 : 10);
            if(request.action()==Action.READ && request.recordId()!=null && request.recordId().equals(deniedLine)) return null;
            if(lineModel.equals(request.modelCode()) && rootPid.equals(request.record().get(parent))){
                if(request.action()==Action.READ)return new Grant(read(parent,qty),Set.of(),1);
                if(request.action()==Action.DELETE && allowDelete)return new Grant(read(parent,qty),Set.of(),1);
                if(request.action()==Action.UPDATE){if(onUpdate!=null){var task=onUpdate;onUpdate=null;task.run();}return new Grant(read(parent,qty),Set.of(qty),1);}
            }
            if(request.action()==Action.CREATE && ledgerModel.equals(request.modelCode()) && rootPid.equals(request.changes().get(ledgerParent)))
                return new Grant(read(ledgerParent,ledgerQty),Set.of(ledgerParent,ledgerQty),1);
            return null;
        }
    }
}
