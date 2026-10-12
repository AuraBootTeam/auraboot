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
    @Autowired BackgroundDataAccessor background;
    @Autowired com.auraboot.framework.tenant.service.TenantService tenants;
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
    private Object sync(String id,String entry){return syncWith(context,id,entry);}
    private Object syncWith(CommandHandlerExtension.CommandContext selectedContext,String id,String entry){
        return syncSelectedWith(selectedContext,List.<String[]>of(new String[]{id,entry}));
    }
    private Object syncSelectedWith(CommandHandlerExtension.CommandContext selectedContext,List<String[]> identities){
        try{
            Class<?> selection=plugin.loadClass("com.auraboot.quote.engine.procurement.ProcurementRequisitionReader$Selection");
            var selected=new ArrayList<Object>();
            for(var identity:identities) selected.add(selection.getConstructor(String.class,String.class).newInstance(identity[0],identity[1]));
            Class<?> importer=plugin.loadClass("com.auraboot.quote.engine.procurement.ProcurementRequisitionImporter");
            Class<?> policy=plugin.loadClass(importer.getName()+"$EligibilityPolicy");
            Object trusted=java.lang.reflect.Proxy.newProxyInstance(plugin,new Class<?>[]{policy},(proxy,method,args)->true);
            return importer.getMethod("sync",CommandHandlerExtension.CommandContext.class,List.class,int.class,policy)
                    .invoke(null,selectedContext,selected,20,trusted);
        }catch(ReflectiveOperationException error){throw unwrap(error);}
    }
    private List<Map<String,Object>> rows(){return scope(SYNC,()->db.queryPage("pr_purchase_request",
            new DataPageQuery(Map.of("qo_pr_source_task_pid",task),Map.of(),List.of(),1,20)).records());}
    private long references(){return scope(SYNC,()->db.queryPage("customer_pcba_int_external_ref",
            new DataPageQuery(Map.of("customer_pcba_ref_account_id",provider),Map.of(),List.of(),1,20)).total());}
    private static String row(String id,String entry){return "[["+id+",\"REQ-FIXTURE\","+entry+",900,\"TEST-MATERIAL\",\"Host material\",\"fixture\",1500,1400,400,300,1000,\"PCS\",\"2026-12-01T00:00:00\",\"ORG-FIXTURE\",\"C\",\"A\",\"A\"]]";}

    @Test void nativeBoundIdentitySelectsCurrentActorBeforeConsumingPhoneGrant() throws Exception {
        String phoneNational = "13" + String.format(Locale.ROOT,"%09d",Math.floorMod(actorId,1_000_000_000L));
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
                    "purePhoneNumber", phoneNational, "countryCode", "86",
                    "watermark", Map.of("appid", appId, "timestamp", 1791730000))));
            e.sendResponseHeaders(200, body.length);e.getResponseBody().write(body);e.close();
        });
        var properties = new WechatMiniProperties();
        properties.setAppId(appId);properties.setAppSecret("synthetic-phone-secret");
        properties.setApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        var service = new WechatMiniIdentityService(identities, userMapper, new WechatMiniClient(properties), properties, users);
        var grantSettings = new HashMap<String,Object>();
        grantSettings.put(com.auraboot.framework.plugin.extension.VerifiedPhoneGrantAccessor.OPT_IN_HANDLER_PARAM, true);
        VerifiedPhoneGrantAccessorImpl.install(grantSettings, tenantId, actorId, false, service);
        var grant = (com.auraboot.framework.plugin.extension.VerifiedPhoneGrantAccessor) grantSettings.get(
                com.auraboot.framework.plugin.extension.VerifiedPhoneGrantAccessor.SETTINGS_KEY);
        assertNotNull(grant);
        String writer="pr_supplier_pcba_contact:sync_directory";
        String supplier=scope(writer,()->(String)db.create("pr_supplier_common",Map.of(
                "pr_supplier_code",provider,"pr_supplier_name","Verified phone binding fixture","pr_supplier_status","enabled")).get("pid"));
        String contact=scope(writer,()->(String)db.create("pr_supplier_pcba_contact",Map.of(
                "pr_spc_supplier_id",supplier,"pr_spc_name","Verified contact","qo_pr_contact_state","active",
                "qo_pr_contact_normalized_phone","+86"+phoneNational)).get("pid"));
        var bound=new TransactionTemplate(transactions).execute(status->scope("qo_procurement_membership_common:bind_verified_phone",
                ()->bindNativeMembership(db,grant)));
        assertEquals(supplier,bound.get("supplierPid"));assertEquals(contact,bound.get("contactPid"));
        var bindingStored=scope(writer,()->db.getById("qo_procurement_membership_common",bound.get("recordPid").toString()));
        assertEquals(actor,bindingStored.get("qo_pr_membership_user_pid"));assertEquals(tenantId,bindingStored.get("tenant_id"));
        assertEquals("active",bindingStored.get("qo_pr_membership_state"));
        assertEquals("PHONE_GRANT_ALREADY_CONSUMED", assertThrows(SecurityException.class,
                () -> grant.verifyCurrentPhone("replayed-login", "replayed-phone")).getMessage());
        assertEquals(3, requests.size());
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

    @SuppressWarnings("unchecked")
    private Map<String,Object> bindNativeMembership(DataAccessor selected,com.auraboot.framework.plugin.extension.VerifiedPhoneGrantAccessor grant) {
        var settings=new HashMap<String,Object>();settings.put(CommandHandlerExtension.DATA_ACCESSOR_KEY,selected);
        settings.put(CommandHandlerExtension.CURRENT_USER_PID_KEY,actor);
        settings.put(com.auraboot.framework.plugin.extension.VerifiedPhoneGrantAccessor.SETTINGS_KEY,grant);
        settings.put(CommandHandlerExtension.TENANT_PROJECTION_ACCESSOR_KEY,new TenantProjectionAccessorImpl(tenantId,background));
        var request=new CommandHandlerExtension.CommandContext(tenantId,"com.auraboot.quote-engine","qoe",
                "qo_procurement_membership_common:bind_verified_phone","qo_procurement_membership_common",null,
                Map.of("loginCode","synthetic-login","phoneCode","synthetic-phone-code"),settings,false);
        try {var type=plugin.loadClass("com.auraboot.quote.engine.procurement.ProcurementCommandHandler");
            return (Map<String,Object>)type.getMethod("execute",CommandHandlerExtension.CommandContext.class)
                    .invoke(type.getConstructor().newInstance(),request);
        }catch(ReflectiveOperationException error){throw unwrap(error);}
    }

    @Test void nativeMembershipCreationRollsBackOnObservedDirectoryChange() {
        String writer="pr_supplier_pcba_contact:sync_directory",bind="qo_procurement_membership_common:bind_verified_phone";
        String phone="+8613"+String.format(Locale.ROOT,"%09d",Math.floorMod(actorId,1_000_000_000L));
        String supplier=scope(writer,()->(String)db.create("pr_supplier_common",Map.of("pr_supplier_code",provider,
                "pr_supplier_name","Binding rollback fixture","pr_supplier_status","enabled")).get("pid"));
        String contact=scope(writer,()->(String)db.create("pr_supplier_pcba_contact",Map.of("pr_spc_supplier_id",supplier,
                "pr_spc_name","Binding rollback contact","qo_pr_contact_state","active","qo_pr_contact_normalized_phone",phone)).get("pid"));
        var captured=new java.util.concurrent.atomic.AtomicReference<String>();
        DataAccessor observed=(DataAccessor)java.lang.reflect.Proxy.newProxyInstance(DataAccessor.class.getClassLoader(),new Class<?>[]{DataAccessor.class},
                (proxy,method,args)->{try {
                    Object result=method.invoke(db,args);
                    if(method.getName().equals("create") && args[0].equals("qo_procurement_membership_common")) {
                        captured.set(((Map<?,?>)result).get("pid").toString());
                        scope(writer,()->db.update("pr_supplier_pcba_contact",contact,Map.of("qo_pr_contact_state","inactive")));
                    }
                    return result;
                }catch(InvocationTargetException error){throw error.getCause();}});
        var tx=new TransactionTemplate(transactions);
        var rejection=assertThrows(com.auraboot.framework.plugin.extension.PluginCommandRejectionException.class,()->tx.execute(status->scope(bind,()->bindNativeMembership(observed,(l,p)->phone))));
        assertEquals("qo_pr.error.phone_not_registered",rejection.messageKey());assertNotNull(captured.get());
        assertTrue(scope(bind,()->nativeRows("qo_procurement_membership_common","qo_pr_membership_user_pid",actor)).isEmpty());
        assertEquals("active",scope(writer,()->db.getById("pr_supplier_pcba_contact",contact)).get("qo_pr_contact_state"));
        var first=tx.execute(status->scope(bind,()->bindNativeMembership(db,(l,p)->phone)));
        var snapshot=scope(bind,()->db.getById("qo_procurement_membership_common",first.get("recordPid").toString()));
        var second=tx.execute(status->scope(bind,()->bindNativeMembership(db,(l,p)->phone)));
        assertEquals(first,second);assertEquals(snapshot,scope(bind,()->db.getById("qo_procurement_membership_common",first.get("recordPid").toString())));
        assertEquals(1,scope(bind,()->nativeRows("qo_procurement_membership_common","qo_pr_membership_user_pid",actor)).size());
    }

    @Test void nativePhoneBindingIgnoresActualForeignTenantDirectory() throws Exception {
        String writer="pr_supplier_pcba_contact:sync_directory",bind="qo_procurement_membership_common:bind_verified_phone";
        String phone="+8613"+String.format(Locale.ROOT,"%09d",Math.floorMod(actorId,1_000_000_000L));
        String foreignOnly="+8614"+String.format(Locale.ROOT,"%09d",Math.floorMod(actorId,1_000_000_000L));
        var foreign=new com.auraboot.framework.tenant.dao.entity.Tenant();
        foreign.setName("foreign-"+provider);foreign.setDisplayName("Foreign directory fixture");
        foreign.setPid(com.auraboot.framework.common.util.UlidGenerator.generate());foreign.setStatus("ACTIVE");
        tenants.createTenant(foreign);assertNotEquals(tenantId,foreign.getId());
        String localSupplier=scope(writer,()->(String)db.create("pr_supplier_common",Map.of(
                "pr_supplier_code",provider,"pr_supplier_name","Local supplier","pr_supplier_status","enabled")).get("pid"));
        String localContact=scope(writer,()->(String)db.create("pr_supplier_pcba_contact",Map.of(
                "pr_spc_supplier_id",localSupplier,"pr_spc_name","Local same phone","qo_pr_contact_state","active",
                "qo_pr_contact_normalized_phone",phone)).get("pid"));
        // Independent foreign fixture rows use the physical table; they do not seed foreign metadata or enrollment.
        String foreignSupplier=cloneForeignDirectoryFixture("mt_pr_supplier_common",localSupplier,foreign.getId(),
                Map.of("pr_supplier_code","foreign-"+provider,"pr_supplier_name","Foreign supplier"));
        String foreignContact=cloneForeignDirectoryFixture("mt_pr_supplier_pcba_contact",localContact,foreign.getId(),
                Map.of("pr_spc_supplier_id",foreignSupplier,"pr_spc_name","Foreign same phone"));
        cloneForeignDirectoryFixture("mt_pr_supplier_pcba_contact",localContact,foreign.getId(),
                Map.of("pr_spc_supplier_id",foreignSupplier,"pr_spc_name","Foreign exclusive phone","qo_pr_contact_normalized_phone",foreignOnly));
        try(var c=source.getConnection();var q=c.prepareStatement("SELECT count(*) FROM mt_pr_supplier_pcba_contact WHERE tenant_id=? AND qo_pr_contact_normalized_phone=? AND qo_pr_contact_state='active'")) {
            q.setLong(1,foreign.getId());q.setString(2,phone);
            try(var result=q.executeQuery()){assertTrue(result.next());assertEquals(1,result.getInt(1));}
        }
        var projection=new TenantProjectionAccessorImpl(tenantId,background);
        assertThrows(com.auraboot.framework.meta.exception.MetaRecordNotFoundException.class,
                ()->projection.getById("pr_supplier_pcba_contact",foreignContact));
        var tx=new TransactionTemplate(transactions);
        var result=tx.execute(status->scope(bind,()->bindNativeMembership(db,(l,p)->phone)));
        assertEquals(localSupplier,result.get("supplierPid"));assertEquals(localContact,result.get("contactPid"));
        var before=scope(bind,()->db.getById("qo_procurement_membership_common",result.get("recordPid").toString()));
        var rejection=assertThrows(PluginCommandRejectionException.class,()->tx.execute(status->scope(bind,()->bindNativeMembership(db,(l,p)->foreignOnly))));
        assertEquals("qo_pr.error.phone_not_registered",rejection.messageKey());
        assertEquals(before,scope(bind,()->db.getById("qo_procurement_membership_common",result.get("recordPid").toString())));
        assertEquals(1,scope(bind,()->nativeRows("qo_procurement_membership_common","qo_pr_membership_user_pid",actor)).size());
    }
    private String cloneForeignDirectoryFixture(String table,String original,Long foreignTenant,Map<String,Object> values) throws Exception {
        assertTrue(Set.of("mt_pr_supplier_common","mt_pr_supplier_pcba_contact").contains(table));
        assertNotEquals(tenantId,foreignTenant);
        var row=new HashMap<>(values);String pid=com.auraboot.framework.common.util.UlidGenerator.generate();
        row.put("id",com.baomidou.mybatisplus.core.toolkit.IdWorker.getId());row.put("pid",pid);row.put("tenant_id",foreignTenant);
        String sql="INSERT INTO "+table+" SELECT (jsonb_populate_record(NULL::"+table+",to_jsonb(src)||?::jsonb)).* FROM "+table+" src WHERE src.pid=? AND src.tenant_id=?";
        try(var c=source.getConnection();var q=c.prepareStatement(sql)) {
            q.setString(1,json.writeValueAsString(row));q.setString(2,original);q.setLong(3,tenantId);
            assertEquals(1,q.executeUpdate());
        }
        return pid;
    }

    @Test void nativeImportRejectsProductDiscontinuedAfterMaterialResolution() throws Exception {
        var resolved=new java.util.concurrent.CountDownLatch(1);var discontinued=new java.util.concurrent.CountDownLatch(1);
        var first=new java.util.concurrent.atomic.AtomicBoolean(true);
        var statuses=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var initialStatus=new java.util.concurrent.atomic.AtomicReference<String>();
        var finalStatus=new java.util.concurrent.atomic.AtomicReference<String>();
        DataAccessor observed=(DataAccessor)java.lang.reflect.Proxy.newProxyInstance(DataAccessor.class.getClassLoader(),new Class<?>[]{DataAccessor.class},
                (proxy,method,args)->{Object result;try {result=method.invoke(db,args);}catch(InvocationTargetException error){throw error.getCause();}
                    if(method.getName().equals("getById") && args[0].equals("prod_product") && args[1].equals(product)) {
                        var snapshot=new LinkedHashMap<>((Map<String,Object>)result);
                        statuses.add(snapshot.get("prod_status").toString());
                        if(first.compareAndSet(true,false)) {
                            initialStatus.set(snapshot.get("prod_status").toString());resolved.countDown();
                            awaitNativeLatch(discontinued,"product lifecycle change must commit after material resolution read");
                        } else finalStatus.set(snapshot.get("prod_status").toString());
                        return snapshot;
                    }
                    return result;});
        var selected=importContext(task,observed);var taskBefore=scope(SYNC,()->db.getById("s2p_sourcing_event",task));
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var importing=pool.submit(()->nativeActorThread(()->new TransactionTemplate(transactions).execute(status->scope(SYNC,()->syncWith(selected,"100","11")))));
            awaitNativeLatch(resolved,"material resolver must receive the original active product snapshot");
            new TransactionTemplate(transactions).execute(status->scope("prod:discontinue_product",()->db.update("prod_product",product,Map.of("prod_status","discontinued"))));
            discontinued.countDown();
            try {
                importing.get(20,java.util.concurrent.TimeUnit.SECONDS);
                fail("Import accepted discontinued product: snapshots="+statuses);
            } catch(java.util.concurrent.ExecutionException failure) {
                assertEquals("SOURCE_PRODUCT_UNAVAILABLE",assertInstanceOf(SecurityException.class,failure.getCause()).getMessage());
            }
            assertEquals("active",initialStatus.get());assertEquals("discontinued",finalStatus.get());
            scope(SYNC,()->{assertTrue(rows().isEmpty());assertEquals(1,references());
                assertEquals(taskBefore,db.getById("s2p_sourcing_event",task));
                assertEquals("discontinued",db.getById("prod_product",product).get("prod_status"));return null;});
        }finally{discontinued.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
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

    private Object allocateNative(Map<String,Object> payload) {return allocateNativeWith(db,payload);}
    private Object allocateNativeWith(DataAccessor accessor,Map<String,Object> payload) {
        try {
            Class<?> type=plugin.loadClass("com.auraboot.quote.engine.procurement.ProcurementAllocationService");
            return type.getMethod("allocate",String.class,Map.class)
                    .invoke(type.getConstructor(DataAccessor.class).newInstance(accessor),task,payload);
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

    private static void awaitNativeLatch(java.util.concurrent.CountDownLatch latch,String reason) {
        try {assertTrue(latch.await(10,java.util.concurrent.TimeUnit.SECONDS),reason);}
        catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(reason,error);}
    }
    /** Observes only the real native quotation CAS; every operation delegates unchanged to the host. */
    private DataAccessor quoteLockObserver(Runnable before,Runnable after) {
        return (DataAccessor)java.lang.reflect.Proxy.newProxyInstance(DataAccessor.class.getClassLoader(),new Class<?>[]{DataAccessor.class},
                (proxy,method,args)->{
                    boolean quoteLock=method.getName().equals("compareAndSetBatch") && "s2p_bid".equals(args[0]);
                    if(quoteLock) before.run();
                    Object result;
                    try {result=method.invoke(db,args);}
                    catch(InvocationTargetException error){throw error.getCause();}
                    if(quoteLock) after.run();
                    return result;
                });
    }
    private <T>T nativeActorThread(Supplier<T> work) {
        MetaContext.setContext(tenantId,actorId,actor,"Host concurrency fixture");
        try {return work.get();}finally{MetaContext.clear();}
    }
    @Test void nativeSourceSyncWinsConcurrentStaleAllocationWithoutLeavingReservations() throws Exception {
        var fixture=savedNativeQuote();String quote=fixture.quote(),sourcePid=fixture.source();
        String submit="qo_procurement_quote_common:submit",allocate="qo_procurement_task_common:allocate";
        var tx=new TransactionTemplate(transactions);
        var formal=(Map<?,?>)tx.execute(status->scope(submit,()->quoteAt(fixture.clock(),"submit",quote,Map.of("revision",1))));
        scope("qo_procurement_task_common:dispatch",()->{openFixtureTask();return null;});
        var taskBefore=scope(allocate,()->db.getById("s2p_sourcing_event",task));
        var snapshot=scope(submit,()->db.getById("qo_procurement_submission_common",(String)formal.get("submissionPid")));
        var request=Map.<String,Object>of("sourceDemands",List.of(Map.of("sourcePid",sourcePid,"sourceRevision",0,"quantity","300.000001")),
                "allocations",List.of(Map.of("quotePid",quote,"sourcePid",sourcePid,"submissionPid",formal.get("submissionPid"),"quantity","300.000001")));
        var sourceHeld=new java.util.concurrent.CountDownLatch(1);
        var allocationAttempted=new java.util.concurrent.CountDownLatch(1);
        var sourceAccessor=quoteLockObserver(()->{},()->{sourceHeld.countDown();awaitNativeLatch(allocationAttempted,"allocation must reach the held native quote lock");});
        var allocationAccessor=quoteLockObserver(allocationAttempted::countDown,()->{});
        var settings=new HashMap<String,Object>(context.settings());settings.put(CommandHandlerExtension.DATA_ACCESSOR_KEY,sourceAccessor);
        var syncContext=new CommandHandlerExtension.CommandContext(tenantId,context.pluginId(),context.namespace(),SYNC,
                context.modelCode(),task,context.payload(),settings,false);
        response=row("100","11").replace(",1500,1400,400,300,1000,",",1500,1399,400,300,1000,");
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var syncFuture=pool.submit(()->nativeActorThread(()->new TransactionTemplate(transactions).execute(status->scope(SYNC,()->syncWith(syncContext,"100","11")))));
            awaitNativeLatch(sourceHeld,"trusted sync must hold the real quote CAS before allocation starts");
            var allocationFuture=pool.submit(()->nativeActorThread(()->new TransactionTemplate(transactions).execute(status->scope(allocate,()->allocateNativeWith(allocationAccessor,request)))));
            assertEquals(List.of("changed"),syncFuture.get(20,java.util.concurrent.TimeUnit.SECONDS));
            var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->allocationFuture.get(20,java.util.concurrent.TimeUnit.SECONDS));
            var conflict=assertInstanceOf(com.auraboot.framework.meta.exception.MetaServiceException.class,failure.getCause());
            assertEquals("CAS batch conflict: no partial batch may commit",conflict.getMessage());
        } finally {allocationAttempted.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
        scope(allocate,()->{
            assertEquals(taskBefore,db.getById("s2p_sourcing_event",task));
            assertNativeQuantity("pr_purchase_request",sourcePid,"qo_pr_source_reserved_qty","0");
            assertNativeQuantity("pr_purchase_request",sourcePid,"qo_pr_source_approved_qty","1399");
            assertNativeQuantity("pr_purchase_request",sourcePid,"qo_pr_source_revision","1");
            assertEquals("invalidated",db.getById("s2p_bid",quote).get("s2p_bid_status"));
            assertNativeQuantity("s2p_bid",quote,"qo_pr_quote_revision","3");
            assertTrue(nativeRows("qo_procurement_allocation_common","qo_pr_allocation_task_pid",task).isEmpty());
            assertTrue(nativeRows("qo_procurement_execution_common","qo_pr_execution_task_pid",task).isEmpty());
            assertEquals(snapshot,db.getById("qo_procurement_submission_common",(String)formal.get("submissionPid")));
            assertEquals(1,nativeRows("qo_procurement_submission_common","qo_pr_submission_quote_pid",quote).size());return null;
        });
    }

    private <T>T otherNativeMember(Long userId,String userPid,String writer,Supplier<T> work) {
        MetaContext.setContext(tenantId,userId,userPid,"Second supplier fixture");
        try {return MetaContext.runWithCommandPermitPlan("ALL",userId,null,null,
                ()->MetaContext.runWithAuthorizedCommandCode(writer,work));}
        finally {MetaContext.setContext(tenantId,actorId,actor,"Host fixture");}
    }
    private record FormalOffer(String quote,String submission) {}
    private FormalOffer secondNativeSupplierOffer(QuoteFixture fixture) {
        String directory="pr_supplier_common:sync_directory";
        var user=users.signUp("second-"+provider+"@example.invalid","host-fixture-password-123","Second supplier","second-"+provider);
        members.addMember(user.getId(),tenantId,"active");
        String supplier=scope(directory,()->(String)db.create("pr_supplier_common",Map.of("pr_supplier_code","second-"+provider,
                "pr_supplier_name","Second lock-order supplier","pr_supplier_status","enabled","qo_pr_supplier_account_id",provider)).get("pid"));
        String contact=scope(directory,()->(String)db.create("pr_supplier_pcba_contact",Map.of(
                "pr_spc_supplier_id",supplier,"pr_spc_name","Second contact","qo_pr_contact_state","active")).get("pid"));
        // Synthetic already-bound membership, separate actual account; no enrollment-policy claim.
        scope("qo_procurement_membership_common:bind_verified_phone",()->db.create("qo_procurement_membership_common",Map.of(
                "qo_pr_membership_key","second-"+provider,"qo_pr_membership_user_pid",user.getPid(),"qo_pr_membership_supplier_pid",supplier,
                "qo_pr_membership_contact_pid",contact,"qo_pr_membership_state","active")));
        String quote=scope("qo_procurement_task_common:dispatch",()->(String)createProductEntity("quote",Map.of("key","second-"+provider,
                "task_pid",task,"supplier_pid",supplier,"state","draft","revision",0,"cutoff_at",fixture.cutoff().toString(),
                "received_at",fixture.cutoff().minusSeconds(1).toString(),"source_pids",List.of(fixture.source()))).get("pid"));
        var line=Map.<String,Object>of("sourcePid",fixture.source(),"sourceRevision",0,"unitPrice","2.000001","currency","CNY",
                "unit","PCS","taxRate","0.13","taxIncluded",true,"availableQty","600","deliveryDate","2026-12-01");
        var tx=new TransactionTemplate(transactions);
        tx.execute(status->otherNativeMember(user.getId(),user.getPid(),"qo_procurement_quote_common:save_draft",()->invokeServiceAt(
                fixture.clock(),"ProcurementQuoteService","execute",new Class<?>[]{String.class,String.class,String.class,Map.class},
                "save_draft",quote,user.getPid(),Map.of("revision",0,"lines",List.of(line),"paymentTerms","Net 30"))));
        var formal=(Map<?,?>)tx.execute(status->otherNativeMember(user.getId(),user.getPid(),"qo_procurement_quote_common:submit",()->invokeServiceAt(
                fixture.clock(),"ProcurementQuoteService","execute",new Class<?>[]{String.class,String.class,String.class,Map.class},
                "submit",quote,user.getPid(),Map.of("revision",1))));
        return new FormalOffer(quote,(String)formal.get("submissionPid"));
    }
    @Test void nativeMultipleSupplierAllocationLocksQuotesInCanonicalOrderDespiteReverseSelection() {
        var fixture=savedNativeQuote();var tx=new TransactionTemplate(transactions);
        var first=(Map<?,?>)tx.execute(status->scope("qo_procurement_quote_common:submit",
                ()->quoteAt(fixture.clock(),"submit",fixture.quote(),Map.of("revision",1))));
        var second=secondNativeSupplierOffer(fixture);
        scope("qo_procurement_task_common:dispatch",()->{openFixtureTask();return null;});
        var offers=new TreeMap<String,String>();offers.put(fixture.quote(),(String)first.get("submissionPid"));offers.put(second.quote(),second.submission());
        assertEquals(2,offers.size());
        var reverse=offers.descendingMap().entrySet().stream().map(e->Map.<String,Object>of("quotePid",e.getKey(),
                "sourcePid",fixture.source(),"submissionPid",e.getValue(),"quantity","300.000001")).toList();
        var request=Map.<String,Object>of("sourceDemands",List.of(Map.of("sourcePid",fixture.source(),"sourceRevision",0,"quantity","600.000002")),"allocations",reverse);
        var locked=new ArrayList<String>();
        DataAccessor observed=(DataAccessor)java.lang.reflect.Proxy.newProxyInstance(DataAccessor.class.getClassLoader(),new Class<?>[]{DataAccessor.class},
                (proxy,method,args)->{
                    Object result;
                    try {result=method.invoke(db,args);}catch(InvocationTargetException error){throw error.getCause();}
                    if(method.getName().equals("compareAndSetBatch") && "s2p_bid".equals(args[0]))
                        for(Object update:(List<?>)args[2]) locked.add(((CompareAndSetUpdate)update).recordId());
                    return result;
                });
        String allocate="qo_procurement_task_common:allocate";
        tx.execute(status->scope(allocate,()->allocateNativeWith(observed,request)));
        assertEquals(new ArrayList<>(offers.keySet()),locked,"actual native quote locks must follow source synchronization order");
        var allocated=scope(allocate,()->nativeRows("qo_procurement_allocation_common","qo_pr_allocation_task_pid",task));
        var executed=scope(allocate,()->nativeRows("qo_procurement_execution_common","qo_pr_execution_task_pid",task));
        assertEquals(2,allocated.size());assertEquals(2,executed.size());
        assertEquals(2,allocated.stream().map(a->a.get("qo_pr_allocation_supplier_pid")).distinct().count());
        var identities=allocated.stream().map(a->a.get("qo_pr_allocation_submission_pid")).collect(java.util.stream.Collectors.toSet());
        assertEquals(new HashSet<>(offers.values()),identities);
        scope(allocate,()->{assertNativeQuantity("pr_purchase_request",fixture.source(),"qo_pr_source_reserved_qty","600.000002");
            for(var allocation:allocated) assertNativeQuantity("qo_procurement_allocation_common",(String)allocation.get("pid"),"qo_pr_allocation_quantity","300.000001");return null;});
        tx.execute(status->scope(allocate,()->allocateNative(request)));
        scope(allocate,()->{
            assertEquals(allocated,nativeRows("qo_procurement_allocation_common","qo_pr_allocation_task_pid",task));
            assertEquals(executed,nativeRows("qo_procurement_execution_common","qo_pr_execution_task_pid",task));
            assertNativeQuantity("pr_purchase_request",fixture.source(),"qo_pr_source_reserved_qty","600.000002");return null;
        });
    }

    @Test void nativeMultipleQuoteSyncAndReverseAllocationCannotInvertLocks() throws Exception {
        var fixture=savedNativeQuote();var tx=new TransactionTemplate(transactions);
        var first=(Map<?,?>)tx.execute(status->scope("qo_procurement_quote_common:submit",()->quoteAt(fixture.clock(),"submit",fixture.quote(),Map.of("revision",1))));
        var second=secondNativeSupplierOffer(fixture);
        scope("qo_procurement_task_common:dispatch",()->{openFixtureTask();return null;});
        var beforeTask=scope(SYNC,()->db.getById("s2p_sourcing_event",task));
        var offers=new TreeMap<String,String>();offers.put(fixture.quote(),(String)first.get("submissionPid"));offers.put(second.quote(),second.submission());
        var original=new HashMap<String,Map<String,Object>>();
        scope(SYNC,()->{offers.values().forEach(pid->original.put(pid,db.getById("qo_procurement_submission_common",pid)));return null;});
        var selected=offers.descendingMap().entrySet().stream().map(e->Map.<String,Object>of("quotePid",e.getKey(),"sourcePid",fixture.source(),"submissionPid",e.getValue(),"quantity","300.000001")).toList();
        var request=Map.<String,Object>of("sourceDemands",List.of(Map.of("sourcePid",fixture.source(),"sourceRevision",0,"quantity","600.000002")),"allocations",selected);
        var sourceHeld=new java.util.concurrent.CountDownLatch(1);var allocationReady=new java.util.concurrent.CountDownLatch(1);
        var syncAccessor=quoteLockObserver(()->{},()->{sourceHeld.countDown();awaitNativeLatch(allocationReady,"allocation must reach the competing quote");});
        DataAccessor allocationAccessor=(DataAccessor)java.lang.reflect.Proxy.newProxyInstance(DataAccessor.class.getClassLoader(),new Class<?>[]{DataAccessor.class},
                (proxy,method,args)->{
                    boolean locking=method.getName().equals("compareAndSetBatch") && "s2p_bid".equals(args[0]);
                    String quote=locking?((CompareAndSetUpdate)((List<?>)args[2]).getFirst()).recordId():"";
                    // Canonical first lock may wait on sync; an inverted first lock must actually be held.
                    if(locking && quote.equals(offers.firstKey())) allocationReady.countDown();
                    Object result;try {result=method.invoke(db,args);}catch(InvocationTargetException error){throw error.getCause();}
                    if(locking && quote.equals(offers.lastKey())) allocationReady.countDown();
                    return result;
                });
        var settings=new HashMap<String,Object>(context.settings());settings.put(CommandHandlerExtension.DATA_ACCESSOR_KEY,syncAccessor);
        var syncContext=new CommandHandlerExtension.CommandContext(tenantId,context.pluginId(),context.namespace(),SYNC,context.modelCode(),task,context.payload(),settings,false);
        response=row("100","11").replace(",1500,1400,400,300,1000,",",1500,1399,400,300,1000,");
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var sync=pool.submit(()->nativeActorThread(()->new TransactionTemplate(transactions).execute(status->scope(SYNC,()->syncWith(syncContext,"100","11")))));
            awaitNativeLatch(sourceHeld,"sync must hold its first real quote lock");
            var allocate=pool.submit(()->nativeActorThread(()->new TransactionTemplate(transactions).execute(status->scope("qo_procurement_task_common:allocate",()->allocateNativeWith(allocationAccessor,request)))));
            assertEquals(List.of("changed"),sync.get(20,java.util.concurrent.TimeUnit.SECONDS));
            var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->allocate.get(20,java.util.concurrent.TimeUnit.SECONDS));
            var conflict=assertInstanceOf(com.auraboot.framework.meta.exception.MetaServiceException.class,failure.getCause());
            assertEquals("CAS batch conflict: no partial batch may commit",conflict.getMessage());
        } finally {allocationReady.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
        scope(SYNC,()->{
            assertEquals(beforeTask,db.getById("s2p_sourcing_event",task));
            for(String quote:offers.keySet()) {assertEquals("invalidated",db.getById("s2p_bid",quote).get("s2p_bid_status"));assertNativeQuantity("s2p_bid",quote,"qo_pr_quote_revision","3");}
            assertNativeQuantity("pr_purchase_request",fixture.source(),"qo_pr_source_reserved_qty","0");
            assertNativeQuantity("pr_purchase_request",fixture.source(),"qo_pr_source_approved_qty","1399");
            assertTrue(nativeRows("qo_procurement_allocation_common","qo_pr_allocation_task_pid",task).isEmpty());
            assertTrue(nativeRows("qo_procurement_execution_common","qo_pr_execution_task_pid",task).isEmpty());
            original.forEach((pid,row)->assertEquals(row,db.getById("qo_procurement_submission_common",pid)));return null;
        });
    }

    private record BatchFixture(QuoteFixture first,String secondSource,String secondQuote,List<String[]> identities) {}
    private BatchFixture savedNativeBatch() {
        var fixture=savedNativeQuote();var tx=new TransactionTemplate(transactions);
        response=row("100","12");tx.execute(status->scope(SYNC,()->sync("100","12")));
        String secondSource=rows().stream().filter(r->!fixture.source().equals(r.get("pid"))).map(r->(String)r.get("pid")).findFirst().orElseThrow();
        String supplier=scope(SYNC,()->(String)db.getById("s2p_bid",fixture.quote()).get("s2p_bid_supplier_id"));
        String secondQuote=scope("qo_procurement_task_common:dispatch",()->(String)createProductEntity("quote",Map.of(
                "key","batch-"+provider,"task_pid",task,"supplier_pid",supplier,"state","draft","revision",0,
                "cutoff_at",fixture.cutoff().toString(),"source_pids",List.of(secondSource))).get("pid"));
        var quoteIds=new TreeSet<String>(List.of(fixture.quote(),secondQuote));
        assertEquals(2,quoteIds.size());
        var identities=new ArrayList<String[]>();
        identities.add(new String[]{"100",quoteIds.last().equals(fixture.quote())?"11":"12"});
        identities.add(new String[]{"100",quoteIds.first().equals(fixture.quote())?"11":"12"});
        String changed=row("100","11").replace(",1500,1400,400,300,1000,",",1500,1399,400,300,1000,");
        response="["+changed.substring(1,changed.length()-1)+","+row("100","12").substring(1,row("100","12").length()-1)+"]";
        return new BatchFixture(fixture,secondSource,secondQuote,identities);
    }
    @Test void nativeBatchSourceSyncLocksAllQuotesBeforeAnySourceInCanonicalOrder() {
        var batch=savedNativeBatch();var fixture=batch.first();var secondSource=batch.secondSource();var secondQuote=batch.secondQuote();
        var identities=batch.identities();var quoteIds=new TreeSet<String>(List.of(fixture.quote(),secondQuote));
        var tx=new TransactionTemplate(transactions);
        var locks=new ArrayList<String>();
        DataAccessor observed=(DataAccessor)java.lang.reflect.Proxy.newProxyInstance(DataAccessor.class.getClassLoader(),new Class<?>[]{DataAccessor.class},
                (proxy,method,args)->{
                    Object result;try {result=method.invoke(db,args);}catch(InvocationTargetException error){throw error.getCause();}
                    if(method.getName().equals("compareAndSetBatch") && ("s2p_bid".equals(args[0]) || "pr_purchase_request".equals(args[0])))
                        for(Object update:(List<?>)args[2]) locks.add(args[0]+":"+((CompareAndSetUpdate)update).recordId());
                    return result;
                });
        var settings=new HashMap<String,Object>(context.settings());settings.put(CommandHandlerExtension.DATA_ACCESSOR_KEY,observed);
        var selectedContext=new CommandHandlerExtension.CommandContext(tenantId,context.pluginId(),context.namespace(),SYNC,context.modelCode(),task,context.payload(),settings,false);
        assertEquals(identities.stream().map(id->id[1].equals("11")?"changed":"unchanged").toList(),
                tx.execute(status->scope(SYNC,()->syncSelectedWith(selectedContext,identities))),"results must retain selected identity order");
        assertEquals(quoteIds.stream().map(q->"s2p_bid:"+q).toList(),locks.subList(0,2),"batch must lock all quotations before touching sources");
        var sourceIds=new TreeSet<String>(List.of(fixture.source(),secondSource));
        assertEquals(sourceIds.stream().map(s->"pr_purchase_request:"+s).toList(),locks.subList(2,4));
        scope(SYNC,()->{for(String source:sourceIds) {assertNativeQuantity("pr_purchase_request",source,"qo_pr_source_approved_qty",source.equals(fixture.source())?"1399":"1400");
            assertNativeQuantity("pr_purchase_request",source,"qo_pr_source_reserved_qty","0");}
            assertEquals("invalidated",db.getById("s2p_bid",fixture.quote()).get("s2p_bid_status"));
            assertEquals("draft",db.getById("s2p_bid",secondQuote).get("s2p_bid_status"));return null;});
    }

    private CommandHandlerExtension.CommandContext importContext(String selectedTask,DataAccessor accessor) {
        var settings=new HashMap<String,Object>(context.settings());settings.put(CommandHandlerExtension.DATA_ACCESSOR_KEY,accessor);
        return new CommandHandlerExtension.CommandContext(tenantId,context.pluginId(),context.namespace(),SYNC,context.modelCode(),selectedTask,context.payload(),settings,false);
    }
    private DataAccessor taskLockObserver(Runnable before,Runnable after) {
        return (DataAccessor)java.lang.reflect.Proxy.newProxyInstance(DataAccessor.class.getClassLoader(),new Class<?>[]{DataAccessor.class},
                (proxy,method,args)->{
                    boolean locking=method.getName().equals("compareAndSetBatch") && "s2p_sourcing_event".equals(args[0]);
                    if(locking) before.run();
                    Object result;try {result=method.invoke(db,args);}catch(InvocationTargetException error){throw error.getCause();}
                    if(locking) after.run();return result;
                });
    }
    @Test void nativeConcurrentSourceBatchesRejectStaleFactsWithoutPartialWrites() throws Exception {
        var batch=savedNativeBatch();var held=new java.util.concurrent.CountDownLatch(1);var attempted=new java.util.concurrent.CountDownLatch(1);
        var first=importContext(task,taskLockObserver(()->{},()->{held.countDown();awaitNativeLatch(attempted,"second batch must attempt native task CAS");}));
        var second=importContext(task,taskLockObserver(attempted::countDown,()->{}));
        var opposite=new ArrayList<>(batch.identities());Collections.reverse(opposite);
        var committed=new HashMap<String,Map<String,Object>>();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var accepted=pool.submit(()->nativeActorThread(()->new TransactionTemplate(transactions).execute(status->scope(SYNC,()->{
                Object result=syncSelectedWith(first,batch.identities());
                committed.put("task",db.getById("s2p_sourcing_event",task));
                committed.put("source1",db.getById("pr_purchase_request",batch.first().source()));
                committed.put("source2",db.getById("pr_purchase_request",batch.secondSource()));
                committed.put("quote1",db.getById("s2p_bid",batch.first().quote()));
                committed.put("quote2",db.getById("s2p_bid",batch.secondQuote()));return result;
            }))));
            awaitNativeLatch(held,"first batch must hold its actual task lock");
            var rejected=pool.submit(()->nativeActorThread(()->new TransactionTemplate(transactions).execute(status->scope(SYNC,()->syncSelectedWith(second,opposite)))));
            assertEquals(batch.identities().stream().map(id->id[1].equals("11")?"changed":"unchanged").toList(),accepted.get(20,java.util.concurrent.TimeUnit.SECONDS));
            var error=assertThrows(java.util.concurrent.ExecutionException.class,()->rejected.get(20,java.util.concurrent.TimeUnit.SECONDS));
            var conflict=assertInstanceOf(com.auraboot.framework.meta.exception.MetaServiceException.class,error.getCause());
            assertEquals("CAS batch conflict: no partial batch may commit",conflict.getMessage());
        } finally {attempted.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
        scope(SYNC,()->{
            assertEquals(committed.get("task"),db.getById("s2p_sourcing_event",task));
            assertEquals(committed.get("source1"),db.getById("pr_purchase_request",batch.first().source()));
            assertEquals(committed.get("source2"),db.getById("pr_purchase_request",batch.secondSource()));
            assertEquals(committed.get("quote1"),db.getById("s2p_bid",batch.first().quote()));
            assertEquals(committed.get("quote2"),db.getById("s2p_bid",batch.secondQuote()));
            assertEquals(2,rows().size());assertEquals(3,references());
            assertNativeQuantity("pr_purchase_request",batch.first().source(),"qo_pr_source_revision","1");
            assertNativeQuantity("pr_purchase_request",batch.secondSource(),"qo_pr_source_revision","0");
            assertNativeQuantity("pr_purchase_request",batch.first().source(),"qo_pr_source_approved_qty","1399");
            assertNativeQuantity("pr_purchase_request",batch.secondSource(),"qo_pr_source_approved_qty","1400");
            assertNativeQuantity("pr_purchase_request",batch.first().source(),"qo_pr_source_reserved_qty","0");
            assertNativeQuantity("pr_purchase_request",batch.secondSource(),"qo_pr_source_reserved_qty","0");
            var history=nativeRows("qo_procurement_history_common","qo_pr_history_target_pid",batch.first().quote());
            assertEquals(2,history.size());
            assertEquals(1,history.stream().filter(h->"source_changed".equals(h.get("qo_pr_history_action"))).count());
            assertTrue(nativeRows("qo_procurement_history_common","qo_pr_history_target_pid",batch.secondQuote()).isEmpty());
            assertTrue(nativeRows("qo_procurement_allocation_common","qo_pr_allocation_task_pid",task).isEmpty());
            assertTrue(nativeRows("qo_procurement_execution_common","qo_pr_execution_task_pid",task).isEmpty());return null;
        });
        assertEquals(List.of("unchanged","unchanged"),new TransactionTemplate(transactions).execute(status->scope(SYNC,()->syncSelectedWith(context,opposite))));
        scope(SYNC,()->{assertEquals(2,rows().size());assertEquals(3,references());
            var history=nativeRows("qo_procurement_history_common","qo_pr_history_target_pid",batch.first().quote());
            assertEquals(2,history.size());
            assertEquals(1,history.stream().filter(h->"source_changed".equals(h.get("qo_pr_history_action"))).count());return null;});
    }

    @Test void nativeCrossInquiryBatchKeepsOriginalSourceAssignmentAndLocksBothTasks() {
        var batch=savedNativeBatch();String connection=scope(SYNC,()->(String)db.getById("s2p_sourcing_event",task).get("qo_pr_task_connection_pid"));
        String newTask=scope("qo_procurement_task_common:create",()->{
            try {Class<?> type=plugin.loadClass("com.auraboot.quote.engine.procurement.ProcurementTaskService");
                var created=(Map<?,?>)type.getMethod("create",Object.class,String.class,Map.class).invoke(type.getConstructor(DataAccessor.class).newInstance(db),tenantId,actor,
                        Map.of("title","Duplicate import "+provider,"connectionPid",connection));return (String)created.get("recordPid");
            }catch(ReflectiveOperationException error){throw unwrap(error);}
        });
        var locked=new ArrayList<String>();
        DataAccessor observed=(DataAccessor)java.lang.reflect.Proxy.newProxyInstance(DataAccessor.class.getClassLoader(),new Class<?>[]{DataAccessor.class},
                (proxy,method,args)->{Object result;try {result=method.invoke(db,args);}catch(InvocationTargetException error){throw error.getCause();}
                    if(method.getName().equals("compareAndSetBatch")) for(Object update:(List<?>)args[2]) locked.add(args[0]+":"+((CompareAndSetUpdate)update).recordId());return result;});
        var selected=importContext(newTask,observed);
        assertEquals(batch.identities().stream().map(id->id[1].equals("11")?"changed":"unchanged").toList(),
                new TransactionTemplate(transactions).execute(status->scope(SYNC,()->syncSelectedWith(selected,batch.identities()))));
        var taskIds=new TreeSet<String>(List.of(task,newTask));
        assertEquals(taskIds.stream().map(t->"s2p_sourcing_event:"+t).toList(),locked.subList(0,2));
        var quoteIds=new TreeSet<String>(List.of(batch.first().quote(),batch.secondQuote()));
        assertEquals(quoteIds.stream().map(q->"s2p_bid:"+q).toList(),locked.subList(2,4));
        scope(SYNC,()->{assertEquals(2,rows().size());assertEquals(3,references());
            assertTrue(nativeRows("pr_purchase_request","qo_pr_source_task_pid",newTask).isEmpty());
            assertTrue(nativeRows("s2p_bid","s2p_bid_sourcing_event_id",newTask).isEmpty());
            for(var source:rows()) assertEquals(task,source.get("qo_pr_source_task_pid"));
            assertEquals("invalidated",db.getById("s2p_bid",batch.first().quote()).get("s2p_bid_status"));
            assertEquals("draft",db.getById("s2p_bid",batch.secondQuote()).get("s2p_bid_status"));return null;});
    }

    @Test void nativeBatchRejectsIdentityCreatedInAnotherInquiryAfterScopeRead() throws Exception {
        String connection=scope(SYNC,()->(String)db.getById("s2p_sourcing_event",task).get("qo_pr_task_connection_pid"));
        String competingTask=scope("qo_procurement_task_common:create",()->{
            try {Class<?> type=plugin.loadClass("com.auraboot.quote.engine.procurement.ProcurementTaskService");
                var created=(Map<?,?>)type.getMethod("create",Object.class,String.class,Map.class).invoke(type.getConstructor(DataAccessor.class).newInstance(db),tenantId,actor,
                        Map.of("title","Concurrent identity "+provider,"connectionPid",connection));return (String)created.get("recordPid");
            }catch(ReflectiveOperationException error){throw unwrap(error);}
        });
        var scoped=new java.util.concurrent.CountDownLatch(1);var inserted=new java.util.concurrent.CountDownLatch(1);
        var taskObserved=taskLockObserver(()->{scoped.countDown();awaitNativeLatch(inserted,"other inquiry must commit after batch scope read");},()->{});
        var partialSource=new java.util.concurrent.atomic.AtomicReference<String>();
        DataAccessor observed=(DataAccessor)java.lang.reflect.Proxy.newProxyInstance(DataAccessor.class.getClassLoader(),new Class<?>[]{DataAccessor.class},
                (proxy,method,args)->{Object result;try {result=method.invoke(taskObserved,args);}catch(InvocationTargetException error){throw error.getCause();}
                    if(method.getName().equals("tryCreate") && args[0].equals("pr_purchase_request"))
                        ((Optional<?>)result).ifPresent(row->partialSource.set(((Map<?,?>)row).get("pid").toString()));
                    return result;});
        response=row("100","12").replace("]]", "],")+row("100","11").substring(1);
        var selected=List.<String[]>of(new String[]{"100","12"},new String[]{"100","11"});
        var batchContext=importContext(task,observed);var competingContext=importContext(competingTask,db);
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var batch=pool.submit(()->nativeActorThread(()->new TransactionTemplate(transactions).execute(status->scope(SYNC,()->syncSelectedWith(batchContext,selected)))));
            awaitNativeLatch(scoped,"batch must complete missing-identity preflight before its first task lock");
            response=row("100","11");
            assertEquals(List.of("added"),new TransactionTemplate(transactions).execute(status->scope(SYNC,()->syncWith(competingContext,"100","11"))));
            var winner=scope(SYNC,()->nativeRows("pr_purchase_request","qo_pr_source_task_pid",competingTask));assertEquals(1,winner.size());
            var originalTask=scope(SYNC,()->db.getById("s2p_sourcing_event",task));
            var winnerTask=scope(SYNC,()->db.getById("s2p_sourcing_event",competingTask));
            inserted.countDown();
            var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->batch.get(20,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("SOURCE_SYNC_CONFLICT",assertInstanceOf(IllegalStateException.class,failure.getCause()).getMessage());
            assertNotNull(partialSource.get(),"first batch source must actually be created before the late conflict");
            scope(SYNC,()->{assertThrows(com.auraboot.framework.meta.exception.MetaRecordNotFoundException.class,
                    ()->db.getById("pr_purchase_request",partialSource.get()));assertTrue(rows().isEmpty());assertEquals(2,references());
                assertEquals(winner,nativeRows("pr_purchase_request","qo_pr_source_task_pid",competingTask));
                assertEquals(originalTask,db.getById("s2p_sourcing_event",task));assertEquals(winnerTask,db.getById("s2p_sourcing_event",competingTask));return null;});
            response=row("100","12").replace("]]", "],")+row("100","11").substring(1);
            assertEquals(List.of("added","unchanged"),new TransactionTemplate(transactions).execute(status->scope(SYNC,()->syncSelectedWith(context,selected))));
            scope(SYNC,()->{assertEquals(1,rows().size());assertEquals(3,references());
                var retained=nativeRows("pr_purchase_request","qo_pr_source_task_pid",competingTask);assertEquals(1,retained.size());
                assertEquals(winner.getFirst().get("pid"),retained.getFirst().get("pid"));assertEquals(competingTask,retained.getFirst().get("qo_pr_source_task_pid"));return null;});
        }finally{inserted.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
    }

    private record DispatchFixture(String supplier,java.time.Clock clock,long version) {}
    private DispatchFixture nativeDispatchFixture() {
        var tx=new TransactionTemplate(transactions);tx.execute(status->scope(SYNC,()->sync("100","11")));
        String source=(String)rows().getFirst().get("pid");
        String category=scope(SYNC,()->(String)db.create("pr_spend_category",Map.of("pr_spc_code",provider,"pr_spc_name","Native dispatch race category")).get("pid"));
        String supplier=scope("pr_supplier_common:sync_directory",()->(String)db.create("pr_supplier_common",Map.of(
                "pr_supplier_code",provider,"pr_supplier_name","Native dispatch race supplier","pr_supplier_status","enabled","qo_pr_supplier_account_id",provider)).get("pid"));
        String connection=scope(SYNC,()->(String)db.getById("s2p_sourcing_event",task).get("qo_pr_task_connection_pid"));
        tx.execute(status->scope("qo_procurement_category_policy_common:create",()->{
            try {Class<?> type=plugin.loadClass("com.auraboot.quote.engine.procurement.ProcurementCategoryPolicyService");
                return type.getMethod("createPolicy",Map.class).invoke(type.getConstructor(DataAccessor.class,Long.class).newInstance(db,tenantId),
                        Map.of("categoryPid",category,"connectionPid",connection,"supplierPids",List.of(supplier),"defaultSupplierPids",List.of(supplier)));
            }catch(ReflectiveOperationException error){throw unwrap(error);}
        }));
        var clock=java.time.Clock.fixed(java.time.Instant.parse("2026-11-15T07:59:59Z"),java.time.ZoneOffset.UTC);
        tx.execute(status->scope("qo_procurement_task_common:classify_source",()->{
            try {Class<?> type=plugin.loadClass("com.auraboot.quote.engine.procurement.ProcurementPlanningService");
                long version=new BigDecimal(db.getById("s2p_sourcing_event",task).get("row_version").toString()).longValueExact();
                return type.getMethod("classify",String.class,Long.class,Map.class).invoke(type.getConstructor(DataAccessor.class,java.time.Clock.class,Object.class).newInstance(db,clock,tenantId),
                        task,version,Map.of("sourcePid",source,"sourceRevision",0,"categoryPid",category));
            }catch(ReflectiveOperationException error){throw unwrap(error);}
        }));
        long version=scope(SYNC,()->new BigDecimal(db.getById("s2p_sourcing_event",task).get("row_version").toString()).longValueExact());
        return new DispatchFixture(supplier,clock,version);
    }
    private Object dispatchWith(DataAccessor accessor,DispatchFixture fixture) {
        try {Class<?> type=plugin.loadClass("com.auraboot.quote.engine.procurement.ProcurementDispatchService");
            return type.getMethod("dispatch",String.class,Long.class,Map.class).invoke(type.getConstructor(DataAccessor.class,java.time.Clock.class,Long.class).newInstance(accessor,fixture.clock(),tenantId),
                    task,fixture.version(),Map.of("supplierPids",List.of(fixture.supplier()),"cutoffAt","2026-11-15T08:00:00Z"));
        }catch(ReflectiveOperationException error){throw unwrap(error);}
    }
    @Test void nativeDispatchRejectsSourceAddedAfterItsSelectionRead() throws Exception {
        var fixture=nativeDispatchFixture();var selected=new java.util.concurrent.CountDownLatch(1);var added=new java.util.concurrent.CountDownLatch(1);
        DataAccessor observed=taskLockObserver(()->{selected.countDown();awaitNativeLatch(added,"source import must commit before dispatch task CAS");},()->{});
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var dispatch=pool.submit(()->nativeActorThread(()->new TransactionTemplate(transactions).execute(status->scope("qo_procurement_task_common:dispatch",()->dispatchWith(observed,fixture)))));
            awaitNativeLatch(selected,"dispatch must read its initial classified source selection");
            response=row("100","12");assertEquals(List.of("added"),new TransactionTemplate(transactions).execute(status->scope(SYNC,()->sync("100","12"))));
            var taskAfterImport=scope(SYNC,()->db.getById("s2p_sourcing_event",task));var sourcesAfterImport=rows();
            added.countDown();
            var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->dispatch.get(20,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("SOURCE_CHANGED",assertInstanceOf(IllegalStateException.class,failure.getCause()).getMessage());
            scope(SYNC,()->{assertEquals(taskAfterImport,db.getById("s2p_sourcing_event",task));assertEquals(sourcesAfterImport,rows());
                assertEquals("draft",db.getById("s2p_sourcing_event",task).get("s2p_se_status"));
                assertTrue(nativeRows("s2p_bid","s2p_bid_sourcing_event_id",task).isEmpty());assertEquals(2,rows().size());assertEquals(3,references());return null;});
        }finally{added.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
    }
    @Test void nativeSourceAppendCannotUseDraftReadBeforeDispatchCommits() throws Exception {
        var fixture=nativeDispatchFixture();var readDraft=new java.util.concurrent.CountDownLatch(1);var dispatched=new java.util.concurrent.CountDownLatch(1);
        var paused=new java.util.concurrent.atomic.AtomicBoolean();
        DataAccessor observed=(DataAccessor)java.lang.reflect.Proxy.newProxyInstance(DataAccessor.class.getClassLoader(),new Class<?>[]{DataAccessor.class},
                (proxy,method,args)->{Object result;try {result=method.invoke(db,args);}catch(InvocationTargetException error){throw error.getCause();}
                    boolean sourceSync=Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(frame->frame.getClassName().endsWith(".ProcurementSourceService") && frame.getMethodName().equals("sync"));
                    if(sourceSync && method.getName().equals("getById") && "s2p_sourcing_event".equals(args[0]) && paused.compareAndSet(false,true)) {
                        readDraft.countDown();awaitNativeLatch(dispatched,"dispatch must commit before source append resumes");}
                    return result;});
        response=row("100","12");var selected=importContext(task,observed);var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var append=pool.submit(()->nativeActorThread(()->new TransactionTemplate(transactions).execute(status->scope(SYNC,()->syncWith(selected,"100","12")))));
            awaitNativeLatch(readDraft,"trusted source sync must actually capture the old draft task");
            var quotes=(List<?>)new TransactionTemplate(transactions).execute(status->scope("qo_procurement_task_common:dispatch",()->dispatchWith(db,fixture)));
            assertEquals(1,quotes.size());var taskAfter=scope(SYNC,()->db.getById("s2p_sourcing_event",task));var sourcesAfter=rows();
            dispatched.countDown();
            var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->append.get(20,java.util.concurrent.TimeUnit.SECONDS));
            var conflict=assertInstanceOf(com.auraboot.framework.meta.exception.MetaServiceException.class,failure.getCause());
            assertEquals("CAS batch conflict: no partial batch may commit",conflict.getMessage());
            scope(SYNC,()->{assertEquals(taskAfter,db.getById("s2p_sourcing_event",task));assertEquals(sourcesAfter,rows());
                assertEquals("bidding",db.getById("s2p_sourcing_event",task).get("s2p_se_status"));assertEquals(1,rows().size());assertEquals(2,references());
                assertEquals(1,nativeRows("s2p_bid","s2p_bid_sourcing_event_id",task).size());return null;});
        }finally{dispatched.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
    }

    @Test void nativeConcurrentShipmentBatchesCannotShareStaleOrderRemainder() throws Exception {
        var fixture=shipmentFixture();String submit="qo_procurement_order_common:submit_shipment";
        var held=new java.util.concurrent.CountDownLatch(1);
        var attempted=new java.util.concurrent.CountDownLatch(1);
        var first=orderLockObserver(()->{},()->{held.countDown();awaitNativeLatch(attempted,"second shipment must attempt the held order CAS");});
        var second=orderLockObserver(attempted::countDown,()->{});
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        Object accepted;
        try {
            var winner=pool.submit(()->nativeActorThread(()->new TransactionTemplate(transactions).execute(status->scope(submit,
                    ()->submitShipmentWith(first,fixture,"shipment-concurrent-1","600")))));
            awaitNativeLatch(held,"first shipment must hold the actual native order CAS");
            var loser=pool.submit(()->nativeActorThread(()->new TransactionTemplate(transactions).execute(status->scope(submit,
                    ()->submitShipmentWith(second,fixture,"shipment-concurrent-2","600")))));
            accepted=winner.get(20,java.util.concurrent.TimeUnit.SECONDS);
            var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->loser.get(20,java.util.concurrent.TimeUnit.SECONDS));
            var conflict=assertInstanceOf(com.auraboot.framework.meta.exception.MetaServiceException.class,failure.getCause());
            assertEquals("CAS batch conflict: no partial batch may commit",conflict.getMessage());
        } finally {attempted.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
        var orderBefore=scope(submit,()->db.getById("pr_purchase_order",fixture.order()));
        var lineBefore=scope(submit,()->db.getById("pr_purchase_order_line",fixture.line()));
        var shipments=scope(submit,()->nativeRows("qo_procurement_shipment_common","qo_pr_shipment_order_pid",fixture.order()));
        assertEquals(1,shipments.size());assertEquals("open",orderBefore.get("qo_pr_order_state"));
        scope(submit,()->{assertNativeQuantity("pr_purchase_order",fixture.order(),"qo_pr_order_revision","1");
            assertNativeQuantity("pr_purchase_order_line",fixture.line(),"qo_pr_order_line_submitted_qty","600");
            assertNativeQuantity("pr_purchase_order_line",fixture.line(),"pr_pol_received_qty","0");
            var rows=nativeRows("qo_procurement_shipment_line_common","qo_pr_shipment_line_shipment_pid",(String)shipments.getFirst().get("pid"));
            assertEquals(1,rows.size());assertEquals(0,new BigDecimal("600").compareTo(new BigDecimal(rows.getFirst().get("qo_pr_shipment_line_quantity").toString())));
            return null;});
        Object replay=new TransactionTemplate(transactions).execute(status->scope(submit,()->submitShipmentWith(db,fixture,"shipment-concurrent-1","600.000")));
        assertEquals(accepted,replay);
        assertEquals(orderBefore,scope(submit,()->db.getById("pr_purchase_order",fixture.order())));
        assertEquals(lineBefore,scope(submit,()->db.getById("pr_purchase_order_line",fixture.line())));
        assertEquals(shipments,scope(submit,()->nativeRows("qo_procurement_shipment_common","qo_pr_shipment_order_pid",fixture.order())));
    }
    @Test void nativeOrderClosureWinsStaleShipmentWithoutChangingQuantities() throws Exception {
        var fixture=shipmentFixture();String sync="qo_procurement_order_common:sync_orders",submit="qo_procurement_order_common:submit_shipment";
        var before=scope(submit,()->db.getById("pr_purchase_order_line",fixture.line()));
        var held=new java.util.concurrent.CountDownLatch(1);
        var attempted=new java.util.concurrent.CountDownLatch(1);
        var closing=orderLockObserver(()->{},()->{held.countDown();awaitNativeLatch(attempted,"shipment must reach the order lock held by closure");});
        var shipping=orderLockObserver(attempted::countDown,()->{});
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var closure=pool.submit(()->nativeActorThread(()->new TransactionTemplate(transactions).execute(status->scope(sync,()->{
                try {
                    var type=plugin.loadClass("com.auraboot.quote.engine.procurement.ProcurementOrderService");
                    return type.getMethod("syncClosures",String.class,Map.class,boolean.class).invoke(
                            type.getConstructor(DataAccessor.class,java.time.Clock.class).newInstance(closing,java.time.Clock.systemUTC()),
                            provider,Map.of(provider,true),true);
                }catch(ReflectiveOperationException error){throw unwrap(error);}
            }))));
            awaitNativeLatch(held,"closure must hold the actual order CAS before shipment starts");
            var shipment=pool.submit(()->nativeActorThread(()->new TransactionTemplate(transactions).execute(status->scope(submit,
                    ()->submitShipmentWith(shipping,fixture,"shipment-after-close","600")))));
            assertEquals(1,closure.get(20,java.util.concurrent.TimeUnit.SECONDS));
            var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->shipment.get(20,java.util.concurrent.TimeUnit.SECONDS));
            var conflict=assertInstanceOf(com.auraboot.framework.meta.exception.MetaServiceException.class,failure.getCause());
            assertEquals("CAS batch conflict: no partial batch may commit",conflict.getMessage());
        } finally {attempted.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
        var closed=scope(submit,()->db.getById("pr_purchase_order",fixture.order()));
        assertEquals("closed",closed.get("qo_pr_order_state"));
        scope(submit,()->{assertNativeQuantity("pr_purchase_order",fixture.order(),"qo_pr_order_revision","1");
            assertNativeQuantity("pr_purchase_order_line",fixture.line(),"pr_pol_qty","1000");
            assertNativeQuantity("pr_purchase_order_line",fixture.line(),"qo_pr_order_line_submitted_qty","0");
            assertNativeQuantity("pr_purchase_order_line",fixture.line(),"pr_pol_received_qty","0");
            assertTrue(nativeRows("qo_procurement_shipment_common","qo_pr_shipment_order_pid",fixture.order()).isEmpty());return null;});
        assertEquals(before,scope(submit,()->db.getById("pr_purchase_order_line",fixture.line())));
        var rejected=assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).execute(status->scope(submit,
                ()->submitShipmentWith(db,fixture,"shipment-after-close-fresh","600"))));
        assertEquals("ORDER_CLOSED_OR_FULFILLED",rejected.getMessage());
        assertEquals(closed,scope(submit,()->db.getById("pr_purchase_order",fixture.order())));
        assertEquals(before,scope(submit,()->db.getById("pr_purchase_order_line",fixture.line())));
        assertTrue(scope(submit,()->nativeRows("qo_procurement_shipment_common","qo_pr_shipment_order_pid",fixture.order())).isEmpty());
    }

    private DataAccessor orderLockObserver(Runnable before,Runnable after) {
        return (DataAccessor)java.lang.reflect.Proxy.newProxyInstance(DataAccessor.class.getClassLoader(),new Class<?>[]{DataAccessor.class},
                (proxy,method,args)->{
                    boolean lock=method.getName().equals("compareAndSetBatch") && "pr_purchase_order".equals(args[0]);
                    if(lock) before.run();
                    Object result;
                    try {result=method.invoke(db,args);}catch(InvocationTargetException error){throw error.getCause();}
                    if(lock) after.run();return result;
                });
    }
    private Object submitShipmentWith(DataAccessor rows,ShipmentFixture fixture,String key,String quantity) {
        try {
            var type=plugin.loadClass("com.auraboot.quote.engine.procurement.ProcurementShipmentService");
            return type.getMethod("submit",String.class,String.class,Map.class).invoke(
                    type.getConstructor(DataAccessor.class,java.time.Clock.class).newInstance(rows,java.time.Clock.systemUTC()),
                    fixture.order(),actor,Map.of("requestKey",key,"addressPid",fixture.address(),
                            "lines",List.of(Map.of("orderLinePid",fixture.line(),"quantity",quantity))));
        }catch(ReflectiveOperationException error){throw unwrap(error);}
    }

    private record ShipmentFixture(String order,String line,String address) {}
    @Test void nativeSupplierAddressCommandFiltersBeforePaginationAndProjectsSafeFields() throws Exception {
        var fixture=shipmentFixture();String maintain="qo_procurement_address_common:maintain";
        String supplier=(String)scope(maintain,()->db.getById("qo_procurement_address_common",fixture.address())).get("qo_pr_address_supplier_pid");
        for(int i=0;i<24;i++) {
            final int index=i;
            scope(maintain,()->db.create("qo_procurement_address_common",Map.of(
                    "qo_pr_address_supplier_pid",supplier,"qo_pr_address_state","active","qo_pr_address_address","Warehouse "+index,
                    "qo_pr_address_manager_name","Manager","qo_pr_address_manager_phone",index==0?" ":"+8613800000000")));
        }
        scope(maintain,()->db.create("qo_procurement_address_common",Map.of("qo_pr_address_supplier_pid",supplier,"qo_pr_address_state","inactive")));
        scope(maintain,()->db.create("qo_procurement_address_common",Map.of("qo_pr_address_supplier_pid","foreign-supplier","qo_pr_address_state","active","qo_pr_address_address","Secret warehouse")));
        var settings=new HashMap<String,Object>();settings.put(CommandHandlerExtension.DATA_ACCESSOR_KEY,db);
        settings.put(CommandHandlerExtension.CURRENT_USER_PID_KEY,actor);
        settings.put(CommandHandlerExtension.TENANT_PROJECTION_ACCESSOR_KEY,new TenantProjectionAccessorImpl(tenantId,background));
        var type=plugin.loadClass("com.auraboot.quote.engine.procurement.ProcurementCommandHandler");
        var handler=type.getConstructor().newInstance();var execute=type.getMethod("execute",CommandHandlerExtension.CommandContext.class);
        var all=new ArrayList<Map<?,?>>();
        for(int page=1;page<=3;page++) {
            var request=new CommandHandlerExtension.CommandContext(tenantId,"com.auraboot.quote-engine","qoe",
                    "qo_procurement_address_common:list_mine","qo_procurement_address_common",null,Map.of("page",page,"size",20),settings,false);
            var result=(Map<?,?>)execute.invoke(handler,request);
            assertEquals(25L,((Number)result.get("total")).longValue());
            var items=(List<?>)result.get("items");assertEquals(page==1?20:page==2?5:0,items.size());
            for(Object value:items) {
                var item=(Map<?,?>)value;assertEquals(Set.of("pid","address","managerName","managerPhone","selectable"),item.keySet());
                assertNotEquals("Secret warehouse",item.get("address"));all.add(item);
            }
        }
        assertEquals(25,all.stream().map(row->row.get("pid")).distinct().count());
        assertEquals(1,all.stream().filter(row->Boolean.FALSE.equals(row.get("selectable"))).count());
        assertTrue(all.stream().anyMatch(row->fixture.address().equals(row.get("pid")) && Boolean.TRUE.equals(row.get("selectable"))));
    }
    private ShipmentFixture shipmentFixture() {
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
        return new ShipmentFixture(order,line,address);
    }

    @Test void nativeShipmentReplaySnapshotAndFulfillmentNeverChangeActualReceipts() {
        var fixture=shipmentFixture();String order=fixture.order(),line=fixture.line(),address=fixture.address();
        String submit="qo_procurement_order_common:submit_shipment",maintain="qo_procurement_address_common:maintain";
        var tx=new TransactionTemplate(transactions);
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
