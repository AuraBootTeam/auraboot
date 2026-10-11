package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.cloudconfig.dto.CloudConfigSaveRequest;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.auraboot.framework.user.service.UserService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in product artifact seam: actual CloudConfig/DynamicDataService/PG, synthetic external HTTP only. */
@SpringBootTest(classes=TestApplication.class,properties={
        "spring.datasource.url=${TEST_DATABASE_URL}","spring.data.redis.database=${TEST_REDIS_DATABASE}",
        "security.field-encryption.key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="})
@ActiveProfiles("integration-test")
@EnabledIfEnvironmentVariable(named="PROCUREMENT_PLUGIN_JAR",matches=".+")
class ProcurementPluginHostRealDbIT {
    private static final String SYNC="qo_procurement_task_common:sync_sources";
    @Autowired DynamicDataService data;
    @Autowired CloudConfigService configs;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource source;
    @Autowired UserService users;
    @Autowired TenantMemberService members;
    private Long actorId,tenantId;
    private URLClassLoader plugin;
    private DynamicDataAccessorImpl db;
    private HttpServer server;
    private String response,actor,task,provider;
    private List<String> logins,queries,cookies;
    private CommandHandlerExtension.CommandContext context;

    @BeforeEach void fixture() throws Exception {
        tenantId=Long.valueOf(System.getenv("TEST_TENANT_ID"));assertTrue(tenantId>0);
        assertEquals("13",System.getenv("TEST_REDIS_DATABASE"));assertNotNull(System.getenv("TEST_DATABASE_URL"));
        try(var connection=source.getConnection()){assertEquals("enterprise_22",connection.getCatalog());}
        Path jar=Path.of(System.getenv("PROCUREMENT_PLUGIN_JAR")).toRealPath();assertTrue(Files.size(jar)>0);
        plugin=new URLClassLoader(new URL[]{jar.toUri().toURL()},getClass().getClassLoader());
        db=new DynamicDataAccessorImpl(data);
        provider="ph_"+UUID.randomUUID().toString().replace("-","").substring(0,24);
        var user=users.signUp(provider+"@example.invalid","host-fixture-password-123","Host fixture",provider);
        actor=user.getPid();actorId=user.getId();
        members.addMember(actorId,tenantId,"active");
        MetaContext.setContext(tenantId,actorId,actor,"Host fixture");
        var secret=new CloudConfigSaveRequest();secret.setConfigLevel("tenant");secret.setServiceType("integration");
        secret.setProviderCode(provider);secret.setEnabled(true);secret.setPriority(1);
        secret.setConfig("{\"appSecret\":\"host-import-fixture-secret\"}");configs.saveConfig(secret);
        logins=new ArrayList<>();queries=new ArrayList<>();cookies=new ArrayList<>();response=row("100","11");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/K3Cloud/Kingdee.BOS.WebApi.ServicesStub.AuthService.LoginByAppSecret.common.kdsvc",e->{
            logins.add(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            e.getResponseHeaders().add("Set-Cookie","host-fixture=authenticated; Path=/K3Cloud");
            byte[] body="{\"LoginResultType\":1}".getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();
        });
        server.createContext("/K3Cloud/Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.ExecuteBillQuery.common.kdsvc",e->{
            queries.add(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            cookies.add(e.getRequestHeaders().getFirst("Cookie"));
            byte[] body=response.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,body.length);
            e.getResponseBody().write(body);e.close();
        });server.start();
        String product=scope(SYNC,()->(String)db.create("prod_product",Map.of("prod_code",provider,"prod_name","Host test material",
                "prod_type","raw_material","prod_unit","pcs","prod_status","active")).get("pid"));
        String connection=scope(SYNC,()->(String)db.create("customer_pcba_int_kingdee_connection",Map.of(
                "customer_pcba_kd_profile_name",provider,"customer_pcba_kd_status","active",
                "customer_pcba_kd_acct_id",provider,"customer_pcba_kd_base_url","http://127.0.0.1:"+server.getAddress().getPort(),
                "customer_pcba_kd_app_id","host-fixture-app","customer_pcba_kd_username","host-fixture-reader",
                "customer_pcba_kd_app_secret_ref","cloud-config:"+provider,"customer_pcba_kd_auth_type","app-secret")).get("pid"));
        task=scope("qo_procurement_task_common:create",()->{
            try{
                Class<?> service=plugin.loadClass("com.auraboot.quote.engine.procurement.ProcurementTaskService");
                Object result=service.getMethod("create",Object.class,String.class,Map.class).invoke(
                        service.getConstructor(DataAccessor.class).newInstance(db),tenantId,actor,Map.of("title","Host fixture "+provider,"connectionPid",connection));
                return (String)((Map<?,?>)result).get("recordPid");
            }catch(ReflectiveOperationException error){throw unwrap(error);}
        });
        scope(SYNC,()->db.create("customer_pcba_int_external_ref",Map.of("customer_pcba_ref_external_system","KINGDEE",
                "customer_pcba_ref_instance_key",connection,"customer_pcba_ref_account_id",provider,
                "customer_pcba_ref_external_form_id","BD_MATERIAL","customer_pcba_ref_external_id","900",
                "customer_pcba_ref_local_model_code","prod_product","customer_pcba_ref_local_pid",product)));
        var settings=new HashMap<String,Object>();settings.put(CommandHandlerExtension.DATA_ACCESSOR_KEY,db);
        settings.put(CommandHandlerExtension.CURRENT_USER_PID_KEY,actor);settings.put("tenantSecretRead",true);
        settings.put("tenantSecretServiceTypes",List.of("integration"));settings.put("tenantSecretFields",List.of("appSecret"));
        TenantSecretAccessorImpl.install(settings,tenantId,configs,json);
        context=new CommandHandlerExtension.CommandContext(tenantId,"com.auraboot.quote-engine","qoe",SYNC,"s2p_sourcing_event",task,
                Map.of("eligible",true,"appSecret","untrusted-payload"),settings,false);
    }
    @AfterEach void close() throws Exception {if(server!=null)server.stop(0);if(plugin!=null)plugin.close();MetaContext.clear();}
    private static RuntimeException unwrap(ReflectiveOperationException error){
        Throwable cause=error instanceof InvocationTargetException invocation?invocation.getCause():error;
        return cause instanceof RuntimeException runtime?runtime:new IllegalStateException(cause);
    }
    // Synthetic resolved host permit: this seam does not exercise public command authentication.
    private <T> T scope(String writer,Supplier<T> work){return MetaContext.runWithCommandPermitPlan("ALL",actorId,null,null,
            ()->MetaContext.runWithAuthorizedCommandCode(writer,work));}
    private Object sync(String id,String entry){
        try{
            Class<?> selection=plugin.loadClass("com.auraboot.quote.engine.procurement.ProcurementRequisitionReader$Selection");
            Object selected=selection.getConstructor(String.class,String.class).newInstance(id,entry);
            Class<?> importer=plugin.loadClass("com.auraboot.quote.engine.procurement.ProcurementRequisitionImporter");
            Class<?> policy=plugin.loadClass(importer.getName()+"$EligibilityPolicy");
            Object trusted=java.lang.reflect.Proxy.newProxyInstance(plugin,new Class<?>[]{policy},(proxy,method,args)->true);
            return importer.getMethod("sync",CommandHandlerExtension.CommandContext.class,List.class,int.class,policy)
                    .invoke(null,context,List.of(selected),20,trusted);
        }catch(ReflectiveOperationException error){throw unwrap(error);}
    }
    private List<Map<String,Object>> rows(){return scope(SYNC,()->db.queryPage("pr_purchase_request",
            new DataPageQuery(Map.of("qo_pr_source_task_pid",task),Map.of(),List.of(),1,20)).records());}
    private long references(){return scope(SYNC,()->db.queryPage("customer_pcba_int_external_ref",
            new DataPageQuery(Map.of("customer_pcba_ref_account_id",provider),Map.of(),List.of(),1,20)).total());}
    private static String row(String id,String entry){return "[["+id+",\"REQ-FIXTURE\","+entry+",900,\"TEST-MATERIAL\",\"Host material\",\"fixture\",1500,1400,400,300,1000,\"PCS\",\"2026-12-01T00:00:00\",\"ORG-FIXTURE\",\"C\",\"A\",\"A\"]]";}

    @Test void actualHostSecretAndNativePersistenceSurviveReplay() throws Exception {
        var tx=new TransactionTemplate(transactions);
        assertEquals(List.of("added"),tx.execute(status->scope(SYNC,()->sync("100","11"))));
        assertEquals(1,rows().size());var record=rows().getFirst();
        assertEquals(0,new BigDecimal("1500").compareTo(new BigDecimal(record.get("pr_preq_qty").toString())),"pr_preq_qty");
        assertEquals(0,new BigDecimal("1400").compareTo(new BigDecimal(record.get("qo_pr_source_approved_qty").toString())),"qo_pr_source_approved_qty");
        assertEquals(0,new BigDecimal("1000").compareTo(new BigDecimal(record.get("qo_pr_source_remaining_qty").toString())),"qo_pr_source_remaining_qty");
        assertEquals(2,references());
        assertEquals(List.of("unchanged"),tx.execute(status->scope(SYNC,()->sync("100","11"))));
        assertEquals(1,rows().size());assertEquals(2,references());
        assertEquals("host-import-fixture-secret",json.readTree(logins.getFirst()).path("appSecret").asText());
        assertEquals(provider,json.readTree(logins.getFirst()).path("acctID").asText());assertEquals(2,queries.size());
        assertEquals(List.of("host-fixture=authenticated","host-fixture=authenticated"),cookies);
    }
    @Test void transactionFailureAndWrongWriterCannotLeaveNativeRequestsOrReferences() {
        var tx=new TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class,()->tx.execute(status->scope(SYNC,()->{
            assertEquals(List.of("added"),sync("100","11"));assertEquals(1,rows().size());assertEquals(2,references());
            throw new IllegalStateException("host fixture rollback after actual writes");
        })));
        assertTrue(rows().isEmpty());assertEquals(1,references());
        RuntimeException denied=assertThrows(RuntimeException.class,()->tx.execute(status->scope("host_fixture:untrusted",()->sync("100","11"))));
        assertTrue(denied.getMessage().contains("WRITER_DENIED"),denied.toString());
        assertTrue(rows().isEmpty());assertEquals(1,references());
    }
}
