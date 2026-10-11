package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.auth.dao.entity.AuthIdentity;
import com.auraboot.framework.auth.mapper.AuthIdentityMapper;
import com.auraboot.framework.auth.wechat.WechatMiniClient;
import com.auraboot.framework.auth.wechat.WechatMiniProperties;
import com.auraboot.framework.auth.wechat.WechatMiniIdentityService;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.user.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
    @Autowired AuthIdentityMapper identities;
    @Autowired UserMapper userMapper;
    private Long actorId,tenantId;
    private URLClassLoader plugin;
    private DynamicDataAccessorImpl db;
    private HttpServer server;
    private String response,actor,task,provider,product;
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
        product=scope(SYNC,()->(String)db.create("prod_product",Map.of("prod_code",provider,"prod_name","Host test material",
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

    @Test void nativeBoundIdentitySelectsCurrentActorBeforeConsumingPhoneGrant() throws Exception {
        String appId = "wx-" + provider;
        String ownOpenid = "own-" + provider;
        String foreignOpenid = "foreign-" + provider;
        var foreignUser = users.signUp("other-" + provider + "@example.invalid", "host-fixture-password-123",
                "Other host fixture", "other-" + provider);
        var now = java.time.Instant.now();
        for (var entry : Map.of(ownOpenid, actorId, foreignOpenid, foreignUser.getId()).entrySet()) {
            var identity = new AuthIdentity();
            identity.setPid(com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr());
            identity.setUserId(entry.getValue());identity.setAppId(appId);identity.setProvider("wechat_mini");
            identity.setOpenid(entry.getKey());identity.setCreatedAt(now);identity.setUpdatedAt(now);
            assertEquals(1, identities.insert(identity));
        }
        var selected = new java.util.concurrent.atomic.AtomicReference<>(ownOpenid);
        var requests = new java.util.concurrent.CopyOnWriteArrayList<String>();
        server.createContext("/sns/jscode2session", e -> {
            requests.add("login");
            byte[] body = json.writeValueAsBytes(Map.of("openid", selected.get(), "session_key", "synthetic-session"));
            e.getResponseHeaders().set("Content-Type", "text/plain");
            e.sendResponseHeaders(200, body.length);e.getResponseBody().write(body);e.close();
        });
        server.createContext("/cgi-bin/stable_token", e -> {
            requests.add("token");
            byte[] body = json.writeValueAsBytes(Map.of("access_token", "synthetic-phone-token", "expires_in", 7200));
            e.sendResponseHeaders(200, body.length);e.getResponseBody().write(body);e.close();
        });
        server.createContext("/wxa/business/getuserphonenumber", e -> {
            requests.add(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = json.writeValueAsBytes(Map.of("errcode", 0, "phone_info", Map.of(
                    "purePhoneNumber", "13800138000", "countryCode", "86",
                    "watermark", Map.of("appid", appId, "timestamp", 1791730000))));
            e.sendResponseHeaders(200, body.length);e.getResponseBody().write(body);e.close();
        });
        var properties = new WechatMiniProperties();
        properties.setAppId(appId);properties.setAppSecret("synthetic-phone-secret");
        properties.setApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        var service = new WechatMiniIdentityService(identities, userMapper, new WechatMiniClient(properties), properties, users);
        var phone = service.verifyCurrentUserPhoneGrant("synthetic-login", "synthetic-phone-code");
        assertEquals(ownOpenid, phone.openid());assertEquals(appId, phone.appId());
        assertEquals("86", phone.countryCode());assertEquals("13800138000", phone.nationalNumber());
        assertEquals(List.of("login", "token"), requests.subList(0, 2));
        assertEquals(Map.of("code", "synthetic-phone-code", "openid", ownOpenid), json.readValue(requests.get(2), Map.class));
        selected.set(foreignOpenid);
        assertThrows(RootUnCheckedException.class, () -> service.verifyCurrentUserPhoneGrant("foreign-login", "unused-phone"));
        selected.set("unbound-" + provider);
        assertThrows(RootUnCheckedException.class, () -> service.verifyCurrentUserPhoneGrant("unbound-login", "unused-phone"));
        properties.setAppId("another-app");selected.set(ownOpenid);
        assertThrows(RootUnCheckedException.class, () -> service.verifyCurrentUserPhoneGrant("wrong-app-login", "unused-phone"));
        assertEquals(6, requests.size());assertEquals(List.of("login", "login", "login"), requests.subList(3, 6));
        MetaContext.setSessionContext(1L, 1L, "tenant", null, null, "active", 1, true, foreignUser.getId(), "web");
        assertThrows(RootUnCheckedException.class, () -> service.verifyCurrentUserPhoneGrant("impersonated", "unused-phone"));
        assertEquals(6, requests.size());
        MetaContext.clear();
        assertThrows(RootUnCheckedException.class, () -> service.verifyCurrentUserPhoneGrant("anonymous", "unused-phone"));
        assertEquals(6, requests.size());
        MetaContext.setContext(tenantId, actorId, actor, "Host fixture");
        var stored = identities.selectList(new QueryWrapper<AuthIdentity>().eq("app_id", appId));
        assertEquals(2, stored.size());
        assertTrue(stored.stream().allMatch(identity -> identity.getLastLoginAt() == null));
        assertEquals(Set.of(actorId, foreignUser.getId()), new HashSet<>(stored.stream().map(AuthIdentity::getUserId).toList()));
    }

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
    private String matchPhone(String phone) {
        try {
            Class<?> service=plugin.loadClass("com.auraboot.quote.engine.procurement.ProcurementSourceService");
            return (String)service.getMethod("matchVerifiedPhone",String.class)
                    .invoke(service.getConstructor(DataAccessor.class).newInstance(db),phone);
        } catch(ReflectiveOperationException error) {throw unwrap(error);}
    }
    @Test void nativePhoneLookupCountsActiveContactsAndRejectsAmbiguousOwnership() {
        String writer="pr_supplier_pcba_contact:sync_directory";
        String phone="+8613"+String.format(Locale.ROOT,"%09d",Math.floorMod(actorId,1_000_000_000L));
        String supplier=scope(writer,()->(String)db.create("pr_supplier_common",Map.of(
                "pr_supplier_code",provider,"pr_supplier_name","Host phone fixture","pr_supplier_status","enabled")).get("pid"));
        String contact=scope(writer,()->(String)db.create("pr_supplier_pcba_contact",Map.of(
                "pr_spc_supplier_id",supplier,"pr_spc_name","Current contact","qo_pr_contact_state","active",
                "qo_pr_contact_normalized_phone",phone)).get("pid"));
        scope(writer,()->db.create("pr_supplier_pcba_contact",Map.of("pr_spc_supplier_id",supplier,
                "pr_spc_name","Disabled history","qo_pr_contact_state","inactive","qo_pr_contact_normalized_phone",phone)));
        assertEquals(contact,scope(writer,()->matchPhone(phone)));
        scope(writer,()->db.create("pr_supplier_pcba_contact",Map.of("pr_spc_supplier_id",supplier,
                "pr_spc_name","Ambiguous active contact","qo_pr_contact_state","active","qo_pr_contact_normalized_phone",phone)));
        SecurityException conflict=assertThrows(SecurityException.class,()->scope(writer,()->matchPhone(phone)));
        assertEquals("PHONE_OWNERSHIP_CONFLICT",conflict.getMessage());
        scope(writer,()->db.update("pr_supplier_pcba_contact",contact,Map.of("qo_pr_contact_state","inactive")));
        assertNotEquals(contact,scope(writer,()->matchPhone(phone)));
        scope(writer,()->db.update("pr_supplier_common",supplier,Map.of("pr_supplier_status","disabled")));
        assertEquals("SUPPLIER_UNAVAILABLE",assertThrows(SecurityException.class,()->scope(writer,()->matchPhone(phone))).getMessage());
    }

    private Object invokeService(String service,String method,Class<?>[] parameters,Object... arguments) {
        return invokeServiceAt(java.time.Clock.systemUTC(),service,method,parameters,arguments);
    }
    private Object invokeServiceAt(java.time.Clock clock,String service,String method,Class<?>[] parameters,Object... arguments) {
        try {
            Class<?> type=plugin.loadClass("com.auraboot.quote.engine.procurement."+service);
            return type.getMethod(method,parameters).invoke(type.getConstructor(DataAccessor.class,java.time.Clock.class)
                    .newInstance(db,clock),arguments);
        } catch(ReflectiveOperationException error) {throw unwrap(error);}
    }
    private List<Map<String,Object>> nativeRows(String model,String field,String value) {
        return db.queryPage(model,new DataPageQuery(Map.of(field,value),Map.of(),List.of(),1,20)).records();
    }
    private void assertNativeQuantity(String model,String pid,String field,String quantity) {
        assertEquals(0,new BigDecimal(quantity).compareTo(new BigDecimal(db.getById(model,pid).get(field).toString())),field);
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> createProductEntity(String entity,Map<String,Object> values) {
        try {
            Class<?> type=plugin.loadClass("com.auraboot.quote.engine.procurement.ProcurementData");
            return (Map<String,Object>)type.getMethod("create",DataAccessor.class,String.class,Map.class)
                    .invoke(null,db,entity,values);
        } catch(ReflectiveOperationException error) {throw unwrap(error);}
    }
    private Object quoteAt(java.time.Clock clock,String action,String quote,Map<String,Object> payload) {
        return invokeServiceAt(clock,"ProcurementQuoteService","execute",
                new Class<?>[]{String.class,String.class,String.class,Map.class},action,quote,actor,payload);
    }
    /** Deterministic passage of time through the actual service, without replacing native persistence. */
    private static java.time.Clock crossingClock(java.time.Instant before,java.time.Instant after) {
        var reads=new java.util.concurrent.atomic.AtomicInteger();
        return new java.time.Clock() {
            @Override public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}
            @Override public java.time.Clock withZone(java.time.ZoneId zone){
                if(!java.time.ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("UTC fixture required");
                return this;
            }
            @Override public java.time.Instant instant(){return reads.getAndIncrement()==0?before:after;}
        };
    }
    private record QuoteFixture(String quote,String source,java.time.Instant cutoff,java.time.Clock clock) {}
    private QuoteFixture savedNativeQuote() {
        String directory="pr_supplier_common:sync_directory",dispatch="qo_procurement_task_common:dispatch";
        String save="qo_procurement_quote_common:save_draft",submit="qo_procurement_quote_common:submit";
        var tx=new TransactionTemplate(transactions);
        tx.execute(status->scope(SYNC,()->sync("100","11")));
        Map<String,Object> sourceRow=rows().getFirst();String sourcePid=(String)sourceRow.get("pid");
        String supplier=scope(directory,()->(String)db.create("pr_supplier_common",Map.of("pr_supplier_code",provider,
                "pr_supplier_name","Deadline fixture","pr_supplier_status","enabled","qo_pr_supplier_account_id",provider)).get("pid"));
        String contact=scope(directory,()->(String)db.create("pr_supplier_pcba_contact",Map.of(
                "pr_spc_supplier_id",supplier,"pr_spc_name","Deadline contact","qo_pr_contact_state","active")).get("pid"));
        // Synthetic bound membership and dispatched quote isolate the deadline/transaction seam.
        scope("qo_procurement_membership_common:bind_verified_phone",()->db.create("qo_procurement_membership_common",Map.of(
                "qo_pr_membership_key",provider,"qo_pr_membership_user_pid",actor,"qo_pr_membership_supplier_pid",supplier,
                "qo_pr_membership_contact_pid",contact,"qo_pr_membership_state","active")));
        var cutoff=java.time.Instant.parse("2026-11-15T08:00:00Z");var before=cutoff.minusSeconds(1);
        var fixed=java.time.Clock.fixed(before,java.time.ZoneOffset.UTC);
        String quote=scope(dispatch,()->(String)createProductEntity("quote",Map.of("key",provider,"task_pid",task,
                "supplier_pid",supplier,"state","draft","revision",0,"cutoff_at",cutoff.toString(),
                "received_at",before.toString(),"source_pids",List.of(sourcePid))).get("pid"));
        var line=Map.<String,Object>of("sourcePid",sourcePid,"sourceRevision",sourceRow.get("qo_pr_source_revision"),
                "unitPrice","1.250001","currency","CNY","unit","PCS","taxRate","0.13","taxIncluded",true,
                "availableQty","600","deliveryDate","2026-12-01");
        tx.execute(status->scope(save,()->quoteAt(fixed,"save_draft",quote,Map.of("revision",0,"lines",List.of(line),"paymentTerms","Net 30"))));
        return new QuoteFixture(quote,sourcePid,cutoff,fixed);
    }
    @Test void nativeFormalSubmissionRechecksDeadlineAndRollsBackAllWrites() {
        var fixture=savedNativeQuote();String quote=fixture.quote(),sourcePid=fixture.source();
        var cutoff=fixture.cutoff();var before=cutoff.minusSeconds(1);var fixed=fixture.clock();
        String submit="qo_procurement_quote_common:submit";var tx=new TransactionTemplate(transactions);
        Map<String,Object> draft=scope(submit,()->db.getById("s2p_bid",quote));
        Map<String,Object> originalSource=scope(submit,()->db.getById("pr_purchase_request",sourcePid));
        var history=scope(submit,()->nativeRows("qo_procurement_history_common","qo_pr_history_target_pid",quote));
        var draftLines=scope(submit,()->nativeRows("qo_procurement_quote_line_common","qo_pr_quote_line_quote_pid",quote));
        for(var reached:List.of(cutoff,cutoff.plusNanos(1))) {
            var failure=assertThrows(IllegalStateException.class,()->tx.execute(status->scope(submit,
                    ()->quoteAt(crossingClock(before,reached),"submit",quote,Map.of("revision",1)))));
            assertEquals("CUTOFF_EXPIRED",failure.getMessage());
            scope(submit,()->{
                assertEquals(draft,db.getById("s2p_bid",quote));
                assertEquals(originalSource,db.getById("pr_purchase_request",sourcePid));
                assertEquals(draftLines,nativeRows("qo_procurement_quote_line_common","qo_pr_quote_line_quote_pid",quote));
                assertEquals(history,nativeRows("qo_procurement_history_common","qo_pr_history_target_pid",quote));
                assertTrue(nativeRows("qo_procurement_submission_common","qo_pr_submission_quote_pid",quote).isEmpty());
                return null;
            });
        }
        Object accepted=tx.execute(status->scope(submit,()->quoteAt(fixed,"submit",quote,Map.of("revision",1))));
        var result=(Map<?,?>)accepted;
        assertEquals("submitted",result.get("state"));
        var after=java.time.Clock.fixed(cutoff.plusSeconds(1),java.time.ZoneOffset.UTC);
        assertEquals(accepted,tx.execute(status->scope(submit,()->quoteAt(after,"submit",quote,Map.of("revision",result.get("revision"))))));
        scope(submit,()->{
            assertEquals(1,nativeRows("qo_procurement_submission_common","qo_pr_submission_quote_pid",quote).size());
            assertEquals(history.size()+1,nativeRows("qo_procurement_history_common","qo_pr_history_target_pid",quote).size());
            var submitted=assertInstanceOf(java.sql.Timestamp.class,db.getById("s2p_bid",quote).get("s2p_bid_submitted_at"));
            assertEquals(before,submitted.toInstant());
            return null;
        });
    }

    @Test void nativeSourceChangeInvalidatesAndRequiresWholeReturnBeforeRequote() throws Exception {
        var fixture=savedNativeQuote();String quote=fixture.quote(),sourcePid=fixture.source();
        String submit="qo_procurement_quote_common:submit",save="qo_procurement_quote_common:save_draft";
        String returned="qo_procurement_quote_common:return";var tx=new TransactionTemplate(transactions);
        var accepted=(Map<?,?>)tx.execute(status->scope(submit,()->quoteAt(fixture.clock(),"submit",quote,Map.of("revision",1))));
        String original=(String)accepted.get("submissionPid");
        var snapshot=scope(submit,()->db.getById("qo_procurement_submission_common",original));
        var snapshotLines=scope(submit,()->nativeRows("qo_procurement_submission_line_common","qo_pr_submission_line_submission_pid",original));
        assertEquals(1,snapshotLines.size());
        assertEquals(List.of("unchanged"),tx.execute(status->scope(SYNC,()->sync("100","11"))));
        scope(SYNC,()->{
            assertEquals(1,rows().size());assertEquals(sourcePid,rows().getFirst().get("pid"));
            assertEquals("submitted",db.getById("s2p_bid",quote).get("s2p_bid_status"));return null;
        });
        response=row("100","11").replace(",1500,1400,400,300,1000,",",1500,1399,400,300,1000,");
        assertEquals(List.of("changed"),tx.execute(status->scope(SYNC,()->sync("100","11"))));
        scope(SYNC,()->{
            assertEquals(1,rows().size());assertEquals(sourcePid,rows().getFirst().get("pid"));
            assertEquals(task,rows().getFirst().get("qo_pr_source_task_pid"));
            assertNativeQuantity("pr_purchase_request",sourcePid,"qo_pr_source_approved_qty","1399");
            assertNativeQuantity("pr_purchase_request",sourcePid,"qo_pr_source_revision","1");
            assertEquals("invalidated",db.getById("s2p_bid",quote).get("s2p_bid_status"));
            assertNativeQuantity("s2p_bid",quote,"qo_pr_quote_revision","3");
            assertEquals(snapshot,db.getById("qo_procurement_submission_common",original));
            assertEquals(snapshotLines,nativeRows("qo_procurement_submission_line_common","qo_pr_submission_line_submission_pid",original));return null;
        });
        assertEquals("QUOTE_LOCKED",assertThrows(IllegalStateException.class,()->tx.execute(status->scope(submit,
                ()->quoteAt(fixture.clock(),"submit",quote,Map.of("revision",3))))).getMessage());
        assertEquals(List.of("unchanged"),tx.execute(status->scope(SYNC,()->sync("100","11"))));
        tx.execute(status->scope(returned,()->quoteAt(fixture.clock(),"return",quote,Map.of("revision",3,
                "reason","Demand changed","cutoffAt",fixture.cutoff().plusSeconds(3600).toString()))));
        scope(returned,()->{
            var lines=nativeRows("qo_procurement_quote_line_common","qo_pr_quote_line_quote_pid",quote);
            assertEquals(1,lines.size());assertNativeQuantity("qo_procurement_quote_line_common",(String)lines.getFirst().get("pid"),"qo_pr_quote_line_source_revision","1");
            assertEquals("draft",db.getById("s2p_bid",quote).get("s2p_bid_status"));return null;
        });
        var newLine=Map.<String,Object>of("sourcePid",sourcePid,"sourceRevision",1,"unitPrice","2.000001",
                "currency","CNY","unit","PCS","taxRate","0.13","taxIncluded",true,"availableQty","600","deliveryDate","2026-12-01");
        tx.execute(status->scope(save,()->quoteAt(fixture.clock(),"save_draft",quote,Map.of("revision",4,"lines",List.of(newLine),"paymentTerms","Net 60"))));
        var second=(Map<?,?>)tx.execute(status->scope(submit,()->quoteAt(fixture.clock(),"submit",quote,Map.of("revision",5))));
        assertNotEquals(original,second.get("submissionPid"));
        scope(submit,()->{
            assertEquals(2,nativeRows("qo_procurement_submission_common","qo_pr_submission_quote_pid",quote).size());
            assertEquals(snapshot,db.getById("qo_procurement_submission_common",original));
            assertEquals(snapshotLines,nativeRows("qo_procurement_submission_line_common","qo_pr_submission_line_submission_pid",original));
            assertNativeQuantity("s2p_bid",quote,"qo_pr_quote_revision","6");
            assertEquals("submitted",db.getById("s2p_bid",quote).get("s2p_bid_status"));return null;
        });
        String latestContent=scope(submit,()->nativeRows("qo_procurement_submission_line_common",
                "qo_pr_submission_line_submission_pid",(String)second.get("submissionPid")).getFirst().get("qo_pr_submission_line_content").toString());
        var originalContent=json.readTree(snapshotLines.getFirst().get("qo_pr_submission_line_content").toString());
        var newContent=json.readTree(latestContent);
        assertEquals(0,new BigDecimal("1400").compareTo(originalContent.path("source_snapshot").path("approved_qty").decimalValue()));
        assertEquals(0,new BigDecimal("1399").compareTo(newContent.path("source_snapshot").path("approved_qty").decimalValue()));
        assertEquals(0,new BigDecimal("2.000001").compareTo(newContent.path("unit_price").decimalValue()));
    }

    private Object allocateNative(Map<String,Object> payload) {
        try {
            Class<?> type=plugin.loadClass("com.auraboot.quote.engine.procurement.ProcurementAllocationService");
            return type.getMethod("allocate",String.class,Map.class)
                    .invoke(type.getConstructor(DataAccessor.class).newInstance(db),task,payload);
        } catch(ReflectiveOperationException error) {throw unwrap(error);}
    }
    private void openFixtureTask() {
        try {
            Class<?> type=plugin.loadClass("com.auraboot.quote.engine.procurement.ProcurementData");
            type.getMethod("update",DataAccessor.class,String.class,String.class,Map.class)
                    .invoke(null,db,"task",task,Map.of("state","open"));
        } catch(ReflectiveOperationException error) {throw unwrap(error);}
    }
    @Test void nativeAllocatedDemandChangeAndWholeReturnAreRejectedWithoutLosingReservations() {
        var fixture=savedNativeQuote();String quote=fixture.quote(),sourcePid=fixture.source();
        String submit="qo_procurement_quote_common:submit",allocate="qo_procurement_task_common:allocate";
        String returned="qo_procurement_quote_common:return";var tx=new TransactionTemplate(transactions);
        var formal=(Map<?,?>)tx.execute(status->scope(submit,()->quoteAt(fixture.clock(),"submit",quote,Map.of("revision",1))));
        scope("qo_procurement_task_common:dispatch",()->{openFixtureTask();return null;});
        var request=Map.<String,Object>of("sourceDemands",List.of(Map.of("sourcePid",sourcePid,"sourceRevision",0,"quantity","300.000001")),
                "allocations",List.of(Map.of("quotePid",quote,"sourcePid",sourcePid,"submissionPid",formal.get("submissionPid"),"quantity","300.000001")));
        Object confirmed=tx.execute(status->scope(allocate,()->allocateNative(request)));
        var sourceBefore=scope(allocate,()->db.getById("pr_purchase_request",sourcePid));
        var quoteBefore=scope(allocate,()->db.getById("s2p_bid",quote));
        var taskBefore=scope(allocate,()->db.getById("s2p_sourcing_event",task));
        var allocations=scope(allocate,()->nativeRows("qo_procurement_allocation_common","qo_pr_allocation_task_pid",task));
        var executions=scope(allocate,()->nativeRows("qo_procurement_execution_common","qo_pr_execution_task_pid",task));
        var history=scope(allocate,()->nativeRows("qo_procurement_history_common","qo_pr_history_target_pid",quote));
        var submissions=scope(allocate,()->nativeRows("qo_procurement_submission_common","qo_pr_submission_quote_pid",quote));
        assertEquals(1,allocations.size());assertEquals(1,executions.size());assertEquals(1,submissions.size());
        scope(allocate,()->{assertNativeQuantity("pr_purchase_request",sourcePid,"qo_pr_source_reserved_qty","300.000001");return null;});
        for(String changed:List.of(
                row("100","11").replace(",1500,1400,400,300,1000,",",1500,1399,400,300,1000,"),
                row("100","11").replace(",1500,1400,400,300,1000,",",1500,1400,400,300,250,"),
                row("100","11").replace(",1500,1400,400,300,1000,",",1500,1400,400,300,0,"))) {
            response=changed;
            assertEquals("SOURCE_CHANGE_REQUIRES_ALLOCATION_REVIEW",assertThrows(IllegalStateException.class,
                    ()->tx.execute(status->scope(SYNC,()->sync("100","11")))).getMessage());
        }
        assertEquals("QUOTE_ALREADY_ALLOCATED",assertThrows(IllegalStateException.class,
                ()->tx.execute(status->scope(returned,()->quoteAt(fixture.clock(),"return",quote,Map.of("revision",2,
                        "reason","Allocated quote cannot be returned","cutoffAt",fixture.cutoff().plusSeconds(3600).toString()))))).getMessage());
        assertEquals(confirmed,tx.execute(status->scope(allocate,()->allocateNative(request))));
        scope(allocate,()->{
            assertEquals(sourceBefore,db.getById("pr_purchase_request",sourcePid));
            assertEquals(quoteBefore,db.getById("s2p_bid",quote));
            assertEquals(taskBefore,db.getById("s2p_sourcing_event",task));
            assertEquals(allocations,nativeRows("qo_procurement_allocation_common","qo_pr_allocation_task_pid",task));
            assertEquals(executions,nativeRows("qo_procurement_execution_common","qo_pr_execution_task_pid",task));
            assertEquals(history,nativeRows("qo_procurement_history_common","qo_pr_history_target_pid",quote));
            assertEquals(submissions,nativeRows("qo_procurement_submission_common","qo_pr_submission_quote_pid",quote));
            assertNativeQuantity("pr_purchase_request",sourcePid,"qo_pr_source_reserved_qty","300.000001");
            assertNativeQuantity("pr_purchase_request",sourcePid,"qo_pr_source_approved_qty","1400");return null;
        });
    }

    @Test void nativeShipmentReplaySnapshotAndFulfillmentNeverChangeActualReceipts() {
        String directory="pr_supplier_common:sync_directory",publish="qo_procurement_order_common:sync_orders";
        String submit="qo_procurement_order_common:submit_shipment",maintain="qo_procurement_address_common:maintain";
        String supplier=scope(directory,()->(String)db.create("pr_supplier_common",Map.of("pr_supplier_code",provider,
                "pr_supplier_name","Shipment fixture","pr_supplier_status","enabled","qo_pr_supplier_account_id",provider)).get("pid"));
        String contact=scope(directory,()->(String)db.create("pr_supplier_pcba_contact",Map.of(
                "pr_spc_supplier_id",supplier,"pr_spc_name","Shipment contact","qo_pr_contact_state","active")).get("pid"));
        // Synthetic already-bound supplier fixture; this is not proof of WeChat phone-grant exchange.
        scope("qo_procurement_membership_common:bind_verified_phone",()->db.create("qo_procurement_membership_common",Map.of(
                "qo_pr_membership_key",provider,"qo_pr_membership_user_pid",actor,"qo_pr_membership_supplier_pid",supplier,
                "qo_pr_membership_contact_pid",contact,"qo_pr_membership_state","active")));
        String address=scope(maintain,()->(String)db.create("qo_procurement_address_common",Map.of(
                "qo_pr_address_supplier_pid",supplier,"qo_pr_address_state","active","qo_pr_address_address","Fixture warehouse A",
                "qo_pr_address_manager_name","Fixture manager","qo_pr_address_manager_phone","+8613800000000")).get("pid"));
        var header=Map.<String,Object>of("account_id",provider,"erp_id",provider,"supplier_pid",supplier,
                "bill_no","SHIP-"+provider,"order_date","2026-10-12","business_status","approved");
        var lines=List.of(Map.<String,Object>of("erp_entry_id","1","product_pid",product,"ordered_qty","1000",
                "submitted_qty","0","received_qty","0"));
        var tx=new TransactionTemplate(transactions);
        assertEquals("published",tx.execute(status->scope(publish,()->invokeService("ProcurementOrderService","publish",
                new Class<?>[]{Map.class,List.class},header,lines))));
        String order=(String)scope(publish,()->nativeRows("pr_purchase_order","qo_pr_order_erp_id",provider).getFirst().get("pid"));
        String line=(String)scope(publish,()->nativeRows("pr_purchase_order_line","pr_pol_order_id",order).getFirst().get("pid"));
        var partial=Map.<String,Object>of("requestKey","shipment-1","addressPid",address,
                "lines",List.of(Map.of("orderLinePid",line,"quantity","600")));
        Object first=tx.execute(status->scope(submit,()->invokeService("ProcurementShipmentService","submit",
                new Class<?>[]{String.class,String.class,Map.class},order,actor,partial)));
        assertEquals(first,tx.execute(status->scope(submit,()->invokeService("ProcurementShipmentService","submit",
                new Class<?>[]{String.class,String.class,Map.class},order,actor,partial))));
        scope(submit,()->{assertNativeQuantity("pr_purchase_order_line",line,"qo_pr_order_line_submitted_qty","600");
            assertNativeQuantity("pr_purchase_order_line",line,"pr_pol_received_qty","0");
            assertNativeQuantity("pr_purchase_order",order,"qo_pr_order_revision","1");return null;});
        scope(maintain,()->db.update("qo_procurement_address_common",address,Map.of("qo_pr_address_address","Fixture warehouse B")));
        var shipment=scope(submit,()->nativeRows("qo_procurement_shipment_common","qo_pr_shipment_order_pid",order).getFirst());
        try {assertEquals("Fixture warehouse A",json.readTree(shipment.get("qo_pr_shipment_address_snapshot").toString()).path("address").asText());}
        catch(java.io.IOException error){throw new IllegalStateException(error);}
        var tooMuch=Map.<String,Object>of("requestKey","shipment-over","addressPid",address,
                "lines",List.of(Map.of("orderLinePid",line,"quantity","401")));
        assertThrows(IllegalArgumentException.class,()->tx.execute(status->scope(submit,()->invokeService("ProcurementShipmentService","submit",
                new Class<?>[]{String.class,String.class,Map.class},order,actor,tooMuch))));
        scope(submit,()->{assertNativeQuantity("pr_purchase_order_line",line,"qo_pr_order_line_submitted_qty","600");
            assertEquals(1,nativeRows("qo_procurement_shipment_common","qo_pr_shipment_order_pid",order).size());
            assertNativeQuantity("pr_purchase_order",order,"qo_pr_order_revision","1");return null;});
        var rest=Map.<String,Object>of("requestKey","shipment-2","addressPid",address,
                "lines",List.of(Map.of("orderLinePid",line,"quantity","400")));
        tx.execute(status->scope(submit,()->invokeService("ProcurementShipmentService","submit",
                new Class<?>[]{String.class,String.class,Map.class},order,actor,rest)));
        scope(submit,()->{assertNativeQuantity("pr_purchase_order_line",line,"qo_pr_order_line_submitted_qty","1000");
            assertNativeQuantity("pr_purchase_order_line",line,"pr_pol_received_qty","0");
            assertEquals("fulfilled",db.getById("pr_purchase_order",order).get("qo_pr_order_state"));
            assertNativeQuantity("pr_purchase_order",order,"qo_pr_order_revision","2");
            assertEquals(2,nativeRows("qo_procurement_shipment_common","qo_pr_shipment_order_pid",order).size());return null;});
    }

}
