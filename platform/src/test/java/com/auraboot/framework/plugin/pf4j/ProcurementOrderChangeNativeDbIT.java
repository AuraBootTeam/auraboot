package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.*;
import com.auraboot.framework.plugin.extension.integration.InstalledIntegrationEvent;
import com.auraboot.framework.user.service.UserService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** Native PostgreSQL domain seam with synthetic approved snapshots; no public HTTP or real ERP change claim. */
@SpringBootTest(classes=TestApplication.class, properties={
    "spring.datasource.url=${TEST_DATABASE_URL}", "spring.data.redis.database=${TEST_REDIS_DATABASE}",
    "security.field-encryption.key=${PROCUREMENT_HOST_FIELD_ENCRYPTION_KEY}",
    "aura.plugins.dir=${PROCUREMENT_SOURCE_PLUGIN_DIR}",
    "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.slf4j.Slf4jImpl",
    "logging.level.com.auraboot.framework=WARN", "logging.level.org.mybatis=WARN", "logging.level.org.springframework=WARN", "logging.level.org.springframework.security=WARN",
    "spring.jpa.show-sql=false"})
@ActiveProfiles("integration-test")
@org.springframework.context.annotation.Import(ProcurementOrderChangeNativeDbIT.NoScheduling.class)
@EnabledIfEnvironmentVariable(named="PROCUREMENT_ORDER_CHANGE_NATIVE", matches="true")
class ProcurementOrderChangeNativeDbIT {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class NoScheduling {
        @org.springframework.context.annotation.Bean
        static org.springframework.beans.factory.config.BeanFactoryPostProcessor isolateScheduling() {
            return factory -> {
                var registry=(org.springframework.beans.factory.support.BeanDefinitionRegistry)factory;
                String processor=org.springframework.scheduling.config.TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME;
                if(registry.containsBeanDefinition(processor))registry.removeBeanDefinition(processor);
                Class<com.auraboot.framework.scheduler.service.SchedulerEngine> type=com.auraboot.framework.scheduler.service.SchedulerEngine.class;
                String[] names=factory.getBeanNamesForType(type,false,false);
                if(names.length!=1)throw new IllegalStateException("Exactly one scheduler required");
                var replacement=new org.springframework.beans.factory.support.RootBeanDefinition(type);
                replacement.setInstanceSupplier(()->org.mockito.Mockito.mock(type));
                registry.removeBeanDefinition(names[0]);registry.registerBeanDefinition(names[0],replacement);
            };
        }
    }
    @Autowired DynamicDataService data;
    @Autowired UserService users;
    @Autowired TenantMemberService members;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource source;
    @Autowired ObjectMapper json;
    DynamicDataAccessorImpl db;
    URLClassLoader plugin;
    long tenant,actorId;
    String actor,namespace,connection,product,sourcePid,supplier,order,linePid;
    Object binding,notices;
    static final String PROCESS="qo_procurement_order_notice_common:process";
    static final String SYNC="qo_procurement_task_common:sync_sources";
    @BeforeEach void prepare() throws Exception {
        assertEquals("13",System.getenv("TEST_REDIS_DATABASE"));
        try(var c=source.getConnection()){assertEquals("enterprise_22",c.getCatalog());}
        tenant=Long.parseLong(System.getenv("TEST_TENANT_ID"));
        namespace="chg_"+UUID.randomUUID().toString().replace("-","").substring(0,20);
        var user=users.signUp(namespace+"@example.invalid","native-change-fixture-123","Native change fixture",namespace);
        actor=user.getPid();actorId=user.getId();members.addMember(actorId,tenant,"active");
        MetaContext.setContext(tenant,actorId,actor,"Native change fixture");
        db=new DynamicDataAccessorImpl(data);
        plugin=new URLClassLoader(new URL[]{Path.of(System.getenv("PROCUREMENT_PLUGIN_JAR")).toRealPath().toUri().toURL()},getClass().getClassLoader());
        product=create(SYNC,"prod_product",Map.of("prod_code",namespace,"prod_name","Change fixture material","prod_type","raw_material","prod_unit","pcs","prod_status","active"));
        connection=create(SYNC,"customer_pcba_int_kingdee_connection",Map.of(
            "customer_pcba_kd_profile_name",namespace,"customer_pcba_kd_status","active","customer_pcba_kd_acct_id",namespace,
            "customer_pcba_kd_base_url","https://example.invalid/K3CLOUD","customer_pcba_kd_app_id","synthetic",
            "customer_pcba_kd_username","synthetic","customer_pcba_kd_app_secret_ref","cloud-config:"+namespace,"customer_pcba_kd_auth_type","app-secret"));
        sourcePid=create(SYNC,"pr_purchase_request",physical("source",Map.of("code",namespace,"product_pid",product,
            "requested_qty",140,"organization","ORG-FIXTURE","unit","PCS","state","eligible","origin","external_requisition","business_status","pending","collaboration_managed",true)));
        supplier=create("pr_supplier_common:sync_directory","pr_supplier_common",Map.of("pr_supplier_code",namespace,
            "pr_supplier_name","Change fixture supplier","pr_supplier_status","enabled","qo_pr_supplier_account_id",namespace));
        Object publisher=construct("ProcurementOrderService",new Class<?>[]{DataAccessor.class,Clock.class},db,Clock.systemUTC());
        assertEquals("published",tx(PROCESS,()->invoke(publisher,"publish",new Class<?>[]{Map.class,List.class},header(),initialLines())));
        order=rows("pr_purchase_order","qo_pr_order_account_id",namespace).getFirst().get("pid").toString();
        linePid=rows("pr_purchase_order_line","pr_pol_order_id",order).stream().filter(row->"800000001".equals(row.get("qo_pr_order_line_erp_entry_id"))).findFirst().orElseThrow().get("pid").toString();
        Class<?> b=type("ProcurementOrderNoticeService$Binding");
        binding=construct("ProcurementOrderNoticeService$Binding",new Class<?>[]{long.class,String.class,String.class,String.class},tenant,"simulation-"+namespace,connection,actor);
        notices=construct("ProcurementOrderNoticeService",new Class<?>[]{DataAccessor.class},db);
        assertNotNull(b);
    }
    @AfterEach void close() throws Exception {if(plugin!=null)plugin.close();MetaContext.clear();}
    // Explicit synthetic resolved permit; native writer protection remains active. Public authentication is a separate seam.
    <T>T scope(String writer,Supplier<T> work){return MetaContext.runWithCommandPermitPlan("ALL",actorId,null,null,()->MetaContext.runWithAuthorizedCommandCode(writer,work));}
    <T>T tx(String writer,Supplier<T> work){return new TransactionTemplate(transactions).execute(status->scope(writer,work));}
    String create(String writer,String model,Map<String,Object> values){return tx(writer,()->db.create(model,values).get("pid").toString());}
    List<Map<String,Object>> rows(String model,String field,Object value){return scope(PROCESS,()->db.queryPage(model,new DataPageQuery(Map.of(field,value),Map.of(),List.of(),1,20)).records());}
    Class<?> type(String name){try{return plugin.loadClass("com.auraboot.quote.engine.procurement."+name);}catch(ClassNotFoundException e){throw new IllegalStateException(e);}}
    Object construct(String name,Class<?>[] types,Object...args){try{var c=type(name).getDeclaredConstructor(types);c.setAccessible(true);return c.newInstance(args);}catch(ReflectiveOperationException e){throw unwrap(e);}}
    Object invoke(Object target,String method,Class<?>[] types,Object...args){try{var m=target.getClass().getDeclaredMethod(method,types);m.setAccessible(true);return m.invoke(target,args);}catch(ReflectiveOperationException e){throw unwrap(e);}}
    RuntimeException unwrap(ReflectiveOperationException e){Throwable c=e instanceof InvocationTargetException i?i.getCause():e;return c instanceof RuntimeException r?r:new IllegalStateException(c);}
    @SuppressWarnings("unchecked") Map<String,Object> physical(String entity,Map<String,Object> values){try{return (Map<String,Object>)type("ProcurementData").getMethod("fields",String.class,Map.class).invoke(null,entity,values);}catch(ReflectiveOperationException e){throw unwrap(e);}}
    List<Map<String,Object>> initialLines(){return List.of(candidate("140"));}
    Map<String,Object> header(){return Map.of("account_id",namespace,"erp_id","700000001","supplier_pid",supplier,"bill_no",namespace,
        "order_date","2026-10-20","business_status","approved","currency","CNY","exchange_rate","1","commercial_snapshot",Map.of("paymentConditionNumber","NET30"));}
    Map<String,Object> candidate(String quantity){var values=new LinkedHashMap<String,Object>();
        values.put("erp_entry_id","800000001");values.put("product_pid",product);values.put("source_pid",sourcePid);
        values.put("ordered_qty",quantity);values.put("submitted_qty",0);values.put("received_qty",0);values.put("unit","PCS");
        values.put("unit_price","1");values.put("gross_unit_price","1.13");values.put("tax_rate","0.13");values.put("amount",quantity);
        values.put("tax_amount",new BigDecimal(quantity).multiply(new BigDecimal("0.13")));values.put("gross_amount",new BigDecimal(quantity).multiply(new BigDecimal("1.13")));
        values.put("material_code",namespace);values.put("material_name","Change fixture material");values.put("specification","");values.put("delivery_date","2026-11-01");
        values.put("erp_received_qty",0);values.put("erp_stock_in_qty",0);return values;}
    @SuppressWarnings("unchecked") Map<String,Object> change(String name,String quantity){
        var body=Map.<String,Object>of("requestId",namespace+"-"+name,"externalSubject",Map.of("type","purchase-order","pid","700000001"),"formId","PUR_PurchaseOrder",
            "orderId","700000001","sourceVersion","12","changedAt","2026-10-20T03:00:00Z","operationId",namespace+"-operation","changeSource","purchase_order_reapproved");
        var event=new InstalledIntegrationEvent("external.kingdee-procurement-orders.order.changed.v1",12,Instant.parse("2026-10-20T03:00:01Z"),"purchase-order","700000001");
        var accepted=(Map<String,Object>)tx("qo_procurement_order_notice_common:accept",()->invoke(notices,"acceptChange",new Class<?>[]{binding.getClass(),Map.class,InstalledIntegrationEvent.class},binding,body,event));
        Object observed=construct("ProcurementOrderChangeReadContract$Observed",new Class<?>[]{long.class,Instant.class},12L,Instant.parse("2026-10-20T03:00:00Z"));
        Object read=construct("ProcurementOrderSyncFactory$ChangeRead",new Class<?>[]{observed.getClass(),Map.class,List.class,Map.class,boolean.class},observed,header(),List.of(candidate(quantity)),Map.of("organization","ORG-FIXTURE","lines",Map.of("800000001",Map.of("plans",List.of("synthetic-plan")))),true);
        Object service=construct("ProcurementOrderChangeService",new Class<?>[]{DataAccessor.class,binding.getClass()},db,binding);
        var result=(Map<String,Object>)tx(PROCESS,()->invoke(service,"process",new Class<?>[]{String.class,String.class,read.getClass()},accepted.get("recordPid"),order,read));
        assertEquals("completed",result.get("state"));return result;
    }
    void verify(String scenario,String expected,String quantity,String submitted,String received) throws Exception {
        var result=change(scenario,"210");var outcome=(Map<?,?>)result.get("result");
        assertEquals(expected,outcome.get("status"));assertEquals(!"updated".equals(expected),outcome.get("newShipmentBlocked"));
        var actual=scope(PROCESS,()->db.getById("pr_purchase_order_line",linePid));
        assertEquals(0,new BigDecimal(quantity).compareTo(new BigDecimal(actual.get("pr_pol_qty").toString())));
        assertEquals(0,new BigDecimal(submitted).compareTo(new BigDecimal(actual.get("qo_pr_order_line_submitted_qty").toString())));
        assertEquals(0,new BigDecimal(received).compareTo(new BigDecimal(actual.get("pr_pol_received_qty").toString())));
        var ledger=rows("qo_procurement_order_notice_common","qo_pr_order_notice_request_id",namespace+"-"+scenario).getFirst();
        assertEquals("completed",ledger.get("qo_pr_order_notice_state"));
        Object raw=ledger.get("qo_pr_order_notice_details_json");assertNotNull(raw);
        var audit=raw instanceof String text?json.readTree(text):json.valueToTree(raw);
        assertEquals(0,new BigDecimal("210").compareTo(new BigDecimal(audit.path("candidateLines").get(0).path("ordered_qty").asText())));
        var parent=scope(PROCESS,()->db.getById("pr_purchase_order",order));
        boolean pending=!"updated".equals(expected);
        assertEquals(pending,parent.get("qo_pr_order_change_pending"));
        if(pending){
            assertNull(outcome.get("appliedSourceVersion"));assertEquals(acceptedReason(scenario),((List<?>)outcome.get("reasonCodes")).getFirst());
            assertEquals(ledger.get("pid"),parent.get("qo_pr_order_pending_change_notice_pid"));
        }else{
            assertEquals("12",outcome.get("appliedSourceVersion"));assertEquals("12",parent.get("qo_pr_order_applied_source_version"));
            assertEquals(ledger.get("pid"),parent.get("qo_pr_order_applied_change_notice_pid"));
        }
        Path directory=Path.of(System.getenv("PROCUREMENT_CHANGE_EVIDENCE_DIR"));Files.createDirectories(directory);
        json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(scenario+".json").toFile(),Map.of("scenario",scenario,"result",result,"order",scope(PROCESS,()->db.getById("pr_purchase_order",order)),"line",actual,"ledger",ledger,"externalProviderSynthetic",true,"publicHttpExecuted",false));
    }
    String acceptedReason(String scenario){return switch(scenario){case "confirmed"->"supplier_confirmed";case "shipped"->"shipment_exists";case "received"->"receipt_exists";default->throw new IllegalArgumentException("Unexpected scenario");};}
    @Test void unconfirmedQuantityChangePersistsWithZeroFulfillmentCounters() throws Exception {verify("unconfirmed","updated","210","0","0");}
    @Test void shippedQuantityChangePreservesOriginalAndPendingCandidate() throws Exception {
        tx("qo_procurement_order_common:submit_shipment",()->db.update("pr_purchase_order_line",linePid,Map.of("qo_pr_order_line_submitted_qty",100)));
        verify("shipped","manual_review_required","140","100","0");
    }
    @Test void receivedQuantityChangePreservesOriginalAndPendingCandidate() throws Exception {
        tx("qo_procurement_order_common:sync_orders",()->db.update("pr_purchase_order_line",linePid,Map.of("pr_pol_received_qty",50)));
        verify("received","manual_review_required","140","0","50");
    }
    @Test void supplierConfirmationPreservesOriginalAndPendingCandidate() throws Exception {
        create("pr_purchase_order_confirmation_common:create","pr_purchase_order_confirmation_common",Map.of("pr_oc_po_id",order,"pr_oc_supplier_id",supplier,"pr_oc_status","confirmed"));
        verify("confirmed","manual_review_required","140","0","0");
    }
}
