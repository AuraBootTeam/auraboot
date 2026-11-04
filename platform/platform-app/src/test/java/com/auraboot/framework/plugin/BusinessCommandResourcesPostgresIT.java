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
        assertThat(jdbc.queryForObject("SELECT current_database()",String.class)).isEqualTo("auraboot_168");
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
        return CommandPipelineContext.builder().commandCode(command.getCode()).command(command).request(request)
                .tenantId(getTestTenant().getId()).userId(getTestUser().getId()).targetRecordReadable(true).targetRecordVersion(rootVersion)
                .execConfig(Map.of("type","custom","permissions",List.of("fixture.receipt.confirm"),"handlerParams",Map.of("targetRecordAuthorization","business","targetRecordAction","update","dslPersistence",false)))
                .authorizationVerdict(CommandAuthorizationVerdict.authorized("fixture.receipt.confirm"))
                .businessTargetAuthorization(new CommandPipelineContext.BusinessTargetAuthorization(command.getCode(),rootModel,rootPid,"update",
                        getTestTenant().getId(),getTestUser().getId(),getTestTenantMember().getId()))
                .permitPlan(new CommandPermitPlan(CommandPermitPlan.Decision.PERMIT,null,null,rootPid,CommandPermitPlan.ScopeGrade.TARGET,rootVersion)).build();
    }
    <T> T command(Function<BusinessCommandDataAccessorFactory.Scope,T> operation) {
        return new TransactionTemplate(transactions).execute(tx->MetaContext.runWithCommandPermitPlan("TARGET",rootVersion,rootModel,rootPid,()->
                MetaContext.runWithAuthorizedCommandCode("fixture:receipt_confirm",()->{
                    try(var scope=factory.open(context(),owner)) {return operation.apply(scope);}
                })));
    }
    String storedQty() {return jdbc.queryForObject("SELECT "+qty+" FROM "+physicalTables.get(lineModel)+" WHERE tenant_id=? AND pid=?",String.class,getTestTenant().getId(),linePid);}
    long ledgerCount() {return jdbc.queryForObject("SELECT COUNT(*) FROM "+physicalTables.get(ledgerModel)+" WHERE tenant_id=?",Long.class,getTestTenant().getId());}

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

    class Owner implements CommandHandlerExtension,BusinessResourceAuthorization {
        Runnable onUpdate;
        public String getCommandType(){return "fixture:receipt_confirm";}
        public Object execute(CommandContext context){throw new AssertionError("Not a product handler fixture");}
        public Boundary businessBoundary(){return new Boundary("fixture:receipt_confirm",rootModel,"update",Set.of("fixture.receipt.confirm"));}
        public Set<String> businessResourceModels(){return Set.of(rootModel,lineModel,ledgerModel);}
        public Set<String> businessResultFields(){return Set.of("success");}
        Set<String> read(String... fields){var set=new HashSet<>(Set.of("pid","tenant_id","row_version"));set.addAll(List.of(fields));return set;}
        public Grant authorizeResource(TargetAuthorizationContext root,Request request){
            if(request.action()==Action.READ && rootModel.equals(request.modelCode()) && rootPid.equals(request.recordId()))return new Grant(read(rootLabel),Set.of(),1);
            if(request.action()==Action.QUERY && lineModel.equals(request.modelCode()) && Map.of(parent,rootPid).equals(request.filters()))return new Grant(read(parent,qty),Set.of(),10);
            if(lineModel.equals(request.modelCode()) && rootPid.equals(request.record().get(parent))){
                if(request.action()==Action.READ)return new Grant(read(parent,qty),Set.of(),1);
                if(request.action()==Action.UPDATE){if(onUpdate!=null){var task=onUpdate;onUpdate=null;task.run();}return new Grant(read(parent,qty),Set.of(qty),1);}
            }
            if(request.action()==Action.CREATE && ledgerModel.equals(request.modelCode()) && rootPid.equals(request.changes().get(ledgerParent)))
                return new Grant(read(ledgerParent,ledgerQty),Set.of(ledgerParent,ledgerQty),1);
            return null;
        }
    }
}
