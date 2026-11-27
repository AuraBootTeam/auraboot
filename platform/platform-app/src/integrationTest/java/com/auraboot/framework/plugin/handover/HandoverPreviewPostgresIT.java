package com.auraboot.framework.plugin.handover;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.mapper.HandoverPreviewMapper;
import com.auraboot.framework.plugin.mapper.HandoverPreviewItemMapper;
import com.auraboot.framework.plugin.mapper.HandoverInventoryMapper;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.lock.LocalDistributedLock;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.test.util.OwnedTestDatabase;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.JsonNode;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual owned PG, MyBatis JSONB mapping and service transactions; no resource move or uninstall. */
class HandoverPreviewPostgresIT {
    JdbcTemplate jdbc;HandoverPreviewService service;HandoverPreviewMapper batches;HandoverPreviewItemMapper itemMapper;
    HandoverInventoryMapper inventory;UserPermissionService permissions;DataSourceTransactionManager manager;
    ResourceHandoverContracts contracts;ResourceHandoverContract contract;long tenant;String sourcePid;
    final Map<String,String> packages=new HashMap<>();
    @BeforeEach void setup() throws Exception {
        String url=System.getenv("TEST_DATABASE_URL");
        OwnedTestDatabase.require(url,OwnedTestDatabase.AURABOOT_SLOT_PATTERN,"Use the existing owned migrated slot");
        var ds=new DriverManagerDataSource(url,System.getenv("TEST_DATABASE_USERNAME"),System.getenv("TEST_DATABASE_PASSWORD"));
        jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);
        var configuration=new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(configuration);
        factory.setTransactionFactory(new SpringManagedTransactionFactory());var sessions=factory.getObject();
        for(var c:List.of(HandoverPreviewMapper.class,HandoverPreviewItemMapper.class,HandoverInventoryMapper.class))sessions.getConfiguration().addMapper(c);
        var sql=new SqlSessionTemplate(sessions);batches=sql.getMapper(HandoverPreviewMapper.class);itemMapper=sql.getMapper(HandoverPreviewItemMapper.class);
        inventory=sql.getMapper(HandoverInventoryMapper.class);permissions=mock(UserPermissionService.class);
        when(permissions.hasPermission(anyLong(),anyString())).thenReturn(true);
        contracts=new ResourceHandoverContracts(new Resource[]{new ClassPathResource("handover-preview-contract.json")});
        contract=contracts.require("pcba-base-manufacturing-preview");
        service=service(itemMapper);tenant=ThreadLocalRandom.current().nextLong(100_000_000L,900_000_000L);
        MetaContext.setContext(tenant,33L,"handover-reviewer","Handover reviewer");
        new TransactionTemplate(manager).executeWithoutResult(s->fixture());
    }
    HandoverPreviewService service(HandoverPreviewItemMapper mapper) {
        return new HandoverPreviewService(contracts,inventory,batches,mapper,permissions,new LocalDistributedLock(),manager);
    }
    @AfterEach void clear(){MetaContext.clear();}
    void fixture() {
        jdbc.update("INSERT INTO ab_tenant(id,pid,name) VALUES (?,?,?)",tenant,UlidGenerator.generate(),"Handover preview fixture "+tenant);
        for(var p:List.of(contract.source(),contract.targets().get(0),contract.targets().get(1),contract.references().getFirst().provider(),new ResourceHandoverContract.PackageVersion("com.auraboot.product-catalog","1.0.0"))) {
            String pid=UlidGenerator.generate();packages.put(p.pluginId(),pid);
            jdbc.update("INSERT INTO ab_plugin(pid,tenant_id,plugin_id,namespace,version,status,manifest,deleted_flag) VALUES (?,?,?,?,?,'enabled','{}'::jsonb,false)",pid,tenant,p.pluginId(),"preview_"+packages.size(),p.version());
        }
        sourcePid=packages.get(contract.source().pluginId());
        long model=jdbc.queryForObject("INSERT INTO ab_meta_model(pid,tenant_id,code,version,source_type,table_name,plugin_pid,deleted_flag) VALUES (?,?,'prod_product',1,'physical','mt_prod_product',?,false) RETURNING id",Long.class,UlidGenerator.generate(),tenant,packages.get("com.auraboot.product-catalog"));
        Map<String,Long> dictIds=new HashMap<>();Map<String,String> dictPids=new HashMap<>();
        for(String code:List.of("pe_msl_level","pe_trace_level","inv_lot_policy")) {
            String pid=UlidGenerator.generate();String owner=code.equals("inv_lot_policy")?packages.get("com.auraboot.inventory"):sourcePid;
            long id=jdbc.queryForObject("INSERT INTO ab_dict(pid,tenant_id,code,name,dict_type,plugin_pid,deleted_flag,extension) VALUES (?,?,?,?,'static',?,false,'{\"precise\":7.5000,\"nullable\":null}'::jsonb) RETURNING id",Long.class,pid,tenant,code,code,owner);
            dictIds.put(code,id);dictPids.put(code,pid);
            jdbc.update("INSERT INTO ab_dict_item(pid,tenant_id,dict_id,value,label) VALUES (?,?,?,'custom','Preserved custom label')",UlidGenerator.generate(),tenant,id);
        }
        jdbc.update("INSERT INTO ab_plugin_resource(pid,tenant_id,plugin_pid,resource_type,resource_code,resource_pid,ownership_type,user_modified) VALUES (?,?,?,'dict','inv_lot_policy',?,'plugin_owned',false)",UlidGenerator.generate(),tenant,packages.get("com.auraboot.inventory"),dictPids.get("inv_lot_policy"));
        Map<String,Long> fields=new HashMap<>();Map<String,String> fieldPids=new HashMap<>();
        for(var r:contract.resources()) if(r.type().equals("field")) {
            String pid=UlidGenerator.generate();
            long id=jdbc.queryForObject("INSERT INTO ab_meta_field(pid,tenant_id,code,version,data_type,plugin_pid,deleted_flag) VALUES (?,?,?,1,?,?,false) RETURNING id",Long.class,pid,tenant,r.code(),r.expectedDataType(),sourcePid);
            fields.put(r.code(),id);fieldPids.put(r.code(),pid);
            if(r.dictCode()!=null)jdbc.update("INSERT INTO ab_meta_field_dict_binding(pid,tenant_id,field_id,field_pid,field_code,dict_id,dict_code,deleted_flag) VALUES (?,?,?,?,?,?,?,false)",UlidGenerator.generate(),tenant,id,pid,r.code(),dictIds.get(r.dictCode()),r.dictCode());
        }
        for(var r:contract.resources()) {
            String pid=switch(r.type()) {
                case "field"->fieldPids.get(r.code());case "dict"->dictPids.get(r.code());
                default->{String value=UlidGenerator.generate();jdbc.update("INSERT INTO ab_meta_model_field_binding(pid,tenant_id,model_id,field_id,visible,editable,deleted_flag) VALUES (?,?,?,?,true,true,false)",value,tenant,model,fields.get(r.fieldCode()));yield value;}
            };
            jdbc.update("INSERT INTO ab_plugin_resource(pid,tenant_id,plugin_pid,resource_type,resource_code,resource_pid,ownership_type,user_modified,import_snapshot) VALUES (?,?,?,?,?,?,'plugin_owned',false,'{\"original\":true,\"nil\":null}'::jsonb)",UlidGenerator.generate(),tenant,sourcePid,r.type(),r.code(),pid);
        }
        jdbc.update("UPDATE ab_plugin_resource SET ownership_type='user_claimed',user_modified=true WHERE tenant_id=? AND resource_code='pe_msl_level'",tenant);
    }
    HandoverPreviewService.Request request(String key) {return new HandoverPreviewService.Request(contract.contractId(),HandoverJson.digest(contract),sourcePid,key);}
    String state() {
        var result=new TreeMap<String,String>();
        for(String table:List.of("ab_plugin","ab_plugin_resource","ab_meta_field","ab_meta_model","ab_meta_model_field_binding","ab_meta_field_dict_binding","ab_dict","ab_dict_item"))
            result.put(table,jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.id),'[]')::text FROM "+table+" r WHERE tenant_id=?",String.class,tenant));
        return result.toString();
    }
    long receipts(){return jdbc.queryForObject("SELECT count(*) FROM ab_plugin_handover_batch WHERE tenant_id=?",Long.class,tenant);}
    @Test void previewsMultipleTargetsPersistsExactJsonbAndDoesNotChangeResources() throws Exception {
        String before=state();var result=service.preview(request("happy"));
        assertThat(result.status()).isEqualTo("PREVIEWED");assertThat(result.executionAvailable()).isFalse();
        assertThat(result.plannedMoves()).isEqualTo(12);assertThat(result.items()).hasSize(13);
        assertThat(result.items().stream().filter(i->i.action().equals("MOVE_OWNER")&&i.targetPluginPid().equals(packages.get("com.auraboot.pcba-industry")))) .hasSize(11);
        assertThat(result.items().stream().filter(i->i.action().equals("VERIFY_REFERENCE"))).hasSize(1);
        assertThat(state()).isEqualTo(before);assertThat(service.get(result.batchPid())).isEqualTo(result);
        assertThat(service.getByKey("happy")).isEqualTo(result);
        String fixtureOutput=System.getenv("HANDOVER_HTTP_FIXTURE");
        if(fixtureOutput!=null) java.nio.file.Files.writeString(java.nio.file.Path.of(fixtureOutput),"{\"tenantId\":"+tenant+",\"sourcePluginPid\":\""+sourcePid+"\"}");
        var stored=batches.byPid(tenant,result.batchPid());
        assertThat(stored.getSnapshot().path("items").get(10).isObject()).isTrue();
        assertThat(stored.getSnapshot().toString()).contains("7.5000","Preserved custom label","user_claimed","original");
        var wire=HandoverJson.JSON.valueToTree(result);
        assertThat(wire.findValue("snapshot")).isNull();assertThat(wire.findValue("tenantId")).isNull();assertThat(wire.findValue("id")).isNull();
    }
    @Test void replayReturnsOriginalReceiptEvenAfterResourceEditsAndAnotherKeyCapturesNewInventory() {
        var first=service.preview(request("replay"));
        jdbc.update("UPDATE ab_dict_item SET label='Later user edit' WHERE tenant_id=?",tenant);
        assertThat(service.preview(request("replay"))).isEqualTo(first);assertThat(receipts()).isEqualTo(1);
        var next=service.preview(request("new-snapshot"));assertThat(next.previewDigest()).isNotEqualTo(first.previewDigest());
        var changed=new HandoverPreviewService.Request(contract.contractId(),HandoverJson.digest(contract),"other-source", "replay");
        assertThatThrownBy(()->service.preview(changed)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("Idempotency");
    }
    @Test void receiptFailureRollsBackEveryBatchAndItemWithoutMutatingSource() {
        String before=state();var failing=mock(HandoverPreviewItemMapper.class);
        doAnswer(i->{HandoverPreviewItem row=i.getArgument(0);if(row.getOrdinal()==4)throw new IllegalStateException("Injected item persistence failure");return itemMapper.insert(row);}).when(failing).insert(any(HandoverPreviewItem.class));
        assertThatThrownBy(()->service(failing).preview(request("fault"))).hasMessageContaining("Injected item");
        assertThat(receipts()).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_plugin_handover_item WHERE tenant_id=?",Long.class,tenant)).isZero();assertThat(state()).isEqualTo(before);
    }
    @Test void nullProtectionAndLiveOwnerMismatchFailClosedWithoutReceipts() {
        jdbc.update("UPDATE ab_plugin_resource SET user_modified=null WHERE tenant_id=? AND resource_code='pe_msl_level'",tenant);
        String before=state();assertThatThrownBy(()->service.preview(request("unknown"))).hasMessageContaining("protection");assertThat(receipts()).isZero();assertThat(state()).isEqualTo(before);
        jdbc.update("UPDATE ab_plugin_resource SET user_modified=true WHERE tenant_id=? AND resource_code='pe_msl_level'",tenant);
        jdbc.update("UPDATE ab_dict SET plugin_pid=? WHERE tenant_id=? AND code='pe_msl_level'",packages.get("com.auraboot.sales"),tenant);
        before=state();assertThatThrownBy(()->service.preview(request("owner"))).hasMessageContaining("owner");assertThat(receipts()).isZero();assertThat(state()).isEqualTo(before);
    }
    @Test void missingTrackingAdditionalVersionAndMissingReceiverAreRejected() {
        jdbc.update("DELETE FROM ab_plugin_resource WHERE tenant_id=? AND resource_code='pe_msl_level'",tenant);
        assertThatThrownBy(()->service.preview(request("missing"))).hasMessageContaining("inventory");assertThat(receipts()).isZero();
        jdbc.update("UPDATE ab_plugin SET status='disabled' WHERE tenant_id=? AND plugin_id='com.auraboot.sales'",tenant);
        assertThatThrownBy(()->service.preview(request("receiver"))).hasMessageContaining("lifecycle");assertThat(receipts()).isZero();
    }
    @Test void wrongDictionaryReferenceAndOrphanOwnedVersionCannotBeIgnored() {
        jdbc.update("UPDATE ab_meta_field_dict_binding SET dict_code='other' WHERE tenant_id=? AND field_code='pe_prd_lot_policy'",tenant);
        assertThatThrownBy(()->service.preview(request("ref"))).hasMessageContaining("dictionary relationship");assertThat(receipts()).isZero();
        jdbc.update("UPDATE ab_meta_field_dict_binding SET dict_code='inv_lot_policy' WHERE tenant_id=? AND field_code='pe_prd_lot_policy'",tenant);
        jdbc.update("INSERT INTO ab_meta_field(pid,tenant_id,code,version,data_type,plugin_pid,deleted_flag) VALUES (?,?,?,2,'enum',?,false)",UlidGenerator.generate(),tenant,"pe_prod_msl_level",sourcePid);
        assertThatThrownBy(()->service.preview(request("version"))).hasMessageContaining("additional source-owned field");assertThat(receipts()).isZero();
    }
    @Test void permissionsTenantAndContractDigestAreRecheckedAndNoPrivateStateLeaks() {
        when(permissions.hasPermission(33L,"sys.plugin.update")).thenReturn(false);
        assertThatThrownBy(()->service.preview(request("denied"))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);assertThat(receipts()).isZero();
        when(permissions.hasPermission(33L,"sys.plugin.update")).thenReturn(true);
        var wrong=new HandoverPreviewService.Request(contract.contractId(),"0".repeat(64),sourcePid,"digest");
        assertThatThrownBy(()->service.preview(wrong)).hasMessageContaining("revision");assertThat(receipts()).isZero();
        var first=service.preview(request("tenant"));MetaContext.setContext(tenant+1,33L,"other","Other");
        assertThatThrownBy(()->service.get(first.batchPid())).hasMessageContaining("not found");
        assertThatThrownBy(()->service.preview(request("foreign-source"))).hasMessageContaining("Source installation");
    }
    @Test void concurrentIndependentAdmissionsResolveOneDurableIdempotentReceipt() throws Exception {
        var gate=new java.util.concurrent.CyclicBarrier(2);
        var actual=batches;
        var racing=mock(HandoverPreviewMapper.class,org.mockito.AdditionalAnswers.delegatesTo(actual));
        doAnswer(i->{var row=actual.byKey(i.getArgument(0),i.getArgument(1));if(row==null)gate.await(10,java.util.concurrent.TimeUnit.SECONDS);return row;})
                .when(racing).byKey(tenant,"parallel");
        var a=new HandoverPreviewService(contracts,inventory,racing,itemMapper,permissions,new LocalDistributedLock(),manager);
        var b=new HandoverPreviewService(contracts,inventory,racing,itemMapper,permissions,new LocalDistributedLock(),manager);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);String before=state();
        try {
            var tasks=List.of(a,b).stream().map(svc->pool.submit(()->{MetaContext.setContext(tenant,33L,"parallel","Parallel reviewer");try{return svc.preview(request("parallel"));}finally{MetaContext.clear();}})).toList();
            assertThat(tasks.get(0).get(20,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(tasks.get(1).get(20,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(receipts()).isEqualTo(1);assertThat(itemMapper.byBatch(tenant,tasks.get(0).get().batchPid())).hasSize(13);assertThat(state()).isEqualTo(before);
        } finally {pool.shutdownNow();}
    }
    @Test void expiredPreviewIsReturnedAsExpiredWithoutRefreshingItsReceipt() {
        var p=service.preview(request("expiry"));
        jdbc.update("UPDATE ab_plugin_handover_batch SET created_at=now()-interval '16 minutes',expires_at=now()-interval '1 minute' WHERE tenant_id=? AND pid=?",tenant,p.batchPid());
        assertThat(service.get(p.batchPid()).expired()).isTrue();assertThat(service.preview(request("expiry")).expired()).isTrue();assertThat(receipts()).isEqualTo(1);
    }
    @Test void externalReferenceProtectionCannotDefaultUnknownToFalse() {
        jdbc.update("UPDATE ab_plugin_resource SET user_modified=null WHERE tenant_id=? AND resource_code='inv_lot_policy'",tenant);
        String before=state();assertThatThrownBy(()->service.preview(request("reference-unknown"))).hasMessageContaining("protection");assertThat(receipts()).isZero();assertThat(state()).isEqualTo(before);
    }
    @Test void corruptOrPartialDurableReceiptIsNeverReturnedAsValidPreview() {
        var result=service.preview(request("corrupt"));String before=state();
        jdbc.update("UPDATE ab_plugin_handover_item SET snapshot='{}'::jsonb WHERE tenant_id=? AND batch_pid=? AND ordinal=0",tenant,result.batchPid());
        assertThatThrownBy(()->service.get(result.batchPid())).hasMessageContaining("integrity");assertThat(state()).isEqualTo(before);
    }
}
