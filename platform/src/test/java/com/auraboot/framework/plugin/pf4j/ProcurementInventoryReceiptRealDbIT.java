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
    "security.field-encryption.key=${PROCUREMENT_RECEIPT_FIELD_ENCRYPTION_KEY}"})
@ActiveProfiles("integration-test")
@Import({ProcurementPublicSourceNativeFixture.IsolatedScheduling.class,ProcurementInventoryReceiptRealDbIT.CasObservationConfiguration.class})
@EnabledIfEnvironmentVariable(named="PROCUREMENT_TRUSTED_RECEIPT_IT",matches="true")
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class ProcurementInventoryReceiptRealDbIT extends ProcurementPublicSourceNativeFixture {
    private static final String INITIALIZE_LINE="pr:fixture_initialize_receipt_line",COMPETE_LINE="pr:fixture_compete_receipt_line";
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
    @Autowired ReceiptReadOnlyObservation receiptReadOnlyObservation;
    @Autowired com.auraboot.framework.common.crypto.FieldEncryptionService actualReceiptEncryption;
    private String prerequisiteActor;
    private Path originalProcurementDirectory;
    private Path originalIntegrationDirectory;
    private boolean integrationOwnerInstalled;
    @Override protected void prepareEvidence(org.junit.jupiter.api.TestInfo test) throws Exception {
        restoreProductionDirectory();
        super.prepareEvidence(test);
        var input=json.readTree(java.nio.file.Files.readAllBytes(Path.of(required("PROCUREMENT_PUBLIC_SOURCE_READINESS_INPUT"))));
        ProcurementInventoryNativeAdmission.validate(input, Path.of(required("PROCUREMENT_PUBLIC_SOURCE_EVIDENCE_DIR")),
                Path.of(input.path("applicationCoreRoot").asText()), actualReceiptEncryption,
                requiredAllowEmpty("PROCUREMENT_RECEIPT_FIELD_ENCRYPTION_KEY"), plugins, json);
        writeArtifact("receipt-artifact-crypto-admission.json",Map.of("actualProofValidated",true,
                "actualEncryptionServiceProbed",true,"nativeFixtureOnly",true,"sourceSet",input.path("sourceSet")));
    }
    @Override protected <T>T fixture(String code,java.util.function.Supplier<T> work) {
        assertEquals(SYNC,code,"Inventory prerequisites cannot enter a synthetic production writer scope");
        receiptReadOnlyObservation.enter();
        try{return super.fixture(code,work);}finally{receiptReadOnlyObservation.leave();}
    }
    private static String requiredAllowEmpty(String name) {
        assertTrue(System.getenv().containsKey(name), "Matching runtime crypto value must be explicitly supplied");
        return System.getenv(name);
    }

    /** Only this inventory subclass replaces synthetic private production-writer prerequisites. */
    @Override protected void prepareInitialSourceRecords() throws Exception {
        prerequisiteActor=prerequisiteActor();
        var directories=new ArrayList<Path>();
        for(var directory:json.readTree(required("PROCUREMENT_SOURCE_IMPORT_DIRECTORIES")))directories.add(Path.of(directory.asText()));
        originalIntegrationDirectory=com.auraboot.framework.notification.sourcebinding.ProductQuoteNativeInitialization.sourceUiIntegrationOwner(directories,json);
        var ownerAppendix=com.auraboot.framework.notification.sourcebinding.ProductQuoteNativeInitialization.prepareSourceUiIntegration(
                originalIntegrationDirectory,caseEvidence.resolve("receipt-integration-owner-init"),json);
        // Mark before import so even a failed import restores the exact formal owner definition.
        integrationOwnerInstalled=true;importDirectory(Path.of(ownerAppendix.get("testDirectory").toString()));
        writeArtifact("receipt-integration-owner-initialization-appendix.json",ownerAppendix);
        var appendix=prepareReceiptDirectory();
        importDirectory(Path.of(procurementDirectory));writeArtifact("receipt-directory-initialization-appendix.json",appendix);
        String product=createRegistered(prerequisiteActor,"prod:create_product",Map.of("prod_name","Public source material",
                "prod_custom_code",provider,"prod_type","raw_material","prod_unit","pcs"));
        prerequisiteAction(prerequisiteActor,"prod:activate_product",product,Map.of());
        connection=directoryInitialize("customer_pcba_int_kingdee_connection",Map.of("customer_pcba_kd_profile_name",provider,
                "customer_pcba_kd_status","active","customer_pcba_kd_acct_id",provider,
                "customer_pcba_kd_base_url","http://127.0.0.1:"+server.getAddress().getPort(),
                "customer_pcba_kd_app_id","local-app","customer_pcba_kd_username","local-reader",
                "customer_pcba_kd_app_secret_ref","cloud-config:"+provider,"customer_pcba_kd_auth_type","app-secret"));
        directoryInitialize("customer_pcba_int_external_ref",Map.of("customer_pcba_ref_external_system","KINGDEE",
                "customer_pcba_ref_instance_key",connection,"customer_pcba_ref_account_id",provider,
                "customer_pcba_ref_external_form_id","BD_MATERIAL","customer_pcba_ref_external_id","900",
                "customer_pcba_ref_local_model_code","prod_product","customer_pcba_ref_local_pid",product));
        task=prerequisiteAction(prerequisiteActor,"qo_procurement_task_common:create",null,
                Map.of("title",provider,"connectionPid",connection)).path("recordPid").asText();
        assertFalse(task.isBlank());
    }

    @org.junit.jupiter.api.AfterEach void restoreProductionDirectory() throws Exception {
        if(originalProcurementDirectory!=null)procurementDirectory=originalProcurementDirectory.toString();
        if(integrationOwnerInstalled) {
            context();importDirectory(originalIntegrationDirectory);integrationOwnerInstalled=false;
        }
    }
    Map<String,Object> prepareReceiptDirectory() throws Exception {
        if(originalProcurementDirectory==null)originalProcurementDirectory=Path.of(procurementDirectory).toRealPath();
        Path original=originalProcurementDirectory,copy=caseEvidence.resolve("receipt-directory-init");
        var appendix=com.auraboot.framework.notification.sourcebinding.ProductQuoteNativeInitialization.prepareSourceUi(original,copy,json);
        procurementDirectory=copy.toString(); // Policy copies within this case retain its declared test writers.
        return appendix;
    }

    private String directoryInitialize(String model,Map<String,Object> facts) throws Exception {
        com.auraboot.framework.notification.sourcebinding.ProductQuoteNativeInitialization.validateSourceUiPayload(model,facts);
        return createRegistered(prerequisiteActor,
                com.auraboot.framework.notification.sourcebinding.ProductQuoteNativeInitialization.sourceUiCommand(model),facts);
    }

    static List<String> prerequisitePermissions() {
        var codes=new ArrayList<>(sourceActorPermissions(true));
        codes.addAll(List.of("qo.procurement.manage","model.pr_spend_category.read","model.pr_supplier_common.read",
                "model.qo_procurement_history_common.read","model.qo_procurement_submission_common.read",
                "model.qo_procurement_submission_line_common.read","model.qo_procurement_execution_common.read",
                "model.qo_procurement_category_policy_common.read","prod.product.manage","model.prod_product.create","model.prod_product.update"));
        return List.copyOf(codes);
    }
    private String prerequisiteActor() {
        context();String username=provider+"-receipt-prereq",email=username+"@example.invalid";
        var user=users.signUp(email,PASSWORD,"Receipt lifecycle prerequisite actor",username);
        var member=members.addMember(user.getId(),tenant,"active");
        return actorToken(user.getId(),user.getPid(),member.getId(),email,prerequisitePermissions());
    }

    private com.fasterxml.jackson.databind.JsonNode prerequisiteAction(String bearer,String code,String target,
            Map<String,Object> payload) throws Exception {
        var request=new LinkedHashMap<String,Object>();request.put("payload",payload);
        request.put("clientRequestId","receipt-prereq-"+UUID.randomUUID());
        if(target!=null){request.put("targetRecordPid",target);
            request.put("expectedVersion",fixture(SYNC,()->db.getById(commands.findByCode(code).getModelCode(),target)).get("row_version"));}
        com.auraboot.framework.application.tenant.MetaContext.clear();org.springframework.test.web.servlet.MvcResult result;
        try{result=mvc.perform(post("/api/meta/commands/execute/"+code).header("Authorization","Bearer "+bearer)
                .contentType("application/json").content(json.writeValueAsBytes(request))).andReturn();}finally{context();}
        var body=json.readTree(result.getResponse().getContentAsByteArray());
        writeArtifact("receipt-public-prerequisite-"+(++publicRequestSequence)+".json",Map.of("commandCode",code,
                "httpStatus",result.getResponse().getStatus(),"businessCode",safeNumericCode(body.path("code")),
                "rawResponseOrCredentialsPersisted",false));
        assertEquals(200,result.getResponse().getStatus());assertEquals("0",body.path("code").asText());
        assertEquals("completed",body.path("data").path("phaseReached").asText());return body.path("data").path("data");
    }

    @Override protected PreparedOrder prepareAllocatedOrder() throws Exception {
        configurePolicy();assertEquals(200,call(token,selected("11")).status());
        String source=pid(sources().getFirst());
        String category=directoryInitialize("pr_spend_category",Map.of("pr_spc_code",provider,"pr_spc_name","Local source category"));
        String supplier=directoryInitialize("pr_supplier_common",Map.of("pr_supplier_code",provider,
                "pr_supplier_name","Local source supplier","pr_supplier_status","enabled","qo_pr_supplier_account_id",provider));
        prerequisiteAction(prerequisiteActor,"qo_procurement_category_policy_common:create",null,Map.of("categoryPid",category,
                "connectionPid",connection,"supplierPids",List.of(supplier),"defaultSupplierPids",List.of(supplier)));
        var before=record("pr_purchase_request",source);
        prerequisiteAction(prerequisiteActor,"qo_procurement_task_common:classify_source",task,
                Map.of("sourcePid",source,"sourceRevision",before.get("qo_pr_source_revision"),"categoryPid",category));
        long revision=new BigDecimal(record("pr_purchase_request",source).get("qo_pr_source_revision").toString()).longValueExact();
        prerequisiteAction(prerequisiteActor,"qo_procurement_task_common:dispatch",task,
                Map.of("supplierPids",List.of(supplier),"cutoffAt","2066-11-15T08:00:00Z"));
        String quote=pid(only("s2p_bid",Map.of("s2p_bid_sourcing_event_id",task)));
        context();String email=provider+"-receipt-supplier@example.invalid";
        var supplierUser=users.signUp(email,PASSWORD,"Receipt prerequisite supplier",provider+"-receipt-supplier");
        var supplierMember=members.addMember(supplierUser.getId(),tenant,"active");
        String supplierToken=actorToken(supplierUser.getId(),supplierUser.getPid(),supplierMember.getId(),email,List.of("meta.command.execute","qo.srm.use"));
        String contact=directoryInitialize("pr_supplier_pcba_contact",Map.of("pr_spc_supplier_id",supplier,
                "pr_spc_name","Local supplier contact","qo_pr_contact_state","active"));
        directoryInitialize("qo_procurement_membership_common",Map.of("qo_pr_membership_key",provider,
                "qo_pr_membership_user_pid",supplierUser.getPid(),"qo_pr_membership_supplier_pid",supplier,
                "qo_pr_membership_contact_pid",contact,"qo_pr_membership_state","active"));
        var line=Map.<String,Object>of("sourcePid",source,"sourceRevision",revision,"unitPrice","1.25","currency","CNY",
                "unit","PCS","taxRate","0.13","taxIncluded",true,"availableQty","600","deliveryDate","2066-12-01");
        long quoteRevision=new BigDecimal(record("s2p_bid",quote).get("qo_pr_quote_revision").toString()).longValueExact();
        prerequisiteAction(supplierToken,"qo_procurement_quote_common:save_draft",quote,
                Map.of("revision",quoteRevision,"lines",List.of(line),"paymentTerms","Net 30"));
        quoteRevision=new BigDecimal(record("s2p_bid",quote).get("qo_pr_quote_revision").toString()).longValueExact();
        String submission=prerequisiteAction(supplierToken,"qo_procurement_quote_common:submit",quote,Map.of("revision",quoteRevision)).path("submissionPid").asText();
        assertFalse(submission.isBlank());
        prerequisiteAction(prerequisiteActor,"qo_procurement_task_common:allocate",task,Map.of("sourceDemands",
                List.of(Map.of("sourcePid",source,"sourceRevision",revision,"quantity","300.000001")),"allocations",
                List.of(Map.of("quotePid",quote,"sourcePid",source,"submissionPid",submission,"quantity","300.000001"))));
        var allocation=only(domainModel("allocation"),Map.of("qo_pr_allocation_source_pid",source,"qo_pr_allocation_submission_pid",submission));
        String execution=allocation.get("qo_pr_allocation_execution_pid").toString();
        directoryInitialize("customer_pcba_int_external_ref",Map.of("customer_pcba_ref_external_system","KINGDEE",
                "customer_pcba_ref_instance_key",connection,"customer_pcba_ref_account_id",provider,
                "customer_pcba_ref_external_form_id","FixtureSupplier","customer_pcba_ref_external_id","supplier-local",
                "customer_pcba_ref_local_model_code","pr_supplier_common","customer_pcba_ref_local_pid",supplier));
        baselineOrderExecution=execution;return new PreparedOrder(new Submitted(source,quote,submission,revision),execution,supplier);
    }

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
        String syncActor=orderSyncActor();
        assertEquals(200,orderSyncCall(syncActor,SYNC_ORDERS,order.execution()).status());
        configureReceiptFixtureAppendix();
        String initializer=initializationActor();
        Map<String,Object> po=only("pr_purchase_order",Map.of("qo_pr_order_erp_id","local-order-1","qo_pr_order_account_id",provider));
        String poPid=pid(po);var orderLines=new ArrayList<>(records("pr_purchase_order_line",Map.of("pr_pol_order_id",poPid)));
        orderLines.sort(java.util.Comparator.comparing(ProcurementPublicSourceNativeFixture::pid));
        assertEquals(splitRead?2:1,orderLines.size());Map<String,Object> line=orderLines.getFirst();
        var originalLines=orderLines.stream().map(LinkedHashMap::new).toList();
        String receiptQuantity=splitRead?"125":"250";
        String linePid=pid(line);assertDecimal("0",line.get("pr_pol_received_qty"));
        assertEquals("approved",po.get("pr_po_status"));assertEquals(Boolean.TRUE,line.get("pr_pol_collaboration_managed"));
        Map<String,Object> upstream=snapshot();Map<String,Object> formal=formalSnapshot(order.quote().submission());
        String warehouse=createRegistered(initializer,"inv:create_warehouse",Map.of(
            "inv_warehouse_name",provider,"inv_warehouse_type","raw_material",
            "inv_wh_lot_tracking",false,"inv_wh_sn_tracking",false));
        String location=createRegistered(initializer,"inv:create_warehouse_location",Map.of(
            "inv_wl_name",provider,"inv_wl_code",provider,"inv_wl_warehouse_id",warehouse,
            "inv_loc_type","shelving","inv_loc_status","active"));
        String prReceipt=createRegistered(initializer,"pr:create_purchase_receipt",Map.of(
            "pr_rcpt_po_id",poPid,"pr_rcpt_date","2066-12-01","pr_rcpt_warehouse_id",warehouse));
        var prLines=new ArrayList<String>();
        for(var orderedLine:orderLines)prLines.add(createRegistered(initializer,INITIALIZE_LINE,Map.of(
            "pr_rcptl_receipt_id",prReceipt,"pr_rcptl_po_line_id",pid(orderedLine),
            "pr_rcptl_product_id",orderedLine.get("pr_pol_product_id"),"pr_rcptl_qty",new BigDecimal(receiptQuantity),
            "pr_rcptl_price",BigDecimal.ONE,"pr_rcptl_amount",new BigDecimal(receiptQuantity))));
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
        if(scenario==Scenario.LATE_CAS || scenario==Scenario.HELD_LEASE)postedClaim=assertConcurrentReceiverRollback(scenario,postedClaim,originalLines,stockBefore,warehouse,syncActor);
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
        @org.springframework.context.annotation.Bean ReceiptReadOnlyObservation receiptReadOnlyObservation(){return new ReceiptReadOnlyObservation();}
    }
    /** The legacy synthetic permit is observation-only in this inventory subclass. */
    @org.aspectj.lang.annotation.Aspect static class ReceiptReadOnlyObservation {
        private final ThreadLocal<Integer> depth=ThreadLocal.withInitial(()->0);
        void enter(){depth.set(depth.get()+1);}
        void leave(){int value=depth.get()-1;if(value==0)depth.remove();else depth.set(value);}
        @org.aspectj.lang.annotation.Before("execution(* com.auraboot.framework.meta.service.DynamicDataService+.create(..)) || "
                +"execution(* com.auraboot.framework.meta.service.DynamicDataService+.update(..)) || "
                +"execution(* com.auraboot.framework.meta.service.DynamicDataService+.delete(..)) || "
                +"execution(* com.auraboot.framework.meta.service.DynamicDataService+.compareAndSet*(..)) || "
                +"execution(* com.auraboot.framework.meta.service.DynamicDataService+.batch*(..)) || "
                +"execution(* com.auraboot.framework.meta.service.DynamicDataService+.bulkCreate(..)) || "
                +"execution(* com.auraboot.framework.meta.service.DynamicDataService+.createRelations(..)) || "
                +"execution(* com.auraboot.framework.meta.service.DynamicDataService+.removeRelations(..)) || "
                +"execution(* com.auraboot.framework.meta.service.DynamicDataService+.saveWithRelations(..)) || "
                +"execution(* com.auraboot.framework.meta.service.DynamicDataService+.incrementWithinCap(..)) || "
                +"execution(* com.auraboot.framework.meta.service.DynamicDataService+.increment(..))")
        public void rejectSyntheticWrite(){assertEquals(0,depth.get(),"Synthetic fixture scope permits only read observations");}
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
    private OutboxEvent assertConcurrentReceiverRollback(Scenario scenario,OutboxEvent event,List<LinkedHashMap<String,Object>> originals,Map<String,Object> postedStock,String warehouse,String competitor) throws Exception {
        assertEquals(2,originals.size());String first=pid(originals.getFirst()),second=pid(originals.getLast());
        casObservation.arm(tenant,first);var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        var delivery=executor.submit(()->{try {dispatchActualWorker(event);return null;}finally {com.auraboot.framework.application.tenant.MetaContext.clear();}});
        try {
            assertTrue(casObservation.entered.await(10,java.util.concurrent.TimeUnit.SECONDS),"Actual first PG CAS must happen before counterexample");
            if(scenario==Scenario.LATE_CAS){
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),"The public competitor must not share the receiver transaction");
                assertEquals(200,receiptCommand(competitor,COMPETE_LINE,second,originals.getLast(),provider+"-competitor",Map.of("pr_pol_received_qty","0")),
                    "Actual Auth/registered fixture command must independently advance only the second line version");
                var committed=record("pr_purchase_order_line",second);
                assertEquals(new BigDecimal(originals.getLast().get("row_version").toString()).longValueExact()+1,new BigDecimal(committed.get("row_version").toString()).longValueExact());
                assertDecimal("0",committed.get("pr_pol_received_qty"));
                writeArtifact("receipt-competitor-authority.json",Map.of("commandCode",COMPETE_LINE,"targetRecordPid",second,
                    "actorSource","Same in-memory authenticated P12 actor that performed the actual sync_orders call; original 15 permissions unchanged",
                    "newRoleOrAllScopeAssigned",false,"publicCommandExecutorVerified",true,"independentCommittedTransactionVerified",true));
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

    /** Append only test-owned commands in an evidence-owned copy for this synthetic tenant. */
    private void configureReceiptFixtureAppendix() throws Exception {
        var directories=json.readTree(required("PROCUREMENT_SOURCE_IMPORT_DIRECTORIES"));
        var originals=new ArrayList<Path>();
        for(var directory:directories){Path candidate=Path.of(directory.asText());
            if("com.auraboot.procurement".equals(json.readTree(candidate.resolve("plugin.json").toFile()).path("pluginId").asText()))originals.add(candidate);}
        assertEquals(1,originals.size());Path original=originals.getFirst(),copy=java.nio.file.Files.createDirectory(caseEvidence.resolve("rf"));
        assertTrue(copy.toString().length()<=256,"Fixture import history path must remain bounded");
        try(var files=java.nio.file.Files.walk(original)){for(Path source:files.toList()){
            Path target=copy.resolve(original.relativize(source));if(java.nio.file.Files.isDirectory(source))java.nio.file.Files.createDirectories(target);else java.nio.file.Files.copy(source,target);}}
        String originalModelsSha=sha(original.resolve("config/models.json"));
        var modelRows=json.readTree(copy.resolve("config/models.json").toFile());var originalModelRows=modelRows.deepCopy();int matches=0;
        for(var definition:modelRows)if("pr_purchase_order_line".equals(definition.path("code").asText())){
            var updates=(com.fasterxml.jackson.databind.node.ArrayNode)definition.path("extension").path("recordCommandWriters").path("commands").path("update");
            assertFalse(updates.toString().contains(COMPETE_LINE));updates.add(COMPETE_LINE);matches++;}
        assertEquals(1,matches);
        var restoredModelRows=modelRows.deepCopy();
        for(var definition:restoredModelRows)if("pr_purchase_order_line".equals(definition.path("code").asText())){
            var updates=(com.fasterxml.jackson.databind.node.ArrayNode)definition.path("extension").path("recordCommandWriters").path("commands").path("update");updates.remove(updates.size()-1);}
        assertEquals(originalModelRows,restoredModelRows,"Only the exact test-owned writer may be appended; model owner/table and all production operations remain unchanged");
        java.nio.file.Files.write(copy.resolve("config/models.json"),json.writerWithDefaultPrettyPrinter().writeValueAsBytes(modelRows));
        var initialize=Map.of("code",INITIALIZE_LINE,"displayName:en","Native Receipt Line Initializer","type","create","modelCode","pr_purchase_receipt_line",
            "inputFields",List.of("pr_rcptl_receipt_id","pr_rcptl_po_line_id","pr_rcptl_product_id","pr_rcptl_qty","pr_rcptl_price","pr_rcptl_amount"),"permissions",List.of("pr.purchase.manage"));
        var compete=Map.of("code",COMPETE_LINE,"displayName:en","Native Receipt Line Version Competitor","type","update","modelCode","pr_purchase_order_line",
            "inputFields",List.of("pr_pol_received_qty"),"permissions",List.of("qo.procurement.integrate"),"preconditions",List.of(Map.of("field","pr_pol_collaboration_managed","operator","EQ","value",true)));
        Path initializerFile=copy.resolve("config/commands/pr_fixture_initialize_receipt_line.json"),competitorFile=copy.resolve("config/commands/pr_fixture_compete_receipt_line.json");
        java.nio.file.Files.write(initializerFile,json.writerWithDefaultPrettyPrinter().writeValueAsBytes(initialize));
        java.nio.file.Files.write(competitorFile,json.writerWithDefaultPrettyPrinter().writeValueAsBytes(compete));
        importDirectory(copy);
        assertEquals(originalModelsSha,sha(original.resolve("config/models.json")),"Production managed writer metadata remains byte-identical");
        assertEquals("pr_purchase_receipt_line",commands.findByCode(INITIALIZE_LINE).getModelCode());
        assertEquals("pr_purchase_order_line",commands.findByCode(COMPETE_LINE).getModelCode());
        assertEquals(tenant,commands.findByCode(COMPETE_LINE).getTenantId());
        writeArtifact("receipt-test-owned-command-appendix.json",Map.of("tenantId",tenant.toString(),"metadataDiffersFromProduction",true,
            "canonicalPluginId","com.auraboot.procurement","originalModelsSha256",originalModelsSha,
            "configuredModelsSha256",sha(copy.resolve("config/models.json")),"initializerSha256",sha(initializerFile),"competitorSha256",sha(competitorFile),
            "productionWriterListChanged",false,"fixtureManagedUpdateWriterAppended",COMPETE_LINE));
    }

    /** Exact functional three-code/self fixture actor. No bootstrap, buyer or ALL authority. */
    private String initializationActor() throws Exception {
        context();String username=provider+"-initializer",email=username+"@example.invalid";
        var user=users.signUp(email,PASSWORD,"Receipt fixture initializer",username);var member=members.addMember(user.getId(),tenant,"active");
        var role=new com.auraboot.framework.rbac.entity.Role();role.setPid(com.auraboot.framework.common.util.UniqueIdGenerator.generate());
        role.setCode(username);role.setName(username);role.setType("custom");role.setScopeType("tenant");role.setStatus("active");role.setTenantId(tenant);
        role.setIsDefault(false);role.setIsSystem(false);role.setDeletedFlag(false);role.setPriority(100);role.setCreatedAt(java.time.Instant.now());role.setUpdatedAt(java.time.Instant.now());role=roles.createRole(role);
        var codes=List.of("meta.command.execute","pr.purchase.manage","inv.warehouse.manage");var ids=new ArrayList<Long>();
        for(String code:codes){var permission=permissions.findByCode(code);assertNotNull(permission);ids.add(permission.getId());}
        assertTrue(roles.assignPermissions(role.getId(),ids));roles.setDefaultDataScopeType(role.getId(),"self");
        assertTrue(userRoles.assignRolesToMember(member.getId(),List.of(role.getId()),tenant,userId));
        com.auraboot.framework.application.tenant.MetaContext.setContext(tenant,user.getId(),user.getPid(),provider);
        com.auraboot.framework.application.tenant.MetaContext.setMemberId(member.getId());userPermissions.evictUserPermissions(tenant,user.getId());
        assertEquals(List.of(role.getId()),userRoles.findByMemberIdAndTenantId(member.getId(),tenant).stream().map(com.auraboot.framework.rbac.entity.UserRole::getRoleId).toList());
        assertFalse(userPermissions.hasPermission(tenant,user.getId(),member.getId(),"*"));
        for(String code:codes)assertTrue(userPermissions.hasPermission(tenant,user.getId(),member.getId(),code));
        assertFalse(userPermissions.hasPermission(tenant,user.getId(),member.getId(),"qo.procurement.manage"));
        var request=new com.auraboot.framework.auth.dto.AuthenticationRequest();request.setEmail(email);request.setPassword(PASSWORD);var authenticated=authentication.authenticate(request);
        assertEquals(tenant,authenticated.getTenantId());assertEquals(user.getId(),authenticated.getUserId());assertNotNull(authenticated.getJwt());
        writeArtifact("receipt-initializer-auth.json",Map.of("tenantId",tenant.toString(),"userPid",user.getPid(),"memberPid",member.getPid(),
            "roleCode",role.getCode(),"permissionCodes",codes,"dataScopeType","self","authenticationVerified",true,"bootstrapPrincipalUsedForPublicRequest",false));
        context();return authenticated.getJwt();
    }

    private String createRegistered(String bearer,String code,Map<String,Object> payload) throws Exception {
        com.auraboot.framework.application.tenant.MetaContext.clear();org.springframework.test.web.servlet.MvcResult result;
        try {result=mvc.perform(post("/api/meta/commands/execute/"+code).header("Authorization","Bearer "+bearer).header("X-Locale","en-US")
            .contentType("application/json").content(json.writeValueAsBytes(Map.of("payload",payload,"clientRequestId",provider+"-create-"+(++publicRequestSequence))))).andReturn();}
        finally {context();}
        var response=json.readTree(result.getResponse().getContentAsByteArray());
        writeArtifact("receipt-public-create-"+publicRequestSequence+".json",Map.of("commandCode",code,"httpStatus",result.getResponse().getStatus(),
            "businessCode",safeNumericCode(response.path("code")),"rawResponseOrCredentialsPersisted",false));
        assertEquals(200,result.getResponse().getStatus());assertEquals("0",response.path("code").asText());
        assertEquals("completed",response.path("data").path("phaseReached").asText());
        String created=response.path("data").path("data").path("recordPid").asText();assertFalse(created.isBlank(),"Registered create must return actual public recordPid");return created;
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
        com.auraboot.framework.application.tenant.MetaContext.clear();
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
