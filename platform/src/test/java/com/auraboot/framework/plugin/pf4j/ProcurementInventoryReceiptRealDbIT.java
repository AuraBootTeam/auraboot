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
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Actual Auth/registered commands/PF4J/T01/PG; only upstream ERP is controlled loopback.
 * This fixture has no inherited business @Test methods and does not replace Source5/Order3.
 */
@SpringBootTest(classes=ProcurementInventoryReceiptRealDbIT.InventoryApplication.class,properties={
    "spring.datasource.url=${TEST_DATABASE_URL}","spring.data.redis.database=${TEST_REDIS_DATABASE}",
    "aura.plugins.dir=${PROCUREMENT_SOURCE_PLUGIN_DIR}",
    "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.slf4j.Slf4jImpl",
    "logging.level.com.auraboot.framework=INFO", "logging.level.org.mybatis=INFO",
    "logging.level.org.springframework.security=WARN", "logging.level.org.springframework.cache=WARN",
    "security.field-encryption.key=${PROCUREMENT_RECEIPT_FIELD_ENCRYPTION_KEY}"})
@ActiveProfiles("integration-test")
@Import({ProcurementPublicSourceNativeFixture.IsolatedScheduling.class,ProcurementInventoryReceiptRealDbIT.CasObservationConfiguration.class})
@EnabledIfEnvironmentVariable(named="PROCUREMENT_TRUSTED_RECEIPT_IT",matches="true")
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class ProcurementInventoryReceiptRealDbIT extends ProcurementPublicSourceNativeFixture {
    /** Inventory-only test context: explicit fixture imports survive, unrelated test configurations cannot become Core consumers. */
    @org.springframework.boot.autoconfigure.SpringBootApplication
    @org.springframework.context.annotation.ComponentScan(basePackages={"com.auraboot.framework","com.auraboot.module"},excludeFilters={
        @org.springframework.context.annotation.ComponentScan.Filter(type=org.springframework.context.annotation.FilterType.CUSTOM,classes=org.springframework.boot.context.TypeExcludeFilter.class),
        @org.springframework.context.annotation.ComponentScan.Filter(type=org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,classes=TestApplication.class),
        @org.springframework.context.annotation.ComponentScan.Filter(type=org.springframework.context.annotation.FilterType.ANNOTATION,classes=org.springframework.boot.SpringBootConfiguration.class)})
    @org.springframework.boot.autoconfigure.AutoConfigurationPackage(basePackageClasses=TestApplication.class)
    @org.mybatis.spring.annotation.MapperScan({
        "com.auraboot.framework.**.mapper",
        "com.auraboot.framework.*.dao",
        "com.auraboot.framework.*.dao.mapper",
        "com.auraboot.framework.*.mapper",
        "com.auraboot.framework.application.release",
        "com.auraboot.framework.aurabot.skill.mapper",
        "com.auraboot.framework.billing.account.mapper",
        "com.auraboot.framework.billing.catalog.mapper",
        "com.auraboot.framework.billing.metering.mapper",
        "com.auraboot.framework.billing.quota.mapper",
        "com.auraboot.framework.connector.airflow.mapper",
        "com.auraboot.framework.connector.airflow.secret.mapper",
        "com.auraboot.framework.connector.saas.oauth.mapper",
        "com.auraboot.framework.rag.mapper",
        "com.auraboot.framework.saas.account.mapper",
        "com.auraboot.framework.saas.config.mapper",
        "com.auraboot.framework.saas.license.mapper",
        "com.auraboot.module.*.mapper",
        "com.auraboot.module.meta.excel.mapper",
        "com.auraboot.smart.framework.engine.persister.database"})
    @org.springframework.transaction.annotation.EnableTransactionManagement
    static class InventoryApplication {}
    private int consumerDiagnosticSequence;
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
    @Autowired ReceiptDiscoveryObservation receiptDiscoveryObservation;
    @Autowired ReceiptReadOnlyObservation receiptReadOnlyObservation;
    @Autowired ReceiptTargetVersionObservation receiptTargetVersionObservation;
    private ReceiptOutcome lastReceiptOutcome;
    @Autowired com.auraboot.framework.common.crypto.FieldEncryptionService actualReceiptEncryption;
    @Autowired org.springframework.core.env.Environment actualReceiptEnvironment;
    private final Map<Long,com.auraboot.framework.application.tenant.MetaContext.Snapshot> formalImportPrincipals=new LinkedHashMap<>();
    private int formalImportSequence;
    private String prerequisiteActor;
    private Path originalProcurementDirectory;
    private Path originalIntegrationDirectory;
    private boolean integrationOwnerInstalled;
    @Override protected void prepareEvidence(org.junit.jupiter.api.TestInfo test) throws Exception {
        restoreProductionDirectory();
        if (test.getTestMethod().orElseThrow().getName().equals("registeredReceiptCommandsPersistLineageAndCounterExactlyOnce"))
            receiptReadBuyerPassword(System.getenv());
        super.prepareEvidence(test);
        var input=json.readTree(java.nio.file.Files.readAllBytes(Path.of(required("PROCUREMENT_PUBLIC_SOURCE_READINESS_INPUT"))));
        ProcurementInventoryNativeAdmission.validate(input, Path.of(required("PROCUREMENT_PUBLIC_SOURCE_EVIDENCE_DIR")),
                Path.of(input.path("applicationCoreRoot").asText()), actualReceiptEncryption,
                requiredAllowEmpty("PROCUREMENT_RECEIPT_FIELD_ENCRYPTION_KEY"), plugins, json,actualReceiptEnvironment.getActiveProfiles());
        writeArtifact("receipt-artifact-crypto-admission.json",Map.of("actualProofValidated",true,
                "actualEncryptionServiceProbed",true,"nativeFixtureOnly",true,"sourceSet",input.path("sourceSet")));
    }
    @Override protected <T>T fixture(String code,java.util.function.Supplier<T> work) {
        assertEquals(SYNC,code,"Inventory prerequisites cannot enter a synthetic production writer scope");
        receiptReadOnlyObservation.enter();
        try{return super.fixture(code,work);}finally{receiptReadOnlyObservation.leave();}
    }
    /** Reused tenant definitions retain their original actual creator; tested actors never inherit this context. */
    @Override protected void importDirectory(Path directory) {
        var principal=withFormalImportPrincipal(formalImportPrincipals,()->super.importDirectory(directory));
        if(caseEvidence!=null)try {
            writeArtifact("receipt-formal-import-principal-"+(++formalImportSequence)+".json",Map.of(
                    "tenantId",principal.tenantId().toString(),"userPid",principal.userPid(),"memberId",principal.memberId().toString(),
                    "sourceDirectory",directory.toString(),"purpose","Original formal import creator only; no public command authority",
                    "publicActorRoleExpanded",false,"currentActorRestored",true));
        } catch(Exception failure){throw new IllegalStateException("Cannot seal formal import identity evidence",failure);}
    }
    static com.auraboot.framework.application.tenant.MetaContext.Snapshot withFormalImportPrincipal(
            Map<Long,com.auraboot.framework.application.tenant.MetaContext.Snapshot> principals,Runnable operation) {
        var current=com.auraboot.framework.application.tenant.MetaContext.snapshot();
        assertNotNull(current);assertNotNull(current.tenantId());assertNotNull(current.userId());assertNotNull(current.memberId());
        assertNotNull(current.userPid());assertFalse(current.userPid().isBlank());
        var principal=principals.computeIfAbsent(current.tenantId(),ignored->current);
        assertEquals(current.tenantId(),principal.tenantId());
        try {com.auraboot.framework.application.tenant.MetaContext.restore(principal);operation.run();return principal;}
        finally {com.auraboot.framework.application.tenant.MetaContext.restore(current);}
    }
    static String receiptCommandPath(String code) {return "/api/meta/commands/execute/"+code;}

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
                "model.qo_procurement_category_policy_common.read","model.qo_procurement_category_supplier_common.read","prod.product.manage","model.prod_product.create","model.prod_product.update"));
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
                "pr_spc_contact_name","Local supplier contact","qo_pr_contact_state","active"));
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
            assertReceiptCounterLineRetained(original,after,receiptQuantity,extra);
        }assertEquals(immutableRequest,record("pr_inventory_operation_request",pid(request)));
        assertEquals(formal,formalSnapshot(order.quote().submission()));assertUpstreamRetained(upstream,snapshot());
        dispatchActualWorker(postedClaim);assertEquals(received,record("pr_purchase_order_line",linePid));assertApplied(postedEvent,COUNTER,1);
        finishClaim(postedClaim);
        if(scenario==Scenario.HAPPY)assertMissingCapabilityAndForeignTarget(prReceipt,requestPayload,invReceipt);
        var currentConfirmed=record("inv_inbound",invReceipt);
        var replayBefore=receiptReplayFacts(prReceipt,invReceipt,warehouse,requestEvent,postedEvent);
        assertEquals(200,receiptCommand(inventoryActor,CONFIRM,invReceipt,originalConfirmIntent(invBefore,currentConfirmed),postKey,Map.of()));
        assertReceiptOutcome(lastReceiptOutcome,200,"0",true);
        assertEquals(replayBefore,receiptReplayFacts(prReceipt,invReceipt,warehouse,requestEvent,postedEvent));
        assertEquals(409,receiptCommand(inventoryActor,CONFIRM,invReceipt,currentConfirmed,postKey,Map.of()));
        assertReceiptOutcome(lastReceiptOutcome,409,"41002",false);
        assertEquals(replayBefore,receiptReplayFacts(prReceipt,invReceipt,warehouse,requestEvent,postedEvent));
        assertEquals(stockBefore,stockSnapshot(warehouse));assertEquals(received,record("pr_purchase_order_line",linePid));
        assertApplied(requestEvent,"inventory.public-command-adapter.v1",1);assertApplied(postedEvent,COUNTER,1);
        writeArtifact("receipt-exact-command-replay.json",Map.of("sameKeyOriginalVersionReplay",true,
                "sameKeyChangedVersionHttpStatus",409,"sameKeyChangedVersionBusinessCode","41002","businessStockEventsAndVersionsUnchanged",true));
        writeArtifact("trusted-receipt-result.json",Map.of("tenantId",tenant.toString(),"orderPid",poPid,"orderLinePid",linePid,
            "prReceiptPid",prReceipt,"inventoryReceiptPid",invReceipt,"requestEventId",requestEvent,"postedEventId",postedEvent,
            "receivedQtyPerLine",receiptQuantity,"nativeExecuted",true,"externalErpSynthetic",true));
        if(scenario==Scenario.HAPPY)retainHappyBuyerReadFacts(order,poPid,linePid,prReceipt,invReceipt);
    }


    /** Separate retained P14 reader only; receiving/request/prerequisite role authorities never change. */
    static List<String> receiptReadBuyerPermissions() {
        return List.of("qo.procurement.manage","meta.command.execute","page.page.read",
                "model.s2p_sourcing_event.read","model.qo_procurement_execution_common.read",
                "model.pr_supplier_common.read","model.pr_purchase_order.read","model.pr_purchase_order_line.read",
                "model.qo_procurement_shipment_common.read","model.qo_procurement_shipment_line_common.read",
                "model.pr_purchase_request.read","model.pr_spend_category.read");
    }
    static String receiptReadBuyerPassword(Map<String,String> environment) {
        String password=environment.get("PROCUREMENT_INVENTORY_BUYER_PASSWORD");
        assertTrue(password!=null && password.length()>=12 && password.length()<=128,
                "Explicit synthetic inventory buyer credential must be supplied before fixture writes");
        return password;
    }
    private void retainHappyBuyerReadFacts(PreparedOrder prepared,String poPid,String linePid,
                                          String prReceipt,String invReceipt) throws Exception {
        var line=record("pr_purchase_order_line",linePid);var po=record("pr_purchase_order",poPid);
        assertEquals(poPid,line.get("pr_pol_order_id"));assertEquals(prepared.supplier(),po.get("pr_po_supplier"));
        assertDecimal("300.000001",line.get("pr_pol_qty"));assertDecimal("0",line.get("qo_pr_order_line_submitted_qty"));
        assertDecimal("250",line.get("pr_pol_received_qty"));assertEquals("open",po.get("qo_pr_order_state"));
        assertEquals(0,records(domainModel("shipment"),Map.of("qo_pr_shipment_order_pid",poPid)).size());
        var material=new LinkedHashMap<String,Object>();
        for(String field:List.of("material_code","material_name","specification","unit")) {
            Object value=line.get("qo_pr_order_line_"+field);assertTrue(value instanceof String && !value.toString().isBlank(),
                    "Retained order material facts must be actual nonempty native snapshot values");material.put(field,value);
        }
        context();var caller=com.auraboot.framework.application.tenant.MetaContext.snapshot();
        try {
            String username=provider+"-buyer-read",email=username+"@example.invalid";
            String password=receiptReadBuyerPassword(System.getenv());
            var user=users.signUp(email,password,"Receipt read-only buyer",username);
            var member=members.addMember(user.getId(),tenant,"active");
            String bearer=actorToken(user.getId(),user.getPid(),member.getId(),email,receiptReadBuyerPermissions(),password);
            assertTrue(bearer!=null && !bearer.isBlank(),"Real fixture authentication must issue a token");
            var assigned=userRoles.findByMemberIdAndTenantId(member.getId(),tenant);assertEquals(1,assigned.size());
            var role=roles.getRoleById(assigned.getFirst().getRoleId());assertNotNull(role);
            assertFalse(userPermissions.hasPermission(tenant,user.getId(),member.getId(),"qo.procurement.integrate"));
            assertFalse(userPermissions.hasPermission(tenant,user.getId(),member.getId(),"pr.purchase.manage"));
            assertFalse(userPermissions.hasPermission(tenant,user.getId(),member.getId(),"inv.warehouse.manage"));
            var buyer=new LinkedHashMap<String,Object>(retainedActors.getLast());
            buyer.put("tenantId",tenant.toString());buyer.put("userId",user.getId().toString());buyer.put("memberPid",member.getPid());
            buyer.put("rolePid",role.getPid());buyer.put("credentialEnvironment","PROCUREMENT_INVENTORY_BUYER_PASSWORD");
            assertEquals(receiptReadBuyerPermissions(),buyer.get("permissionCodes"));
            var facts=new LinkedHashMap<String,Object>();facts.put("kind","inventory-happy-buyer-read-prerequisites-v1");facts.put("schemaVersion",1);
            facts.put("method","registeredReceiptCommandsPersistLineageAndCounterExactlyOnce");facts.put("tenantId",tenant.toString());
            facts.put("namespace",provider);facts.put("connectionPid",connection);facts.put("accountId",provider);
            facts.put("executionPid",prepared.execution());facts.put("taskPid",task);facts.put("sourcePid",prepared.quote().source());
            facts.put("supplierPid",prepared.supplier());facts.put("orderPid",poPid);facts.put("orderLinePid",linePid);
            facts.put("erpOrderId",po.get("qo_pr_order_erp_id"));facts.put("erpBillNo",po.get("pr_po_code"));
            facts.put("orderedQty","300.000001");facts.put("submittedQty","0");facts.put("receivedQty","250");facts.put("remainingQty","300.000001");
            facts.put("orderState","open");facts.put("orderLineMaterialFacts",material);
            facts.put("orderLineMaterialFactsOrigin","Synthetic loopback source read persisted by native domain lifecycle; copied into formal order-line snapshot, not customer ERP006/007 evidence");
            facts.put("buyerActor",buyer);facts.put("expectedShipmentHistory",List.of());
            facts.put("prReceiptPid",prReceipt);facts.put("inventoryReceiptPid",invReceipt);
            Path receipt=caseEvidence.resolve("trusted-receipt-result.json").toRealPath();
            facts.put("trustedReceiptResultPath",receipt.toString());facts.put("trustedReceiptResultSha256",sha(receipt));
            facts.put("receiptWriterVerified",true);facts.put("externalERPOutcomeSynthetic",true);
            facts.put("httpBuyerProjectionExecuted",false);facts.put("browserExecuted",0);facts.put("releaseApproved",false);
            writeArtifact("receipt-happy-buyer-read-prerequisites.json",facts);
        } finally {com.auraboot.framework.application.tenant.MetaContext.restore(caller);}
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
        @org.springframework.context.annotation.Bean ReceiptDiscoveryObservation receiptDiscoveryObservation(){return new ReceiptDiscoveryObservation();}
        @org.springframework.context.annotation.Bean ReceiptReadOnlyObservation receiptReadOnlyObservation(){return new ReceiptReadOnlyObservation();}
        @org.springframework.context.annotation.Bean ReceiptTargetVersionObservation receiptTargetVersionObservation(){return new ReceiptTargetVersionObservation();}
    }
    record ReceiptOutcome(int status,String code,boolean idempotentReplay,String phaseReached) {}
    static void assertReceiptOutcome(ReceiptOutcome outcome,int status,String code,boolean replay) {
        assertNotNull(outcome);assertEquals(status,outcome.status());assertEquals(code,outcome.code());
        assertEquals(replay,outcome.idempotentReplay());if(replay)assertEquals("completed",outcome.phaseReached());
    }
    static Map<String,Object> originalConfirmIntent(Map<String,Object> original,Map<String,Object> confirmed) {
        assertEquals(original.get("pid"),confirmed.get("pid"));assertNotNull(original.get("pid"));
        assertEquals("draft",original.get("inv_in_status"));assertEquals("confirmed",confirmed.get("inv_in_status"));
        long before=new BigDecimal(assertInstanceOf(Number.class,original.get("row_version")).toString()).longValueExact();
        long after=new BigDecimal(assertInstanceOf(Number.class,confirmed.get("row_version")).toString()).longValueExact();
        assertTrue(before>=0 && after>before,"Confirmation must advance the stored version");
        return new LinkedHashMap<>(original);
    }
    /** Observes the real command CAS mapper return, without replacing its arguments or result. */
    @org.aspectj.lang.annotation.Aspect static class ReceiptTargetVersionObservation {
        private static final class Capture {
            final Long tenant;final String table,key,pid;int calls;Map<String,Object> fact;
            Capture(Long tenant,String table,String key,String pid){this.tenant=tenant;this.table=table;this.key=key;this.pid=pid;}
        }
        private final ThreadLocal<Capture> current=new ThreadLocal<>();
        void begin(Long tenant,String table,String key,String pid){assertNull(current.get());current.set(new Capture(tenant,table,key,pid));}
        Map<String,Object> finish(){var capture=current.get();assertNotNull(capture);try{assertEquals(1,capture.calls);assertNotNull(capture.fact);return capture.fact;}finally{current.remove();}}
        void clear(){current.remove();}
        @org.aspectj.lang.annotation.AfterReturning(pointcut="execution(* com.auraboot.framework.meta.mapper.DynamicDataMapper.selectTargetVersionForUpdate(..)) && args(table,key,tenant,pid)",returning="result",argNames="table,key,tenant,pid,result")
        public void returned(String table,String key,Long tenant,String pid,Object result){
            var capture=current.get();if(capture==null)return;capture.calls++;
            assertEquals(capture.tenant,tenant);assertEquals(tenant,com.auraboot.framework.application.tenant.MetaContext.getCurrentTenantId());
            assertEquals(capture.table,table);assertEquals(capture.key,key);assertEquals(capture.pid,pid);
            assertInstanceOf(List.class,result);assertTrue(((List<?>)result).isEmpty(),"Foreign target must be absent in caller tenant CAS scope");
            capture.fact=Map.of("actualTenantId",tenant.toString(),"actualTable",table,"actualPrimaryKey",key,"targetRecordPid",pid,"rowCount",0,"originalMapperObserved",true);
        }
    }
    /** Captures the original manager return within the real Registry call; never discovers a second instance. */
    @org.aspectj.lang.annotation.Aspect static class ReceiptDiscoveryObservation {
        private static final class Capture {
            int calls;
            List<com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension> instances;
        }
        private final ThreadLocal<Capture> current=new ThreadLocal<>();
        void begin(){assertNull(current.get(),"Discovery observation cannot nest");current.set(new Capture());}
        List<com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension> finish(){
            var capture=current.get();assertNotNull(capture,"Discovery observation must be active");
            try {assertEquals(1,capture.calls,"Registry must discover exactly once");return capture.instances;}
            finally {current.remove();}
        }
        void clear(){current.remove();}
        @org.aspectj.lang.annotation.AfterReturning(pointcut="execution(* com.auraboot.framework.plugin.pf4j.AuraPluginManager.getExtensionsOfType(java.lang.Class)) && args(type)",returning="result",argNames="type,result")
        public void returned(Class<?> type,Object result){
            var capture=current.get();
            if(capture==null || type!=com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension.class)return;
            assertSame(com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension.class,type);
            assertInstanceOf(List.class,result);capture.calls++;
            var instances=new ArrayList<com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension>();
            for(Object instance:(List<?>)result)instances.add(assertInstanceOf(com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension.class,instance));
            capture.instances=List.copyOf(instances);
        }
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
        var before=receiptBoundaryFacts(tenant);var receiptBefore=record("pr_purchase_receipt",receipt);var inventoryBefore=record("inv_inbound",inventoryReceipt);
        String missing=receiptActor("missing",null,PR_READS);assertEquals(403,receiptCommand(missing,REQUEST,receipt,receiptBefore,provider+"-missing",payload));
        assertEquals(before,receiptBoundaryFacts(tenant));assertEquals(receiptBefore,record("pr_purchase_receipt",receipt));assertEquals(inventoryBefore,record("inv_inbound",inventoryReceipt));
        String foreign=prepareForeignActor();observed.tenant=foreignTenant;observed.missingTargetModel="pr_purchase_receipt";observed.missingTargetPid=receipt;observed.targetNotFound.set(false);
        Map<String,Object> targetLock;
        receiptTargetVersionObservation.begin(foreignTenant,foreignReceiptTable,foreignReceiptPrimaryKey,receipt);
        try {assertEquals(409,receiptCommand(foreign,REQUEST,receipt,receiptBefore,provider+"-foreign",payload));
            assertReceiptOutcome(lastReceiptOutcome,409,"41001",false);targetLock=receiptTargetVersionObservation.finish();}
        finally {receiptTargetVersionObservation.clear();observed.tenant=tenant;observed.missingTargetPid=null;observed.missingTargetModel="s2p_sourcing_event";}
        writeArtifact("receipt-foreign-target-cas-observation.json",targetLock);
        assertEquals(before,receiptBoundaryFacts(tenant));assertEquals(foreignBefore,receiptBoundaryFacts(foreignTenant));
        assertEquals(receiptBefore,record("pr_purchase_receipt",receipt));assertEquals(inventoryBefore,record("inv_inbound",inventoryReceipt));
        writeArtifact("receipt-public-denials.json",Map.of("missingBusinessCapabilityHttpStatus",403,"foreignPublicTargetHttpStatus",409,"foreignPublicTargetBusinessCode","41001",
            "foreignTenantId",foreignTenant.toString(),"tenantScopedCasTargetObserved",true,"bothTenantDomainSnapshotsUnchanged",true));
    }
    private Long foreignTenant;private Map<String,Object> foreignBefore;
    private String foreignReceiptTable,foreignReceiptPrimaryKey;
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
            foreignReceiptTable=models.getTableName("pr_purchase_receipt");
            var primaryKey=models.getPrimaryKeyField("pr_purchase_receipt");assertNotNull(primaryKey);
            foreignReceiptPrimaryKey=primaryKey.getColumnName()==null?primaryKey.getCode():primaryKey.getColumnName();
            String bearer=receiptActor("foreign","pr.purchase.manage",PR_READS);foreignBefore=receiptBoundaryFacts(foreignTenant);
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
        var modelRows=appendReceiptCompetitorWriters(json.readTree(copy.resolve("config/models.json").toFile()));
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

    /** Evidence-owned RF copy only: no parent mutation or production command gets this fixture authority. */
    static com.fasterxml.jackson.databind.JsonNode appendReceiptCompetitorWriters(com.fasterxml.jackson.databind.JsonNode original) {
        assertTrue(original.isArray());
        var changed=original.deepCopy();var expected=Set.of("pr_purchase_order","pr_purchase_order_line");
        var seen=new java.util.HashSet<String>();
        for(var definition:changed)if(expected.contains(definition.path("code").asText())) {
            assertTrue(seen.add(definition.path("code").asText()),"Duplicate fixture writer model");
            var updates=assertInstanceOf(com.fasterxml.jackson.databind.node.ArrayNode.class,
                    definition.path("extension").path("recordCommandWriters").path("commands").path("update"));
            for(var command:updates)assertNotEquals(COMPETE_LINE,command.asText(),"Fixture writer must not exist in original source");
            updates.add(COMPETE_LINE);
        }
        assertEquals(expected,seen,"Exactly the same-owner parent and child require the fixed competitor writer");
        var restored=changed.deepCopy();
        for(var definition:restored)if(expected.contains(definition.path("code").asText())) {
            var updates=(com.fasterxml.jackson.databind.node.ArrayNode)definition.path("extension").path("recordCommandWriters").path("commands").path("update");
            assertEquals(COMPETE_LINE,updates.get(updates.size()-1).asText());updates.remove(updates.size()-1);
        }
        assertEquals(original,restored,"Only two fixed update-writer appends allowed; original prefixes/create/delete/fields/models remain exact");
        return changed;
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
        lastReceiptOutcome=new ReceiptOutcome(result.getResponse().getStatus(),safeNumericCode(response.path("code")),
                response.path("data").path("idempotentReplay").isBoolean() && response.path("data").path("idempotentReplay").booleanValue(),
                response.path("data").path("phaseReached").asText(""));
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
        try {result=mvc.perform(post(receiptCommandPath(code)).header("Authorization","Bearer "+bearer).header("X-Locale","en-US")
            .contentType("application/json").content(json.writeValueAsBytes(input))).andReturn();}
        finally {observed.publicRequestActive=false;}context();
        var response=json.readTree(result.getResponse().getContentAsByteArray());
        lastReceiptOutcome=new ReceiptOutcome(result.getResponse().getStatus(),safeNumericCode(response.path("code")),
                response.path("data").path("idempotentReplay").isBoolean() && response.path("data").path("idempotentReplay").booleanValue(),
                response.path("data").path("phaseReached").asText(""));
        writeArtifact("receipt-public-request-"+(++publicRequestSequence)+".json",Map.of("commandCode",code,"targetRecordPid",target,
            "httpStatus",result.getResponse().getStatus(),"businessCode",safeNumericCode(response.path("code")),
            "diagnostics",safeReceiptResponseDiagnostics(response),"rawResponseOrCredentialsPersisted",false));
        return result.getResponse().getStatus();
    }
    /** Persist only bounded classifications, never arbitrary exception detail, SQL, payload or credentials. */
    static Map<String,Object> safeReceiptResponseDiagnostics(com.fasterxml.jackson.databind.JsonNode response) {
        var diagnostics=new LinkedHashMap<String,Object>();
        var context=response.path("context");
        String key=context.path("messageKey").isTextual()?context.path("messageKey").textValue():null;
        if(key!=null && key.matches("\\$i18n:[A-Za-z][A-Za-z0-9_.-]{0,127}"))diagnostics.put("messageKey",key);
        String exception=context.path("exception").isTextual()?context.path("exception").textValue():null;
        if(exception!=null && List.of("BusinessException","IllegalArgumentException","IllegalStateException",
                "AccessDeniedException","ConflictException").contains(exception))diagnostics.put("exceptionType",exception);
        String phase=response.path("data").path("phaseReached").asText("");
        if(List.of("init","handler","completed","completed_dry_run").contains(phase))diagnostics.put("phaseReached",phase);
        var messages=new ArrayList<String>();
        for(String field:List.of("messageKey","detail","cause"))if(context.path(field).isTextual())messages.add(context.path(field).textValue());
        if(context.isTextual())messages.add(context.textValue());
        var signals=new ArrayList<String>();
        for(String signal:List.of("FIELD_WRITER_DENIED","RECORD_WRITER_DENIED","PERMISSION_DENIED"))
            if(messages.stream().anyMatch(message->message.matches("(?s).*(?<![A-Z0-9_])"+signal+"(?![A-Z0-9_]).*")))signals.add(signal);
        diagnostics.put("authoritySignals",signals);
        var guards=Map.of("Managed purchase-order line unit is required","MANAGED_UNIT_REQUIRED",
                "Purchase order must be approved or receiving","ORDER_STATUS_REJECTED",
                "Receipt quantity exceeds remaining purchase-order quantity","QUANTITY_EXCEEDS_REMAINING",
                "Inventory operation requires at least one line","RECEIPT_LINES_MISSING");
        for(var guard:guards.entrySet())if(messages.stream().anyMatch(message->message.equals(guard.getKey())
                || message.equals("Plugin handler execution failed: "+guard.getKey())))diagnostics.put("businessGuard",guard.getValue());
        diagnostics.put("opaqueDiagnosticPresent",context.isObject()||context.isTextual());
        diagnostics.put("rawResponseOrCredentialsPersisted",false);
        return Map.copyOf(diagnostics);
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
        List<com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension> registered,discovered;
        receiptDiscoveryObservation.begin();
        try {registered=consumers.consumersFor(event.getEventType());discovered=receiptDiscoveryObservation.finish();}
        finally {receiptDiscoveryObservation.clear();}
        assertFalse(registered.isEmpty());
        var diagnostics=new ArrayList<Map<String,Object>>();
        for(var consumer:registered){
            var artifact=plugins.observeLoadedArtifact(consumer.getClass());
            diagnostics.add(Map.of("class",consumer.getClass().getName(),"consumerCode",consumer.consumerCode(),
                "ownerArtifactSha256",artifact.map(AuraPluginManager.LoadedPluginArtifact::digest).orElse(""),
                "isDiscoveredExtensionInstance",plugins.isDiscoveredExtensionInstance(com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension.class,consumer)));
        }
        writeArtifact("receipt-consumer-discovery-"+(++consumerDiagnosticSequence)+".json",Map.of("eventType",event.getEventType(),"consumers",diagnostics,"invokedBeforeEvidence",false));
        var admission=json.readTree(java.nio.file.Files.readAllBytes(Path.of(required("PROCUREMENT_PUBLIC_SOURCE_READINESS_INPUT"))));
        assertConsumerOwnership(event.getEventType(),registered,discovered,plugins,admission.path("receiptPluginArtifacts"));
        var method=OutboxWorkerImpl.class.getDeclaredMethod("dispatchReliable",OutboxEvent.class,String.class);method.setAccessible(true);
        try {method.invoke(worker,event,event.getLeaseToken());}catch(InvocationTargetException failure){
            if(failure.getCause() instanceof Exception actual)throw actual;throw failure;
        }finally {context();}
    }
    static void assertConsumerOwnership(String eventType,
            List<com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension> registered,
            List<com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension> discovered,
            AuraPluginManager plugins,com.fasterxml.jackson.databind.JsonNode admitted) throws Exception {
        String inventory="com.auraboot.plugins.inventory.ReliableInventoryEventConsumer",counter="com.auraboot.plugins.procurement.ReliableProcurementReceiptConsumer",
                trace="com.auraboot.plugins.inventory.ReliableReceiptTraceAdapter";
        Map<String,String> expected;
        if("procurement.purchase-order.receipt-requested.v1".equals(eventType))expected=Map.of(inventory,"inventory.public-command-adapter.v1");
        else {assertEquals(POSTED,eventType);expected=Map.of(counter,COUNTER,trace,"inventory.receipt-trace-adapter.v1");}
        assertEquals(expected.size(),registered.size());
        assertEquals(expected.keySet(),registered.stream().map(c->c.getClass().getName()).collect(java.util.stream.Collectors.toSet()));
        for(var consumer:registered){
            String name=consumer.getClass().getName(),owner=name.equals(counter)?"com.auraboot.procurement":"com.auraboot.inventory";
            assertEquals(expected.get(name),consumer.consumerCode());assertTrue(consumer.supports(eventType));
            assertTrue(discovered.stream().anyMatch(actual->actual==consumer),"Exact object must come from current PF4J discovery");
            var artifact=plugins.observeLoadedArtifact(consumer.getClass()).orElseThrow();assertEquals(owner,artifact.pluginId());
            var wrapper=plugins.getPlugin(owner);assertNotNull(wrapper);assertEquals(PluginState.STARTED,wrapper.getPluginState());
            assertSame(wrapper.getPluginClassLoader(),consumer.getClass().getClassLoader());
            Path actual=artifact.path().toRealPath();assertEquals(actual,wrapper.getPluginPath().toRealPath());
            assertEquals(admitted.path(owner).path("path").asText(),actual.toString());
            String admittedSha=admitted.path(owner).path("sha256").asText();
            assertTrue(admittedSha.matches("[a-f0-9]{64}"),"Admission requires a physical lowercase SHA-256");
            assertEquals(admittedSha,sha(actual));assertEquals("sha256:"+admittedSha,artifact.digest());
            if(!name.equals(trace))assertTrue(plugins.isDiscoveredExtensionInstance(com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension.class,consumer));
        }
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
    private Map<String,Object> receiptBoundaryFacts(Long requestedTenant) {
        Long previous=tenant;
        try {
            tenant=requestedTenant;context();var facts=new LinkedHashMap<String,Object>(snapshot());
            for(String model:List.of("pr_purchase_receipt","pr_purchase_receipt_line","pr_inventory_operation_request",
                    "inv_inbound","inv_inbound_line","inv_warehouse","inv_warehouse_location","prod_product","inv_balance","inv_balance_ledger"))
                facts.put(model,records(model,Map.of()));
            var jdbc=new JdbcTemplate(postgres);
            facts.put("outbox",jdbc.queryForList("SELECT row_to_json(o)::text AS data FROM ab_outbox o WHERE tenant_id=? ORDER BY event_id",requestedTenant));
            facts.put("integrationReceipts",jdbc.queryForList("SELECT row_to_json(r)::text AS data FROM ab_integration_receipt r WHERE tenant_id=? ORDER BY event_id,consumer_code",requestedTenant));
            return facts;
        } finally {tenant=previous;context();}
    }
    private Map<String,Object> receiptReplayFacts(String procurementReceipt,String inventoryReceipt,String warehouse,String requestEvent,String postedEvent) {
        context();var jdbc=new JdbcTemplate(postgres);
        return Map.of("domain",snapshot(),"stock",stockSnapshot(warehouse),
                "procurementReceipt",record("pr_purchase_receipt",procurementReceipt),
                "procurementLines",records("pr_purchase_receipt_line",Map.of("pr_rcptl_receipt_id",procurementReceipt)),
                "inventoryReceipt",record("inv_inbound",inventoryReceipt),
                "inventoryLines",records("inv_inbound_line",Map.of("inv_in_line_receipt_id",inventoryReceipt)),
                "events",jdbc.queryForList("SELECT row_to_json(o)::text AS data FROM ab_outbox o WHERE tenant_id=? AND event_id IN (?,?) ORDER BY event_id",tenant,requestEvent,postedEvent),
                "receipts",jdbc.queryForList("SELECT row_to_json(r)::text AS data FROM ab_integration_receipt r WHERE tenant_id=? AND event_id IN (?,?) ORDER BY event_id,consumer_code",tenant,requestEvent,postedEvent));
    }
    private Map<String,Object> stockSnapshot(String warehouse){return Map.of("balance",records("inv_balance",Map.of("inv_bal_warehouse_id",warehouse)),"ledger",records("inv_balance_ledger",Map.of("inv_bl_warehouse_id",warehouse)));}
    private static void assertDecimal(String expected,Object actual){assertNotNull(actual);assertEquals(0,new BigDecimal(expected).compareTo(new BigDecimal(actual.toString())));}
    /** Counter delivery is authored by the real host SYSTEM0, not the original fixture creator. */
    static void assertReceiptCounterLineRetained(Map<String,Object> before,Map<String,Object> after,
            String expectedReceived,long versionDelta) {
        assertInstanceOf(Number.class,after.get("updated_by"));
        assertEquals(0,new BigDecimal(after.get("updated_by").toString()).compareTo(BigDecimal.ZERO),
                "Receipt counter audit must identify the actual host SYSTEM0");
        assertDecimal(expectedReceived,after.get("pr_pol_received_qty"));
        assertEquals(new BigDecimal(before.get("row_version").toString()).longValueExact()+versionDelta,
                new BigDecimal(after.get("row_version").toString()).longValueExact());
        assertFalse(java.time.LocalDateTime.parse(after.get("updated_at").toString().replace(' ','T'))
                .isBefore(java.time.LocalDateTime.parse(before.get("updated_at").toString().replace(' ','T'))));
        var normalized=new LinkedHashMap<>(after);
        normalized.put("updated_by",before.get("updated_by"));
        assertLineBusinessRetained(before,normalized);
    }

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
