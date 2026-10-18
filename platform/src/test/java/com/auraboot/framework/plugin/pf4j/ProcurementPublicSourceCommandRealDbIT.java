package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.notification.sourcebinding.S06NativeEncryptionAdmission;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.service.AuthService;
import com.auraboot.framework.auth.dto.AuthenticationRequest;
import com.auraboot.framework.cloudconfig.dto.CloudConfigSaveRequest;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.DataAccessor;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real registered PF4J handler/controller/security/PG transaction; only external ERP is loopback synthetic.
 * Independent tenant imports authoritative DSL with an explicitly different local eligibility policy.
 * Does not prove customer003 selection/callback, production ERP admission, or a browser journey.
 */
@SpringBootTest(classes=TestApplication.class,properties={
    "spring.datasource.url=${TEST_DATABASE_URL}","spring.data.redis.database=${TEST_REDIS_DATABASE}",
    "aura.plugins.dir=${PROCUREMENT_SOURCE_PLUGIN_DIR}",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.slf4j.Slf4jImpl",
        "logging.level.com.auraboot.framework=INFO","logging.level.org.mybatis=INFO",
        "logging.level.org.springframework.security=WARN","logging.level.org.springframework.cache=WARN",
        "spring.jpa.show-sql=false"})
@ActiveProfiles("integration-test")
@Import(ProcurementPublicSourceCommandRealDbIT.IsolatedScheduling.class)
@EnabledIfEnvironmentVariable(named="PROCUREMENT_PUBLIC_SOURCE_IT",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProcurementPublicSourceCommandRealDbIT extends ProcurementPublicSourceNativeFixture {

    @Autowired FieldEncryptionService fieldEncryption;

    @DynamicPropertySource
    static void matchingEncryption(DynamicPropertyRegistry registry) {
        registerMatchingEncryption(registry,System.getenv());
    }

    static void registerMatchingEncryption(DynamicPropertyRegistry registry,Map<String,String> environment) {
        String key=S06NativeEncryptionAdmission.requiredKey(environment);
        registry.add("security.field-encryption.key",()->key);
    }

    @Override protected void admitEncryption() throws Exception {
        writeArtifact("matching-encryption-admission.json",S06NativeEncryptionAdmission.admit(
            System.getenv(),json,fieldEncryption,getClass(),application.getEnvironment().getActiveProfiles()));
    }

    @Test @Order(1) void publicConfiguredReadCreatesThenReplaysWithoutDuplicatePgFacts() throws Exception {
        configurePolicy();var first=call(token,selected("11"));assertEquals(200,first.status());assertEquals("added",first.body().path("data").path("data").path("items").get(0).path("status").asText());
        var before=snapshot();assertEquals(1,sources().size());assertEquals(2,references().size());
        String sourcePid=pid(sources().getFirst());
        var replay=call(token,selected("11"));assertEquals(200,replay.status());
        assertEquals("unchanged",replay.body().path("data").path("data").path("items").get(0).path("status").asText());
        var after=snapshot();assertReplaySnapshot(sourcePid,before,after);
        writeArtifact("replay-snapshots.json",Map.of("sourcePid",sourcePid,"before",before,"after",after));
        assertTrue(loginCount.get()>0 && queryCount.get()>0);
    }

    @Test @Order(5) void publicRoleAndPayloadCannotGrantIntegrationOrSecretCapabilities() throws Exception {
        assertNotNull(baselineTenant);assertNotEquals(baselineTenant,tenant);assertNotNull(baselineTargetTask);
        configurePolicy();assertEquals(403,call(deniedToken,selected("11")).status());assertEquals(0,loginCount.get());assertEquals(0,queryCount.get());
        var forged=new LinkedHashMap<>(selected("11"));forged.put("eligible",true);forged.put("tenantSecretRead",true);forged.put("appSecret","client-secret");
        assertEquals(400,call(token,forged).status());assertEquals(0,loginCount.get());assertTrue(sources().isEmpty());assertEquals(1,references().size());
        var primaryBefore=snapshotTenant(baselineTenant);var isolatedBefore=snapshot();
        String ownTask=task;task=baselineTargetTask;observed.tenant=tenant;observed.missingTargetPid=baselineTargetTask;observed.targetNotFound.set(false);
        try {assertEquals(400,call(token,selected("11")).status(),"Current public command wrapper maps this typed missing target to BadParam");assertTrue(observed.targetNotFound.get(),"Actual governed target lookup must throw typed MetaRecordNotFoundException");}
        finally {task=ownTask;observed.missingTargetPid=null;}
        assertEquals(primaryBefore,snapshotTenant(baselineTenant));assertEquals(isolatedBefore,snapshot());
        assertEquals(0,loginCount.get());assertEquals(0,queryCount.get());
        writeArtifact("cross-tenant-denial.json",Map.of("foreignTenantId",baselineTenant.toString(),"actorTenantId",tenant.toString(),
            "foreignTaskPid",baselineTargetTask,"httpStatus",400,"typedMetaRecordNotFoundObserved",true,"bothTenantBusinessSnapshotsUnchanged",true));
    }

    @Test @Order(2) void publicMissingPolicyAndLaterUnknownCodeRejectWholeReadWithoutWrites() throws Exception {
        assertEquals(400,call(token,selected("11")).status());assertEquals(0,loginCount.get());assertTrue(sources().isEmpty());
        configurePolicy();response=combine(row("11",1000,"C"),row("12",1000,"UNKNOWN"));
        var before=snapshot();assertEquals(400,call(token,selected("11","12")).status());assertEquals(before,snapshot());assertTrue(queryCount.get()>0);
    }

    @Test @Order(3) void publicCriticalChangeInvalidatesWithPersistedSystemHistoryAndRetainsFormalOffer() throws Exception {
        configurePolicy();assertEquals(200,call(token,selected("11")).status());
        var quote=prepareSubmittedQuote();var formal=formalSnapshot(quote.submission());
        response=row("11",900,"C");assertEquals(200,call(token,selected("11")).status());
        fixture(SYNC,()->{assertEquals("invalidated",db.getById("s2p_bid",quote.quote()).get("s2p_bid_status"));
            assertEquals("SOURCE_CHANGED",db.getById("s2p_bid",quote.quote()).get("qo_pr_quote_return_reason"));
            assertEquals(formal,formalSnapshot(quote.submission()),"Both formal headers and complete formal lines remain immutable");
            var history=db.query("qo_procurement_history_common",Map.of("qo_pr_history_target_pid",quote.quote()));
            assertTrue(history.stream().anyMatch(event->"SOURCE_CHANGED".equals(event.get("qo_pr_history_reason")) && "system".equals(event.get("qo_pr_history_actor_pid"))));return null;});
        var changed=snapshot();assertEquals(200,call(token,selected("11")).status());assertCriticalReplaySnapshot(quote.source(),quote.quote(),changed,snapshot());
    }

    @Test @Order(4) void publicBatchRollsBackAnActuallyWrittenFirstSourceWhenLaterAllocatedSourceChanges() throws Exception {
        configurePolicy();assertEquals(200,call(token,selected("11")).status());var quote=prepareSubmittedQuote();
        fixture("qo_procurement_task_common:allocate",()->new TransactionTemplate(transactions).execute(status->service("ProcurementAllocationService",new Class<?>[]{DataAccessor.class},new Object[]{db},"allocate",new Class<?>[]{String.class,Map.class},task,
            Map.of("sourceDemands",List.of(Map.of("sourcePid",quote.source(),"sourceRevision",quote.sourceRevision(),"quantity","300")),
                "allocations",List.of(Map.of("quotePid",quote.quote(),"sourcePid",quote.source(),"submissionPid",quote.submission(),"quantity","300"))))));
        String allocatedTask=task;
        task=fixture("qo_procurement_task_common:create",()->pid((Map<?,?>)service("ProcurementTaskService",new Class<?>[]{DataAccessor.class},new Object[]{db},"create",new Class<?>[]{Object.class,String.class,Map.class},tenant,actor,Map.of("title",provider+" second draft inquiry","connectionPid",connection))));
        retainedTaskPids.add(task);
        assertNotEquals(allocatedTask,task,"Fresh row is admitted into a draft inquiry; duplicate retains original allocated inquiry");
        var before=snapshot();observed.created.set(null);observed.tenant=tenant;
        response=combine(row("12",1000,"C"),row("11",900,"C"));var result=call(token,selected("12","11"));
        assertEquals(400,result.status());assertNotNull(observed.created.get(),"First source must actually be inserted before the later allocation conflict");
        assertEquals(before,snapshot(),"Actual host transaction must roll back source/reference/quote/history/task and reservations");
        assertTrue(sources().isEmpty());fixture(SYNC,()->{assertEquals(0,new BigDecimal("300").compareTo(new BigDecimal(db.getById("pr_purchase_request",quote.source()).get("qo_pr_source_reserved_qty").toString())));return null;});
    }

    @Test @Order(6) void publicConfiguredOrderCreatesImmutableSourceLinkedFactsAndReplaysWithoutAnotherSave() throws Exception {
        var order=prepareAllocatedOrder();String bearer=orderActor(true);String denied=orderActor(false);
        var before=snapshot();assertEquals(403,orderCall(denied,order.execution(),Map.of()).status());assertEquals(before,snapshot());
        assertEquals(400,orderCall(bearer,order.execution(),Map.of("quantity","1","connectionPid","client")).status());assertEquals(before,snapshot());
        assertEquals(400,orderCall(bearer,order.execution(),Map.of()).status());assertEquals(before,snapshot());
        assertEquals(0,orderSaveCount.get());assertEquals(0,orderLookupCount.get());
        configureOrderPolicy();var formal=formalSnapshot(order.quote().submission());
        var allocations=fixture(SYNC,()->db.query(domainModel("allocation"),Map.of("qo_pr_allocation_execution_pid",order.execution())));
        var source=fixture(SYNC,()->db.getById(domainModel("source"),order.quote().source()));
        var created=orderCall(bearer,order.execution(),Map.of());assertEquals(200,created.status());assertEquals(1,orderSaveCount.get());
        assertEquals("supplier-local",savedOrder.path("Supplier").path("Id").asText());var line=savedOrder.path("Lines").get(0);
        assertEquals("900",line.path("Material").path("Id").asText());assertEquals(0,new BigDecimal("300.000001").compareTo(new BigDecimal(line.path("Quantity").asText())));
        assertEquals(0,new BigDecimal("1.25").compareTo(new BigDecimal(line.path("Price").asText())));
        assertEquals("100",line.path("Source").path("Head").asText());assertEquals("11",line.path("Source").path("Entry").asText());
        var succeeded=fixture(SYNC,()->db.getById(domainModel("execution"),order.execution()));
        assertEquals("succeeded",succeeded.get("qo_pr_execution_state"));assertEquals("local-order-1",succeeded.get("qo_pr_execution_erp_order_id"));
        assertEquals(200,orderCall(bearer,order.execution(),Map.of()).status());assertEquals(1,orderSaveCount.get());assertEquals(0,orderLookupCount.get());
        assertEquals(succeeded,fixture(SYNC,()->db.getById(domainModel("execution"),order.execution())));
        assertEquals(formal,formalSnapshot(order.quote().submission()));
        assertEquals(allocations,fixture(SYNC,()->db.query(domainModel("allocation"),Map.of("qo_pr_allocation_execution_pid",order.execution()))));
        assertEquals(source,fixture(SYNC,()->db.getById(domainModel("source"),order.quote().source())));
        verifyPublicOrderSynchronization(order,source,allocations,formal);
        writeArtifact("public-order-outcome.json",Map.of("executionPid",order.execution(),"sourcePid",order.quote().source(),"submissionPid",order.quote().submission(),"saveCount",orderSaveCount.get(),"lookupCount",orderLookupCount.get(),"externalErpSynthetic",true,"customer006Verified",false));
    }

    @Test @Order(7) void publicUnknownSaveKeepsIndependentCheckpointUntilMarkerLookupWithoutResending() throws Exception {
        var order=prepareAllocatedOrder();String bearer=orderActor(true);configureOrderPolicy();ambiguousOrderSave=true;
        var source=fixture(SYNC,()->db.getById(domainModel("source"),order.quote().source()));
        var formal=formalSnapshot(order.quote().submission());
        var allocations=fixture(SYNC,()->db.query(domainModel("allocation"),Map.of("qo_pr_allocation_execution_pid",order.execution())));
        assertEquals(400,orderCall(bearer,order.execution(),Map.of()).status());assertEquals(1,orderSaveCount.get());
        var unknown=fixture(SYNC,()->db.getById(domainModel("execution"),order.execution()));assertEquals("unknown",unknown.get("qo_pr_execution_state"));
        assertEquals("CREATE_RESULT_UNKNOWN",unknown.get("qo_pr_execution_failure_code"));
        assertEquals(source,fixture(SYNC,()->db.getById(domainModel("source"),order.quote().source())));
        assertEquals(200,orderCall(bearer,order.execution(),Map.of()).status());assertEquals(1,orderSaveCount.get());assertEquals(1,orderLookupCount.get());
        var succeeded=fixture(SYNC,()->db.getById(domainModel("execution"),order.execution()));assertEquals("succeeded",succeeded.get("qo_pr_execution_state"));
        assertEquals("local-order-1",succeeded.get("qo_pr_execution_erp_order_id"));assertEquals(formal,formalSnapshot(order.quote().submission()));
        assertEquals(allocations,fixture(SYNC,()->db.query(domainModel("allocation"),Map.of("qo_pr_allocation_execution_pid",order.execution()))));
        assertEquals(source,fixture(SYNC,()->db.getById(domainModel("source"),order.quote().source())));
        writeArtifact("public-order-recovery.json",Map.of("executionPid",order.execution(),"checkpointSurvivedPublicOuterRollback",true,"saveCount",orderSaveCount.get(),"lookupCount",orderLookupCount.get(),"externalErpSynthetic",true,"customer006Verified",false));
    }

    @Test @Order(8) void publicOrderTargetCannotCrossIntoAnotherFormallyImportedTenant() throws Exception {
        assertNotNull(baselineOrderExecution);assertNotEquals(baselineTenant,tenant);String bearer=orderActor(true);
        var primaryBefore=snapshotTenant(baselineTenant);var secondaryBefore=snapshot();
        observed.tenant=tenant;observed.missingTargetPid=baselineOrderExecution;observed.missingTargetModel="qo_procurement_execution_common";observed.targetNotFound.set(false);
        try {
            assertEquals(400,orderCall(bearer,baselineOrderExecution,Map.of()).status());assertTrue(observed.targetNotFound.get());
            String syncBearer=orderSyncActor();
            for(String command:List.of(SYNC_ORDERS,SYNC_CLOSURES)){
                observed.targetNotFound.set(false);assertEquals(400,orderSyncCall(syncBearer,command,baselineOrderExecution).status());assertTrue(observed.targetNotFound.get());
            }
        } finally {observed.missingTargetModel="s2p_sourcing_event";observed.missingTargetPid=null;}
        assertEquals(primaryBefore,snapshotTenant(baselineTenant));assertEquals(secondaryBefore,snapshot());
        assertEquals(0,orderSaveCount.get());assertEquals(0,orderLookupCount.get());assertEquals(0,loginCount.get());
        writeArtifact("public-order-cross-tenant-denial.json",Map.of("foreignTenantId",baselineTenant.toString(),"actorTenantId",tenant.toString(),"executionPid",baselineOrderExecution,"httpStatus",400,"typedTargetNotFoundObserved",true,"bothTenantBusinessFactsUnchanged",true,"erpCalls",0));
    }
}
