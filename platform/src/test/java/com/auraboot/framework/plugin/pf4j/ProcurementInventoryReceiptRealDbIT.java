package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.integration.ReliableEventConsumerRegistry;
import com.auraboot.framework.meta.entity.OutboxEvent;
import com.auraboot.framework.meta.mapper.OutboxEventMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.OutboxWorkerImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.pf4j.PluginState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Actual Auth/registered commands/PF4J/T01/PG; only upstream ERP is controlled loopback.
 * This fixture has no inherited business @Test methods and does not replace Source5/Order3.
 */
@SpringBootTest(classes=TestApplication.class,properties={
    "spring.datasource.url=${TEST_DATABASE_URL}","spring.data.redis.database=${TEST_REDIS_DATABASE}",
    "aura.plugins.dir=${PROCUREMENT_SOURCE_PLUGIN_DIR}",
    "security.field-encryption.key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="})
@ActiveProfiles("integration-test")
@Import({ProcurementPublicSourceNativeFixture.IsolatedScheduling.class,ProcurementInventoryReceiptRealDbIT.CasObservationConfiguration.class})
@EnabledIfEnvironmentVariable(named="PROCUREMENT_TRUSTED_RECEIPT_IT",matches="true")
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class ProcurementInventoryReceiptRealDbIT extends ProcurementPublicSourceNativeFixture {
    private static final String REQUEST="pr:confirm_purchase_receipt",CONFIRM="inv:confirm_warehouse_in";
    private static final String POSTED="inventory.receipt.confirmed.v1";
    private static final String COUNTER="procurement.confirmed-receipt-counter.v1";
    private static final List<String> PR_READS=List.of("pr_purchase_receipt","pr_purchase_receipt_line",
        "pr_purchase_order","pr_purchase_order_line","pr_inventory_operation_request");
    private static final List<String> INVENTORY_READS=List.of("inv_inbound","inv_inbound_line","inv_warehouse",
        "inv_warehouse_location","prod_product","inv_balance","inv_balance_ledger");
    @Autowired OutboxEventMapper outbox;
    @Autowired OutboxWorkerImpl worker;
    @Autowired ReliableEventConsumerRegistry consumers;
    @Autowired AuraPluginManager plugins;
    @Autowired MetaModelService models;

    @Autowired ReceiptCasObservation casObservation;
    private boolean splitRead;
    private enum Scenario { HAPPY, LATE_CAS, LOST_LEASE, HELD_LEASE }
    @Test void registeredReceiptCommandsPersistLineageAndCounterExactlyOnce() throws Exception {runReceiptScenario(Scenario.HAPPY);}
    @Test void postedReceiptLateCasFailureRollsBackReceiverButRetainsPostedStock() throws Exception {runReceiptScenario(Scenario.LATE_CAS);}
    @Test void replacedDeliveryLeaseRejectsOldReceiverWithoutEffects() throws Exception {runReceiptScenario(Scenario.LOST_LEASE);}
    @Test void heldCounterRowLockSkipsExpiredConcurrentClaimAndRollsBackExpiredEffect() throws Exception {runReceiptScenario(Scenario.HELD_LEASE);}

    private void runReceiptScenario(Scenario scenario) throws Exception {
        splitRead=scenario==Scenario.LATE_CAS || scenario==Scenario.HELD_LEASE;
        verifyReceiptArtifactAdmission();
        PreparedOrder order=prepareAllocatedOrder();configureOrderPolicy();
        assertEquals(200,orderCall(orderActor(true),order.execution(),Map.of()).status());
        readReceivedQuantity="0";configureOrderReadPolicy();
        assertEquals(200,orderSyncCall(orderSyncActor(),SYNC_ORDERS,order.execution()).status());
        Map<String,Object> po=only("pr_purchase_order",Map.of("qo_pr_order_erp_id","local-order-1","qo_pr_order_account_id",provider));
        String poPid=pid(po);var orderLines=new ArrayList<>(records("pr_purchase_order_line",Map.of("pr_pol_order_id",poPid)));
        orderLines.sort(java.util.Comparator.comparing(ProcurementPublicSourceNativeFixture::pid));
        assertEquals(splitRead?2:1,orderLines.size());Map<String,Object> line=orderLines.getFirst();
        var originalLines=orderLines.stream().map(LinkedHashMap::new).toList();
        String receiptQuantity=splitRead?"125":"250";
        String linePid=pid(line);assertDecimal("0",line.get("pr_pol_received_qty"));
        assertEquals("approved",po.get("pr_po_status"));assertEquals(Boolean.TRUE,line.get("pr_pol_collaboration_managed"));
        Map<String,Object> upstream=snapshot();Map<String,Object> formal=formalSnapshot(order.quote().submission());
        String warehouse=fixture("inv:create_warehouse",()->pid(db.create("inv_warehouse",Map.of(
            "inv_warehouse_code",provider,"inv_warehouse_name",provider,"inv_warehouse_type","raw_material",
            "inv_warehouse_status","enabled","inv_wh_lot_tracking",false,"inv_wh_sn_tracking",false))));
        String location=fixture("inv:create_warehouse_location",()->pid(db.create("inv_warehouse_location",Map.of(
            "inv_wl_name",provider,"inv_wl_code",provider,"inv_wl_warehouse_id",warehouse,
            "inv_loc_type","shelving","inv_loc_status","active","inv_wl_status","enabled"))));
        String prReceipt=fixture("pr:create_purchase_receipt",()->pid(db.create("pr_purchase_receipt",Map.of(
            "pr_rcpt_code",provider,"pr_rcpt_po_id",poPid,"pr_rcpt_date","2066-12-01",
            "pr_rcpt_warehouse_id",warehouse,"pr_rcpt_status","draft"))));
        var prLines=new ArrayList<String>();
        for(var orderedLine:orderLines)prLines.add(fixture("pr:create_purchase_receipt",()->pid(db.create("pr_purchase_receipt_line",Map.of(
            "pr_rcptl_receipt_id",prReceipt,"pr_rcptl_po_line_id",pid(orderedLine),
            "pr_rcptl_product_id",orderedLine.get("pr_pol_product_id"),"pr_rcptl_qty",new BigDecimal(receiptQuantity),
            "pr_rcptl_price",BigDecimal.ONE,"pr_rcptl_amount",new BigDecimal(receiptQuantity))))));
        String prActor=receiptActor("request","pr.purchase.manage",PR_READS);
        Map<String,Object> requestPayload=Map.of("warehouseId",warehouse,"receivingLocationId",location);
        Map<String,Object> prBefore=record("pr_purchase_receipt",prReceipt);
        String requestKey=provider+"-pr-confirm";
        assertEquals(200,receiptCommand(prActor,REQUEST,prReceipt,prBefore,requestKey,requestPayload));
        Map<String,Object> request=only("pr_inventory_operation_request",Map.of("pr_ior_source_id",prReceipt));
        assertEquals("enqueued",request.get("pr_ior_status"));
        String requestEvent=request.get("pr_ior_event_id").toString();
        var immutableRequest=new LinkedHashMap<>(request);
        OutboxEvent requestClaim=claimExact(requestEvent);
        dispatchActualWorker(requestClaim);assertApplied(requestEvent,"inventory.public-command-adapter.v1",1);
        String invReceipt=pid(only("inv_inbound",Map.of("inv_in_source_pid",prReceipt)));
        var invLines=records("inv_inbound_line",Map.of("inv_in_line_receipt_id",invReceipt));assertEquals(orderLines.size(),invLines.size());
        for(int index=0;index<orderLines.size();index++){
            String sourceLine=prLines.get(index),poLine=pid(orderLines.get(index));
            var invLine=invLines.stream().filter(row->sourceLine.equals(row.get("inv_in_line_source_line_pid"))).findFirst().orElseThrow();
            assertEquals(poLine,invLine.get("inv_in_line_source_po_line_pid"));assertDecimal(receiptQuantity,invLine.get("inv_in_line_qty"));
        }
        assertDecimal("0",record("pr_purchase_order_line",linePid).get("pr_pol_received_qty"));
        dispatchActualWorker(requestClaim);assertApplied(requestEvent,"inventory.public-command-adapter.v1",1);
        assertEquals(1,records("inv_inbound",Map.of("inv_in_source_pid",prReceipt)).size());
        finishClaim(requestClaim);
        String inventoryActor=receiptActor("post","inv.warehouse.manage",INVENTORY_READS);
        Map<String,Object> invBefore=record("inv_inbound",invReceipt);String postKey=provider+"-inv-confirm";
        assertTrue(records("inv_balance",Map.of("inv_bal_warehouse_id",warehouse)).isEmpty());
        assertTrue(records("inv_balance_ledger",Map.of("inv_bl_warehouse_id",warehouse)).isEmpty());
        assertEquals(200,receiptCommand(inventoryActor,CONFIRM,invReceipt,invBefore,postKey,Map.of()));
        Map<String,Object> confirmed=record("inv_inbound",invReceipt);assertEquals("confirmed",confirmed.get("inv_in_status"));
        int version=new BigDecimal(confirmed.get("inv_in_receipt_version").toString()).intValueExact();
        String postedEvent=UUID.nameUUIDFromBytes((POSTED+":"+tenant+":"+invReceipt+":"+version).getBytes(StandardCharsets.UTF_8)).toString();
        OutboxEvent postedClaim=claimExact(postedEvent,(scenario==Scenario.HELD_LEASE || scenario==Scenario.LOST_LEASE)?java.time.Instant.now().plusSeconds(2):java.time.Instant.now().plusSeconds(120));
        var headers=json.readTree(postedClaim.getHeaders());assertEquals("inv:confirm_warehouse_in",postedClaim.getCommandCode());
        assertEquals("com.auraboot.inventory",headers.path("core.inventory.producer.plugin").asText());
        assertTrue(headers.path("core.inventory.producer.artifact").asText().matches("[a-f0-9]{64}"));
        Map<String,Object> stockBefore=stockSnapshot(warehouse);
        assertStockPosted(warehouse,orderLines.size());
        if(scenario==Scenario.LATE_CAS || scenario==Scenario.HELD_LEASE)postedClaim=assertConcurrentReceiverRollback(scenario,postedClaim,originalLines,stockBefore,warehouse);
        else if(scenario==Scenario.LOST_LEASE){
            waitUntilExpired(postedClaim);var replacement=claimExact(postedEvent);
            OutboxEvent stale=postedClaim;assertThrows(SecurityException.class,()->dispatchActualWorker(stale));assertNoReceiverReceipt(postedEvent);
            assertEquals(originalLines,recordsSorted("pr_purchase_order_line",Map.of("pr_pol_order_id",poPid)));
            assertEquals(stockBefore,stockSnapshot(warehouse));postedClaim=replacement;
        }
        dispatchActualWorker(postedClaim);assertApplied(postedEvent,COUNTER,1);
        Map<String,Object> received=record("pr_purchase_order_line",linePid);assertDecimal(receiptQuantity,received.get("pr_pol_received_qty"));
        for(var original:originalLines){var after=record("pr_purchase_order_line",pid(original));assertDecimal(receiptQuantity,after.get("pr_pol_received_qty"));
            long extra=scenario==Scenario.LATE_CAS && pid(original).equals(pid(originalLines.getLast()))?2:1;
            assertEquals(new BigDecimal(original.get("row_version").toString()).longValueExact()+extra,new BigDecimal(after.get("row_version").toString()).longValueExact());
            assertLineBusinessRetained(original,after);
        }assertEquals(immutableRequest,record("pr_inventory_operation_request",pid(request)));
        assertEquals(formal,formalSnapshot(order.quote().submission()));assertUpstreamRetained(upstream,snapshot());
        dispatchActualWorker(postedClaim);assertEquals(received,record("pr_purchase_order_line",linePid));assertApplied(postedEvent,COUNTER,1);
        finishClaim(postedClaim);
        if(scenario==Scenario.HAPPY)assertMissingCapabilityAndForeignTarget(prReceipt,requestPayload,invReceipt);
        assertEquals(200,receiptCommand(inventoryActor,CONFIRM,invReceipt,confirmed,postKey,Map.of()));
        assertEquals(stockBefore,stockSnapshot(warehouse));assertEquals(received,record("pr_purchase_order_line",linePid));
        writeArtifact("trusted-receipt-result.json",Map.of("tenantId",tenant.toString(),"orderPid",poPid,"orderLinePid",linePid,
            "prReceiptPid",prReceipt,"inventoryReceiptPid",invReceipt,"requestEventId",requestEvent,"postedEventId",postedEvent,
            "receivedQtyPerLine",receiptQuantity,"nativeExecuted",true,"externalErpSynthetic",true));
    }

    @Override protected List<List<Object>> orderReadRows() {
        var base=super.orderReadRows();if(!splitRead)return base;
        var first=new ArrayList<Object>(base.getFirst());first.set(8,"150");
        var second=new ArrayList<Object>(base.getFirst());second.set(2,"local-entry-2");second.set(8,"150.000001");
        return List.of(first,second);
    }
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class CasObservationConfiguration {
        @org.springframework.context.annotation.Bean ReceiptCasObservation receiptCasObservation(){return new ReceiptCasObservation();}
    }
    /** Observes after an actual committed-to-T01 SQL CAS; never replaces the service or its result. */
    @org.aspectj.lang.annotation.Aspect static class ReceiptCasObservation {
        volatile Long tenant;volatile String firstPid;volatile boolean enabled;
        volatile java.util.concurrent.CountDownLatch entered,release;
        void arm(Long tenant,String pid){this.tenant=tenant;firstPid=pid;entered=new java.util.concurrent.CountDownLatch(1);release=new java.util.concurrent.CountDownLatch(1);enabled=true;}
        void clear(){enabled=false;if(release!=null)release.countDown();}
        @org.aspectj.lang.annotation.AfterReturning(pointcut="execution(* com.auraboot.framework.meta.service.DynamicDataService+.compareAndSet(..)) && args(model,pid,field,version,patch)",returning="result")
        public void afterActualCas(String model,String pid,String field,Object version,Map<String,Object> patch,Object result) throws InterruptedException {
            if(enabled && java.util.Objects.equals(tenant,com.auraboot.framework.application.tenant.MetaContext.getCurrentTenantId())
                && "pr_purchase_order_line".equals(model) && firstPid.equals(pid) && Boolean.TRUE.equals(result)){
                enabled=false;entered.countDown();assertTrue(release.await(15,java.util.concurrent.TimeUnit.SECONDS),"Native CAS observer must be released");
            }
        }
    }
    private OutboxEvent assertConcurrentReceiverRollback(Scenario scenario,OutboxEvent event,List<LinkedHashMap<String,Object>> originals,Map<String,Object> postedStock,String warehouse) throws Exception {
        assertEquals(2,originals.size());String first=pid(originals.getFirst()),second=pid(originals.getLast());
        casObservation.arm(tenant,first);var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        var delivery=executor.submit(()->{try {dispatchActualWorker(event);return null;}finally {com.auraboot.framework.application.tenant.MetaContext.clear();}});
        try {
            assertTrue(casObservation.entered.await(10,java.util.concurrent.TimeUnit.SECONDS),"Actual first PG CAS must happen before counterexample");
            if(scenario==Scenario.LATE_CAS){
                Boolean won=fixture("qo_procurement_order_line_common:sync_orders",()->new TransactionTemplate(transactions).execute(status->db.compareAndSet(
                    "pr_purchase_order_line",second,"row_version",originals.getLast().get("row_version"),Map.of("pr_pol_received_qty",new BigDecimal("0")))));
                assertEquals(Boolean.TRUE,won,"The independent real PG competitor must advance only the second line version");
            }else {
                waitUntilExpired(event);var tx=new TransactionTemplate(transactions);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                var skipped=tx.execute(status->outbox.claimKnownEvent(tenant,event.getEventId(),provider+"-contender",UUID.randomUUID().toString(),java.time.Instant.now().plusSeconds(120)));
                assertNotNull(skipped);assertTrue(skipped.isEmpty(),"Expired eligible row must be skipped while T01 still holds its FOR UPDATE lock");
            }
            casObservation.release.countDown();
            var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->delivery.get(15,java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class,failure.getCause(),"The observed version/lease counter guard must cause rollback");assertNoReceiverReceipt(event.getEventId());
            for(var original:originals){var after=record("pr_purchase_order_line",pid(original));assertDecimal("0",after.get("pr_pol_received_qty"));
                if(scenario==Scenario.LATE_CAS && second.equals(pid(original))){
                    assertEquals(new BigDecimal(original.get("row_version").toString()).longValueExact()+1,new BigDecimal(after.get("row_version").toString()).longValueExact());assertLineBusinessRetained(original,after);
                }else assertEquals(original,after,"Every T01 first-line SQL effect/audit must roll back");
            }
            assertEquals(postedStock,stockSnapshot(warehouse),"Prior Inventory posting remains committed and is not rolled back by the receiver");
            writeArtifact("receiver-rollback-"+scenario.name().toLowerCase()+".json",Map.of("eventId",event.getEventId(),"firstRealCasObserved",true,
                "receiverAppliedReceiptCount",0,"receiverEffectsRolledBack",true,"priorStockRetained",true,"failureClass",failure.getCause().getClass().getSimpleName()));
            if(scenario==Scenario.LATE_CAS)finishRetry(event);
            return claimExact(event.getEventId());
        } finally {casObservation.clear();executor.shutdownNow();assertTrue(executor.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));context();}
    }
    private void finishRetry(OutboxEvent event){context();Integer changed=new TransactionTemplate(transactions).execute(status->outbox.recordFailureFenced(event.getId(),event.getLeaseToken(),java.time.Instant.now(),"controlled-native-version-conflict"));assertEquals(Integer.valueOf(1),changed);}
    private void assertNoReceiverReceipt(String event){assertEquals(Integer.valueOf(0),new JdbcTemplate(postgres).queryForObject("SELECT COUNT(*) FROM ab_integration_receipt WHERE tenant_id=? AND event_id=? AND consumer_code=?",Integer.class,tenant,event,COUNTER));}
    private void waitUntilExpired(OutboxEvent event) throws InterruptedException {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<deadline){Boolean expired=new JdbcTemplate(postgres).queryForObject("SELECT lease_until<NOW() FROM ab_outbox WHERE tenant_id=? AND event_id=? AND lease_token=? AND status='processing'",Boolean.class,tenant,event.getEventId(),event.getLeaseToken());
            if(Boolean.TRUE.equals(expired))return;Thread.sleep(50);
        }fail("Owned exact lease did not expire within its bounded native interval");
    }
    private void assertMissingCapabilityAndForeignTarget(String receipt,Map<String,Object> payload,String inventoryReceipt) throws Exception {
        var before=snapshot();var receiptBefore=record("pr_purchase_receipt",receipt);var inventoryBefore=record("inv_inbound",inventoryReceipt);
        String missing=receiptActor("missing",null,PR_READS);assertEquals(403,receiptCommand(missing,REQUEST,receipt,receiptBefore,provider+"-missing",payload));
        assertEquals(before,snapshot());assertEquals(receiptBefore,record("pr_purchase_receipt",receipt));assertEquals(inventoryBefore,record("inv_inbound",inventoryReceipt));
        String foreign=prepareForeignActor();observed.tenant=foreignTenant;observed.missingTargetModel="pr_purchase_receipt";observed.missingTargetPid=receipt;observed.targetNotFound.set(false);
        try {assertEquals(400,receiptCommand(foreign,REQUEST,receipt,receiptBefore,provider+"-foreign",payload));assertTrue(observed.targetNotFound.get(),"Exact foreign public target must produce typed MetaRecordNotFound observation");}
        finally {observed.tenant=tenant;observed.missingTargetPid=null;observed.missingTargetModel="s2p_sourcing_event";}
        assertEquals(before,snapshot());assertEquals(foreignBefore,snapshotTenant(foreignTenant));
        assertEquals(receiptBefore,record("pr_purchase_receipt",receipt));assertEquals(inventoryBefore,record("inv_inbound",inventoryReceipt));
        writeArtifact("receipt-public-denials.json",Map.of("missingBusinessCapabilityHttpStatus",403,"foreignPublicTargetHttpStatus",400,
            "foreignTenantId",foreignTenant.toString(),"typedForeignTargetObserved",true,"bothTenantDomainSnapshotsUnchanged",true));
    }
    private Long foreignTenant;private Map<String,Object> foreignBefore;
    private String prepareForeignActor() throws Exception {
        Long ownTenant=tenant,ownUser=userId,ownMember=memberId;String ownActor=actor;context();
        var organization=new com.auraboot.framework.tenant.dao.entity.Tenant();organization.setName(provider+"_foreign");organization.setDisplayName(provider);organization.setStatus("active");
        organization.setPid(com.auraboot.framework.common.util.UniqueIdGenerator.generate());foreignTenant=tenants.createTenant(organization).getId();
        assertNotEquals(Long.valueOf(System.getenv("TEST_TENANT_ID")),foreignTenant);assertNotEquals(ownTenant,foreignTenant);
        String name=provider+"-foreign-bootstrap";var bootstrap=users.signUp(name+"@example.invalid",PASSWORD,"Receipt foreign bootstrap",name);
        var bootstrapMember=members.addMember(bootstrap.getId(),foreignTenant,"active");
        try {
            tenant=foreignTenant;userId=bootstrap.getId();actor=bootstrap.getPid();memberId=bootstrapMember.getId();context();
            assertTrue(tenantBootstrap.bootstrapTenant(tenant,userId).isSuccess());context();
            var directories=json.readTree(required("PROCUREMENT_SOURCE_IMPORT_DIRECTORIES"));assertEquals(23,directories.size());
            for(var directory:directories)importDirectory(Path.of(directory.asText()));imports.verifyImportReferenceIntegrity();
            String bearer=receiptActor("foreign","pr.purchase.manage",PR_READS);foreignBefore=snapshot();
            writeArtifact("foreign-receipt-tenant.json",Map.of("tenantId",foreignTenant.toString(),"formalImportDirectoryCount",23,"bootstrapPrincipalUsedForPublicRequest",false,"bootstrapUserPid",bootstrap.getPid(),"bootstrapMemberPid",bootstrapMember.getPid()));return bearer;
        } finally {tenant=ownTenant;userId=ownUser;actor=ownActor;memberId=ownMember;context();}
    }

    private String receiptActor(String suffix,String capability,List<String> readModels) {
        context();String username=provider+"-receipt-"+suffix,email=username+"@example.invalid";
        var user=users.signUp(email,PASSWORD,"Trusted receipt local fixture",username);
        var member=members.addMember(user.getId(),tenant,"active");var codes=new ArrayList<>(List.of("meta.command.execute"));if(capability!=null)codes.add(capability);
        readModels.forEach(model->codes.add("model."+model+".read"));
        return actorToken(user.getId(),user.getPid(),member.getId(),email,List.copyOf(codes));
    }
    private int receiptCommand(String bearer,String code,String target,Map<String,Object> before,String key,Map<String,Object> payload) throws Exception {
        var input=new LinkedHashMap<String,Object>();input.put("targetRecordPid",target);input.put("expectedVersion",before.get("row_version"));
        input.put("clientRequestId",key);input.put("payload",payload);
        org.springframework.test.web.servlet.MvcResult result;observed.publicRequestActive=true;
        try {result=mvc.perform(post("/api/commands/"+code).header("Authorization","Bearer "+bearer).header("X-Locale","en-US")
            .contentType("application/json").content(json.writeValueAsBytes(input))).andReturn();}
        finally {observed.publicRequestActive=false;}context();
        var response=json.readTree(result.getResponse().getContentAsByteArray());
        writeArtifact("receipt-public-request-"+(++publicRequestSequence)+".json",Map.of("commandCode",code,"targetRecordPid",target,
            "httpStatus",result.getResponse().getStatus(),"businessCode",safeNumericCode(response.path("code")),"rawResponseOrCredentialsPersisted",false));
        return result.getResponse().getStatus();
    }
    private OutboxEvent claimExact(String eventId) {return claimExact(eventId,java.time.Instant.now().plusSeconds(120));}
    private OutboxEvent claimExact(String eventId,java.time.Instant until) {
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),"No outer event lock/transaction");
        context();var tx=new TransactionTemplate(transactions);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var rows=tx.execute(status->outbox.claimKnownEvent(tenant,eventId,provider,UUID.randomUUID().toString(),until));
        assertNotNull(rows);assertEquals(1,rows.size());assertEquals(tenant,rows.getFirst().getTenantId());assertEquals(eventId,rows.getFirst().getEventId());
        return rows.getFirst();
    }
    private void dispatchActualWorker(OutboxEvent event) throws Exception {
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());context();
        var registered=consumers.consumersFor(event.getEventType());assertFalse(registered.isEmpty());
        for(var consumer:registered)assertTrue(plugins.isDiscoveredExtensionInstance(com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension.class,consumer));
        var method=OutboxWorkerImpl.class.getDeclaredMethod("dispatchReliable",OutboxEvent.class,String.class);method.setAccessible(true);
        try {method.invoke(worker,event,event.getLeaseToken());}catch(InvocationTargetException failure){
            if(failure.getCause() instanceof Exception actual)throw actual;throw failure;
        }finally {context();}
    }
    private void finishClaim(OutboxEvent event) {context();Integer changed=new TransactionTemplate(transactions).execute(status->outbox.markDeliveredFenced(event.getId(),event.getLeaseToken()));assertEquals(Integer.valueOf(1),changed);}
    private void assertApplied(String event,String consumer,int expected) {
        Integer count=new JdbcTemplate(postgres).queryForObject("SELECT COUNT(*) FROM ab_integration_receipt WHERE tenant_id=? AND event_id=? AND consumer_code=? AND status='applied'",Integer.class,tenant,event,consumer);
        assertEquals(expected,count);
    }
    private List<Map<String,Object>> recordsSorted(String model,Map<String,Object> filter){var rows=new ArrayList<>(records(model,filter));rows.sort(java.util.Comparator.comparing(ProcurementPublicSourceNativeFixture::pid));return rows;}
    private List<Map<String,Object>> records(String model,Map<String,Object> filter){return fixture(SYNC,()->db.query(model,filter));}
    private Map<String,Object> only(String model,Map<String,Object> filter){var rows=records(model,filter);assertEquals(1,rows.size(),model);return rows.getFirst();}
    private Map<String,Object> record(String model,String pid){return fixture(SYNC,()->db.getById(model,pid));}
    private void assertStockPosted(String warehouse,int lineCount){
        var balances=records("inv_balance",Map.of("inv_bal_warehouse_id",warehouse));assertFalse(balances.isEmpty());
        var ledger=records("inv_balance_ledger",Map.of("inv_bl_warehouse_id",warehouse));assertEquals(lineCount,ledger.size());
        assertDecimal("250",balances.stream().map(row->new BigDecimal(row.get("inv_bal_qty").toString())).reduce(BigDecimal.ZERO,BigDecimal::add));
        assertDecimal("250",ledger.stream().map(row->new BigDecimal(row.get("inv_bl_qty_delta").toString())).reduce(BigDecimal.ZERO,BigDecimal::add));
    }
    private Map<String,Object> stockSnapshot(String warehouse){return Map.of("balance",records("inv_balance",Map.of("inv_bal_warehouse_id",warehouse)),"ledger",records("inv_balance_ledger",Map.of("inv_bl_warehouse_id",warehouse)));}
    private static void assertDecimal(String expected,Object actual){assertNotNull(actual);assertEquals(0,new BigDecimal(expected).compareTo(new BigDecimal(actual.toString())));}
    private static void assertLineBusinessRetained(Map<String,Object> before,Map<String,Object> after){
        var normalized=new LinkedHashMap<>(after);for(String audit:List.of("row_version","updated_at"))normalized.put(audit,before.get(audit));
        normalized.put("pr_pol_received_qty",before.get("pr_pol_received_qty"));assertEquals(before,normalized);
    }
    private static void assertUpstreamRetained(Map<String,Object> before,Map<String,Object> after){
        for(String domain:List.of("task","source","quote","quote_line","history","submission","submission_line","allocation","execution","externalReferences"))
            {assertTrue(before.containsKey(domain));assertTrue(after.containsKey(domain));assertEquals(before.get(domain),after.get(domain),domain);}
    }
    private void verifyReceiptArtifactAdmission() throws Exception {
        var input=json.readTree(java.nio.file.Files.readAllBytes(Path.of(required("PROCUREMENT_PUBLIC_SOURCE_READINESS_INPUT"))));
        var expected=input.path("receiptPluginArtifacts");assertTrue(expected.isObject(),"Runner must seal actual receipt plugin artifacts");
        for(String id:List.of("com.auraboot.inventory","com.auraboot.procurement")){
            var wrapper=plugins.getPlugin(id);assertNotNull(wrapper);assertEquals(PluginState.STARTED,wrapper.getPluginState());
            assertEquals(sha(wrapper.getPluginPath()),expected.path(id).path("sha256").asText());
            assertEquals(wrapper.getPluginPath().toRealPath().toString(),expected.path(id).path("path").asText());
        }
        for(String model:List.of("inv_inbound","inv_inbound_line","pr_purchase_order","pr_purchase_order_line","pr_inventory_operation_request")){
            var definition=models.getModelDefinition(model).orElseThrow();assertEquals("published",definition.getStatus());
            String table=models.getTableName(model);assertNotNull(new JdbcTemplate(postgres).queryForObject("SELECT to_regclass(?)::text",String.class,table),"Actual physical relation required");
        }
    }
}
