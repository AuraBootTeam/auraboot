package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.AuthenticationRequest;
import com.auraboot.framework.cloudconfig.dto.CloudConfigSaveRequest;
import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.notification.sourcebinding.S06NativeEncryptionAdmission;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Independent retained protocol/PG lifecycle; never an original SupplierUI or customer ERP receipt. */
@SpringBootTest(classes=TestApplication.class,properties={
        "spring.datasource.url=${TEST_DATABASE_URL}","spring.data.redis.database=${TEST_REDIS_DATABASE}",
        "spring.datasource.hikari.connection-init-sql=SET TIME ZONE 'UTC'",
        "aura.plugins.dir=${PROCUREMENT_SOURCE_PLUGIN_DIR}",
        "logging.level.com.auraboot.framework=INFO","logging.level.org.mybatis=INFO",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.slf4j.Slf4jImpl",
        "logging.level.org.springframework.security=WARN","logging.level.org.springframework.cache=WARN",
        "spring.jpa.show-sql=false"})
@ActiveProfiles("integration-test")
@Import(ProcurementPublicSourceNativeFixture.IsolatedScheduling.class)
@EnabledIfEnvironmentVariable(named="PROCUREMENT_QUOTE_LIFECYCLE_IT",matches="true")
class ProcurementQuoteLifecycleNativeIT extends ProcurementPublicSourceNativeFixture {
    static final String MAIN="d60a9f0acdfce618d4817a7a74da24d61f8b2e5d";
    static final String DIRECTORY="qo_procurement_task_common:sync_directory";
    static final List<String> MODELS=List.of("s2p_sourcing_event","pr_purchase_request","s2p_bid",
            "qo_procurement_quote_line_common","qo_procurement_history_common","qo_procurement_submission_common",
            "qo_procurement_submission_line_common","qo_procurement_allocation_common","qo_procurement_execution_common",
            "customer_pcba_int_external_ref","pr_supplier_common","pr_supplier_pcba_contact","qo_procurement_membership_common",
            "prod_product","pr_spend_category","qo_procurement_category_policy_common","qo_procurement_category_supplier_common",
            "customer_pcba_int_kingdee_connection","pr_purchase_order","pr_purchase_order_line",
            "qo_procurement_address_common","qo_procurement_shipment_common","qo_procurement_shipment_line_common");
    static final List<String> SOURCE_FILES=java.util.stream.Stream.concat(
            java.util.stream.Stream.of("ProcurementQuoteLifecycleNativeIT","ProcurementQuoteLifecycleErp","ProcurementQuoteLifecycleContractTest",
                    "ProcurementPublicSourceNativeFixture","ProcurementSupplierUiInitialization","ProcurementSupplierUiFixtureProducerIT",
                    "ProcurementSourceUiFixtureProducerIT","ProcurementOrderUiFixtureProducerIT")
                    .map(name->"platform/src/test/java/com/auraboot/framework/plugin/pf4j/"+name+".java"),
            java.util.stream.Stream.of("S06NativeEncryptionAdmission","ProductQuoteNativeInitialization")
                    .map(name->"platform/src/test/java/com/auraboot/framework/notification/sourcebinding/"+name+".java")).toList();
    static final List<String> INITIAL_MODELS=List.of("prod_product","customer_pcba_int_kingdee_connection","pr_spend_category","pr_purchase_request","qo_procurement_membership_common");
    @Autowired CommandExecutor executor;
    @Autowired MetaModelService metadata;
    @Autowired FieldEncryptionService encryption;
    private ProcurementQuoteLifecycleErp erp;
    private JsonNode selected;
    private String makerJwt,supplierJwt,product,category,source,quote;
    private Map<String,Object> crypto;
    private final List<Map<String,Object>> actions=new ArrayList<>(),initializers=new ArrayList<>(),importProofs=new ArrayList<>();
    private String stage="before-admission";
    private boolean completed;

    @DynamicPropertySource static void matchingKey(DynamicPropertyRegistry registry) {
        registry.add("security.field-encryption.key",()->S06NativeEncryptionAdmission.requiredKey(System.getenv()));
    }
    @Override protected void admitEncryption() throws Exception {
        crypto=S06NativeEncryptionAdmission.admit(System.getenv(),json,encryption,getClass(),application.getEnvironment().getActiveProfiles());
        writeArtifact("matching-encryption-admission.json",crypto);
    }
    static void validateInput(JsonNode input) {
        assertEquals("procurement-quote-lifecycle-input-v1",input.path("kind").asText());
        assertTrue(input.path("schemaVersion").isIntegralNumber() && input.path("schemaVersion").canConvertToInt());assertEquals(1,input.path("schemaVersion").intValue());
        assertTrue(input.path("quoteLifecycleEnabled").isBoolean() && input.path("quoteLifecycleEnabled").booleanValue());
        assertEquals(MAIN,input.path("sourceSet").path("oss").asText());
    }
    static String initCommand(String model) {
        if(!INITIAL_MODELS.contains(model)) throw new IllegalArgumentException("Bounded lifecycle initializer required");
        return model+":init_quote_lifecycle";
    }
    @Override @BeforeEach void prepare(TestInfo info) throws Exception {
        Path input=Path.of(required("PROCUREMENT_QUOTE_LIFECYCLE_INPUT")).toRealPath();
        assertEquals(required("PROCUREMENT_QUOTE_LIFECYCLE_INPUT_SHA256"),sha(input));
        assertTrue(input.startsWith(Path.of(required("PROCUREMENT_PUBLIC_SOURCE_EVIDENCE_DIR")).toRealPath()));
        selected=json.readTree(Files.readAllBytes(input));validateInput(selected);prepareEvidence(info);admitEncryption();
        Path app=Path.of(selected.path("applicationCoreRoot").asText()).toRealPath();
        assertEquals(app.toString(),Path.of(required("S06_NATIVE_APPLICATION_ROOT")).toRealPath().toString());
        assertEquals(selected.path("applicationCoreCommit").asText(),gitRead(app,"rev-parse","HEAD").strip());
        assertEquals("",gitRead(app,"status","--porcelain").strip());
        assertEquals("",gitRead(app,"diff",MAIN,"HEAD","--","platform/src/main").strip());
        assertEquals(app.resolve("platform/build/classes/java/test").toRealPath(),Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        assertEquals(selected.path("sourceSet"),json.readTree(Path.of(required("PROCUREMENT_PUBLIC_SOURCE_READINESS_INPUT")).toFile()).path("sourceSet"));
        assertTrue(selected.path("nativeSourceSha256").isObject());
        assertEquals(Set.copyOf(SOURCE_FILES),json.convertValue(selected.path("nativeSourceSha256"),Map.class).keySet());
        for(var file:selected.path("nativeSourceSha256").properties()) assertEquals(file.getValue().asText(),sha(app.resolve(file.getKey())));
        try(var sql=postgres.getConnection()) {
            var url=java.net.URI.create(sql.getMetaData().getURL().substring(5));
            assertEquals("127.0.0.1",url.getHost());assertEquals(5432,url.getPort());assertEquals("/enterprise_22",url.getPath());
            assertEquals("enterprise_22",sql.getCatalog());
        }
        assertEquals("13",required("TEST_REDIS_DATABASE"));
        provider="ql_"+UUID.randomUUID().toString().replace("-","").substring(0,20);
        var bootstrap=users.signUp(provider+"-bootstrap@example.invalid",password(),"Controlled lifecycle bootstrap",provider+"_bootstrap");
        userId=bootstrap.getId();actor=bootstrap.getPid();
        var organization=new Tenant();organization.setPid(com.auraboot.framework.common.util.UniqueIdGenerator.generate());
        organization.setName(provider);organization.setDisplayName(provider);organization.setStatus("active");tenant=tenants.createTenant(organization).getId();
        assertNotEquals(Long.valueOf(required("TEST_TENANT_ID")),tenant);memberId=members.addMember(userId,tenant,"active").getId();context();
        assertTrue(tenantBootstrap.bootstrapTenant(tenant,userId).isSuccess());
        retainedBootstrapPrincipal=Map.of("userPid",actor,"memberId",memberId.toString(),"usedForTestedPublicRequest",false);
        var directories=new ArrayList<Path>();for(JsonNode path:json.readTree(required("PROCUREMENT_SOURCE_IMPORT_DIRECTORIES"))) directories.add(Path.of(path.asText()).toRealPath());
        assertEquals(23,directories.size());stage="formal-original-import";
        for(Path directory:directories) formalImport(directory);
        var fields=new LinkedHashMap<String,List<String>>();for(String model:INITIAL_MODELS) fields.put(model,ProcurementSupplierUiInitialization.FIELDS.get(model));
        var appendix=ProcurementSupplierUiInitialization.prepareOwned(directories,caseEvidence.resolve("lifecycle-appendix"),json,fields,ProcurementQuoteLifecycleNativeIT::initCommand);
        writeArtifact("initialization-appendix.json",appendix);
        @SuppressWarnings("unchecked") var owners=(List<Map<String,Object>>)appendix.get("ownerDirectories");
        for(var owner:owners) formalImport(Path.of(owner.get("testDirectory").toString()));
        imports.verifyImportReferenceIntegrity();validateAppendix(appendix,fields);
        var maker=users.signUp(provider+"-maker@example.invalid",password(),"Controlled lifecycle maker",provider+"_maker");
        var makerMember=members.addMember(maker.getId(),tenant,"active");
        makerJwt=actorToken(maker.getId(),maker.getPid(),makerMember.getId(),maker.getEmail(),ProcurementSupplierUiFixtureProducerIT.INITIALIZER_PERMISSIONS,password());
        userId=maker.getId();actor=maker.getPid();memberId=makerMember.getId();context();
        mvc=MockMvcBuilders.webAppContextSetup(application).addFilters(springSecurityFilterChain).build();
        erp=new ProcurementQuoteLifecycleErp(provider);erp.start();
        var secret=new CloudConfigSaveRequest();secret.setConfigLevel("tenant");secret.setServiceType("integration");secret.setProviderCode(provider);
        secret.setEnabled(true);secret.setPriority(1);secret.setConfig(json.writeValueAsString(Map.of("appSecret",ProcurementQuoteLifecycleErp.SECRET)));configs.saveConfig(secret);
        product=initialize("prod_product",Map.of("prod_code",provider,"prod_name","Controlled lifecycle material","prod_type","raw_material","prod_unit","pcs","prod_status","active"));
        connection=initialize("customer_pcba_int_kingdee_connection",Map.of("customer_pcba_kd_profile_name",provider,"customer_pcba_kd_status","active",
                "customer_pcba_kd_acct_id",provider,"customer_pcba_kd_base_url",erp.baseUrl(),"customer_pcba_kd_app_id",provider,"customer_pcba_kd_username",provider,
                "customer_pcba_kd_app_secret_ref","cloud-config:"+provider,"customer_pcba_kd_auth_type","app-secret"));
        category=initialize("pr_spend_category",Map.of("pr_spc_code",provider,"pr_spc_name","Controlled lifecycle category"));
        var taskResult=action(makerJwt,"qo_procurement_task_common:create",null,Map.of("title",provider,"connectionPid",connection,"deliveryDate","2066-12-01"),200);
        task=taskResult.path("recordPid").asText();assertFalse(task.isBlank());retainedTaskPids.add(task);
        source=initialize("pr_purchase_request",sourceFacts());
        Path owner=owners.stream().map(row->Path.of(row.get("testDirectory").toString())).filter(path->Files.isRegularFile(path.resolve("config/commands/supplier-directory.json"))).findFirst().orElseThrow();
        configureDirectory(owner);
    }
    private void formalImport(Path directory) throws Exception {
        var preview=imports.parseDirectory(directory.toString(),true);String name="import-"+importProofs.size();writeArtifact(name+"-preview.json",preview);assertTrue(preview.isValid());
        var request=com.auraboot.framework.plugin.dto.imports.ImportRequest.builder().importId(preview.getImportId())
                .conflictStrategy(com.auraboot.framework.plugin.dto.imports.ImportRequest.ConflictStrategy.OVERWRITE)
                .autoPublishFields(true).autoPublishModels(true).autoPublishCommands(true).autoPublishPages(true).createResourcePermissions(true).build();
        var result=imports.execute(preview.getImportId(),request);writeArtifact(name+"-result.json",result);assertTrue(result.isSuccess());
        importProofs.add(Map.of("directory",directory.toString(),"pluginPid",result.getPluginPid(),"previewSha256",sha(caseEvidence.resolve(name+"-preview.json")),"resultSha256",sha(caseEvidence.resolve(name+"-result.json"))));
    }
    private void validateAppendix(Map<String,Object> appendix,Map<String,List<String>> fields) throws Exception {
        JsonNode proof=json.valueToTree(appendix);
        for(var item:fields.entrySet()) {
            var command=commands.findByCode(initCommand(item.getKey()));assertEquals(tenant,command.getTenantId());assertEquals(item.getKey(),command.getModelCode());
            var definition=metadata.getModelDefinitionFromDb(item.getKey()).orElseThrow();
            for(JsonNode model:proof.path("exactModelCreateWriterAppendix")) if(item.getKey().equals(model.path("model").asText()))
                assertEquals(model.path("testPolicy"),json.valueToTree(definition.getExtension().get("recordCommandWriters")));
            for(String name:item.getValue()) {
                var field=definition.getFields().stream().filter(f->name.equals(f.getCode())).findFirst().orElseThrow();
                for(JsonNode expected:proof.path("exactFieldWriterAppendix")) if(name.equals(expected.path("field").asText()) && expected.path("models").toString().contains('"'+item.getKey()+'"'))
                    assertEquals(expected.path("testWriters"),json.valueToTree(field.getAllowedWriterCommands()));
            }
        }
        writeArtifact("actual-initializer-model-field-projection.json",Map.of("verified",true,"models",fields.keySet()));
    }
    private void configureDirectory(Path owner) throws Exception {
        Path file=owner.resolve("config/commands/supplier-directory.json");var array=json.readTree(file.toFile());int changed=0;
        for(JsonNode command:array) if(DIRECTORY.equals(command.path("code").asText())) {
            var params=(ObjectNode)command.path("handlerParams");assertTrue(params.path("procurementSupplierDirectoryPolicies").isEmpty());assertTrue(params.path("procurementSupplierPaymentPolicies").isEmpty());
            params.set("procurementSupplierDirectoryPolicies",json.valueToTree(List.of(Map.of("connectionPid",connection,"accountId",provider,"defaultCallingCode","+86",
                    "supplierStates",List.of(Map.of("documentStatus","C","forbidStatus","A","enabled",true)),"contactStates",List.of(Map.of("forbidStatus","A","active",true))))));
            params.set("procurementSupplierPaymentPolicies",json.valueToTree(List.of(Map.of("connectionPid",connection,"accountId",provider,
                    "conditionStates",List.of(Map.of("documentStatus","C","forbidStatus","A","enabled",true))))));changed++;
        }
        assertEquals(1,changed);json.writeValue(file.toFile(),array);formalImport(owner);
        var actual=json.readTree(commands.findByCode(DIRECTORY).getExecutionConfig());
        assertEquals(array.get(0).path("handlerParams"),actual.path("handlerParams"));writeArtifact("directory-command-policy.json",actual);
    }
    private String initialize(String model,Map<String,Object> values) throws Exception {
        assertEquals(Set.copyOf(ProcurementSupplierUiInitialization.FIELDS.get(model)),values.keySet());
        var auth=new AuthenticationRequest();auth.setEmail(users.findByPid(actor).getEmail());auth.setPassword(password());
        var authenticated=authentication.authenticate(auth);assertEquals(tenant,authenticated.getTenantId());assertEquals(userId,authenticated.getUserId());
        var saved=MetaContext.snapshot();
        try {
            context();var request=new CommandExecuteRequest();request.setPayload(values);request.setClientRequestId("ql-init-"+UUID.randomUUID());
            var result=executor.execute(initCommand(model),request);assertEquals("completed",result.getPhaseReached());
            String pid=result.getData().get("recordPid").toString();assertFalse(pid.isBlank());initializers.add(Map.of("command",initCommand(model),"recordPid",pid,"authenticationVerified",true));return pid;
        } finally {MetaContext.clear();MetaContext.restore(saved);}
    }
    private JsonNode action(String jwt,String code,String target,Map<String,Object> payload,int status) throws Exception {
        var request=new LinkedHashMap<String,Object>();request.put("payload",payload);request.put("clientRequestId","ql-action-"+UUID.randomUUID());
        if(target!=null){request.put("targetRecordPid",target);request.put("expectedVersion",raw(commands.findByCode(code).getModelCode(),target).path("row_version").longValue());}
        MetaContext.clear();
        try {
            var response=mvc.perform(post("/api/meta/commands/execute/"+code).header("Authorization","Bearer "+jwt).contentType("application/json").content(json.writeValueAsBytes(request))).andReturn();
            assertEquals(status,response.getResponse().getStatus());var body=json.readTree(response.getResponse().getContentAsByteArray());
            if(status==200) ProcurementSupplierUiFixtureProducerIT.requireCompletedCommandResponse(body);
            else {assertTrue(body.path("code").isTextual());assertEquals("35000",body.path("code").asText());}
            actions.add(Map.of("command",code,"httpStatus",status,"target",target==null?"create":target,"clientRequestId",request.get("clientRequestId")));
            return body.path("data").path("data");
        } finally {context();}
    }
    @Test void quoteLifecycle() throws Exception {
        stage="directory-production-sync";
        var directory=action(makerJwt,DIRECTORY,task,Map.of("selectedSupplierIds",List.of("701")),200);
        assertTrue(directory.path("paymentTermsSynchronized").asBoolean());assertEquals(1,directory.path("items").size());var item=directory.path("items").get(0);
        assertEquals("701",item.path("supplierId").asText());String supplier=item.path("supplierPid").asText();assertEquals(1,item.path("contacts").size());
        var contact=item.path("contacts").get(0);assertEquals("901",contact.path("externalContactId").asText());String contactPid=contact.path("contactPid").asText();
        var supplierRow=raw("pr_supplier_common",supplier);assertTrue(supplierRow.path("pr_supplier_directory_managed").asBoolean());
        JsonNode master=parseStored(supplierRow.get("qo_pr_supplier_payment_conditions_snapshot"));
        assertEquals(2,master.path("financeEntries").size());assertEquals(2,master.path("paymentConditions").size());
        var refs=rows("customer_pcba_int_external_ref");assertEquals(2,refs.size());
        assertDirectoryReference(refs,"", "pr_supplier_common",supplier);
        assertDirectoryReference(refs,"901", "pr_supplier_pcba_contact",contactPid);
        var supplierUser=users.signUp(provider+"-supplier@example.invalid",password(),"Controlled lifecycle contact",provider+"_supplier");
        var member=members.addMember(supplierUser.getId(),tenant,"active");
        initialize("qo_procurement_membership_common",Map.of("qo_pr_membership_key",provider,"qo_pr_membership_user_pid",supplierUser.getPid(),
                "qo_pr_membership_supplier_pid",supplier,"qo_pr_membership_contact_pid",contactPid,"qo_pr_membership_state","active"));
        supplierJwt=actorToken(supplierUser.getId(),supplierUser.getPid(),member.getId(),supplierUser.getEmail(),ProcurementSupplierUiFixtureProducerIT.SUPPLIER_PERMISSIONS,password());context();
        action(makerJwt,"qo_procurement_category_policy_common:create",null,Map.of("categoryPid",category,"connectionPid",connection,"supplierPids",List.of(supplier),"defaultSupplierPids",List.of(supplier)),200);
        action(makerJwt,"qo_procurement_task_common:classify_source",task,Map.of("sourcePid",source,"sourceRevision",0,"categoryPid",category),200);
        action(makerJwt,"qo_procurement_task_common:dispatch",task,Map.of("supplierPids",List.of(supplier),"cutoffAt","2066-11-15T08:00:00Z"),200);
        var quotes=rows("s2p_bid");assertEquals(1,quotes.size());quote=quotes.get(0).path("pid").asText();
        assertTrue(rows("qo_procurement_allocation_common").isEmpty());assertTrue(rows("qo_procurement_execution_common").isEmpty());
        stage="formal-first-submission";var view=view();var firstSelection=selection(view,"801");var draft=snapshotOwned();
        var forged=new LinkedHashMap<>(firstSelection);forged.put("sourceDigest","0".repeat(64));
        action(supplierJwt,"qo_procurement_quote_common:save_draft",quote,savePayload(view,"1.10",forged),400);assertEquals(draft,snapshotOwned());
        action(supplierJwt,"qo_procurement_quote_common:save_draft",quote,savePayload(view,"1.10",firstSelection),200);
        view=view();var saved=snapshotOwned();
        action(supplierJwt,"qo_procurement_quote_common:submit",quote,Map.of("revision",-1),400);assertEquals(saved,snapshotOwned());
        var first=action(supplierJwt,"qo_procurement_quote_common:submit",quote,Map.of("revision",view.path("quote").path("revision").asText()),200);
        String firstPid=first.path("submissionPid").asText();assertFalse(firstPid.isBlank());
        var frozen=raw("qo_procurement_submission_common",firstPid);var frozenLines=submissionLines(firstPid);assertEquals(1,frozenLines.size());
        verifyTerms(frozen,master,"801");assertEquals(1,frozen.path("qo_pr_submission_sequence").intValue());
        stage="return-and-second-submission";
        action(makerJwt,"qo_procurement_quote_common:return",quote,Map.of("revision",first.path("revision").asText(),"reason","Controlled quotation revision","cutoffAt","2066-11-16T08:00:00Z"),200);
        assertEquals(frozen,raw("qo_procurement_submission_common",firstPid));assertEquals(frozenLines,submissionLines(firstPid));
        view=view();assertEquals("draft",view.path("quote").path("state").asText());
        action(supplierJwt,"qo_procurement_quote_common:save_draft",quote,savePayload(view,"1.20",selection(view,"802")),200);view=view();
        var second=action(supplierJwt,"qo_procurement_quote_common:submit",quote,Map.of("revision",view.path("quote").path("revision").asText()),200);
        String secondPid=second.path("submissionPid").asText();assertFalse(secondPid.isBlank());assertNotEquals(firstPid,secondPid);
        var newFormal=raw("qo_procurement_submission_common",secondPid);assertEquals(2,newFormal.path("qo_pr_submission_sequence").intValue());verifyTerms(newFormal,master,"802");
        assertEquals(frozen,raw("qo_procurement_submission_common",firstPid));assertEquals(frozenLines,submissionLines(firstPid));
        assertEquals(secondPid,raw("s2p_bid",quote).path("qo_pr_quote_latest_submission_pid").asText());
        var beforeReplay=snapshotOwned();assertSubmissionReplay(second,action(supplierJwt,"qo_procurement_quote_common:submit",quote,Map.of("revision",second.path("revision").asText()),200));
        assertEquals(beforeReplay,snapshotOwned());assertEquals(2,rows("qo_procurement_submission_common").size());
        assertEquals(Map.of("login",1,"supplierQuery",1,"masterViews",3,"rejected",0),erp.counts());
        var receipt=new LinkedHashMap<String,Object>();receipt.put("kind","procurement-independent-quote-lifecycle-native-v1");receipt.put("schemaVersion",1);
        receipt.put("tenantId",tenant.toString());receipt.put("namespace",provider);receipt.put("sourceSet",selected.path("sourceSet"));receipt.put("matchingEncryptionAdmission",crypto);
        receipt.put("quotePid",quote);receipt.put("firstSubmission",frozen);receipt.put("firstSubmissionLines",frozenLines);receipt.put("secondSubmission",newFormal);
        receipt.put("secondSubmissionLines",submissionLines(secondPid));receipt.put("snapshot",snapshotOwned());receipt.put("providerCounts",erp.counts());
        receipt.put("actors",retainedActors);receipt.put("initializationActions",initializers);receipt.put("publicActions",actions);receipt.put("formalImports",importProofs);
        receipt.put("retainedAfterNative",true);receipt.put("browserExecuted",0);receipt.put("actualCustomerERPVerified",false);receipt.put("actualWechatMessages",0);
        receipt.put("allocatedReturnNegativeDidNotRun",true);writeArtifact("quote-lifecycle-receipt.json",receipt);completed=true;stage="native-lifecycle-completed";
    }
    static void assertSubmissionReplay(JsonNode first,JsonNode replay) {
        assertTrue(first.isObject() && replay.isObject(),"Submission responses must be objects");
        var expected=Set.of("quotePid","recordPid","state","submissionPid","revision");
        var firstKeys=new HashSet<String>();first.fieldNames().forEachRemaining(firstKeys::add);
        var replayKeys=new HashSet<String>();replay.fieldNames().forEachRemaining(replayKeys::add);
        assertEquals(expected,firstKeys,"Initial submission response fields changed");
        assertEquals(expected,replayKeys,"Replay submission response fields changed");
        for(String field:List.of("quotePid","recordPid","state","submissionPid")) {
            assertTrue(first.get(field).isTextual() && !first.get(field).textValue().isBlank(),"Submission identity must be nonblank text");
            assertEquals(first.get(field),replay.get(field),"Replay submission identity changed: "+field);
        }
        assertEquals(canonicalRevision(first.get("revision")),canonicalRevision(replay.get("revision")),"Replay revision changed");
    }
    private static long canonicalRevision(JsonNode value) {
        assertNotNull(value,"Submission revision required");
        if(value.isIntegralNumber()) {
            assertTrue(value.canConvertToLong() && value.longValue()>=0,"Submission revision outside nonnegative long range");
            return value.longValue();
        }
        assertTrue(value.isTextual() && value.textValue().length()<=19
                && value.textValue().matches("0|[1-9][0-9]*"),"Submission revision must be a canonical nonnegative integer");
        var number=new java.math.BigInteger(value.textValue());
        assertTrue(number.compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE))<=0,"Submission revision outside nonnegative long range");
        return number.longValueExact();
    }
    private JsonNode view() throws Exception{return action(supplierJwt,"qo_procurement_quote_common:view_mine",quote,Map.of(),200);}
    static Map<String,Object> selection(JsonNode view,String condition) {
        var found=new ArrayList<JsonNode>();for(JsonNode option:view.path("paymentConditionOptions")) if(condition.equals(option.path("conditionId").asText())) found.add(option);
        assertEquals(1,found.size());var option=found.get(0);return Map.of("financeEntryId",option.path("financeEntryId").asText(),"conditionId",condition,"sourceDigest",option.path("sourceDigest").asText());
    }
    private Map<String,Object> savePayload(JsonNode view,String price,Map<String,Object> condition) {
        assertEquals(1,view.path("lines").size());var line=view.path("lines").get(0);return Map.of("revision",view.path("quote").path("revision").asText(),"paymentCondition",condition,
                "lines",List.of(Map.of("sourcePid",source,"sourceRevision",line.path("demand").path("revision").asText(),"unitPrice",price,"currency","CNY","unit","PCS","taxRate","0.13","taxIncluded",true,"availableQty","100","deliveryDate","2066-12-01")));
    }
    private void verifyTerms(JsonNode formal,JsonNode master,String condition) throws Exception {
        JsonNode header=parseStored(formal.get("qo_pr_submission_content"));JsonNode terms=parseStored(header.get("qo_pr_quote_payment_terms_snapshot"));
        assertEquals("KINGDEE_PAYMENT_CONDITION",terms.path("kind").asText());assertEquals(condition,terms.path("selection").path("conditionId").asText());
        assertTrue(sameFacts(master.path("paymentConditions").path(condition),terms.path("condition")));
        assertTrue(sameFacts(ProcurementQuoteLifecycleErp.condition(condition),terms.path("condition")));
        assertEquals(2,terms.path("condition").path("PayRecCondtionEntry").size());
        assertEquals(0,new BigDecimal("59.99999999999999999999999999999").compareTo(terms.path("condition").path("PayRecCondtionEntry").get(1).path("Rate").decimalValue()));
        assertTrue(terms.path("condition").path("ISINSTALLMENT").isBoolean());assertTrue(terms.path("condition").path("PayRecCondtionEntry").get(0).path("FWorkCalID").isNull());
    }
    private void assertDirectoryReference(List<JsonNode> refs,String entry,String model,String localPid) {
        var found=refs.stream().filter(ref->entry.equals(ref.path("customer_pcba_ref_external_entry_id").asText())).toList();assertEquals(1,found.size());
        var row=found.get(0);assertEquals(tenant.toString(),row.path("tenant_id").asText());
        for(var fact:Map.of("external_system","KINGDEE","instance_key",connection,"account_id",provider,
                "external_form_id","BD_Supplier","external_id","701","external_entry_id",entry,"local_model_code",model,"local_pid",localPid).entrySet())
            assertEquals(fact.getValue(),row.path("customer_pcba_ref_"+fact.getKey()).asText(),fact.getKey());
        assertFalse(row.path("customer_pcba_ref_identity_key").asText().isBlank());
    }
    static boolean sameFacts(JsonNode left,JsonNode right) {
        if(left==null || right==null) return left==right;
        if(left.isNumber() && right.isNumber()) return left.decimalValue().compareTo(right.decimalValue())==0;
        if(left.isObject() && right.isObject()) {
            var keys=new HashSet<String>();left.fieldNames().forEachRemaining(keys::add);var other=new HashSet<String>();right.fieldNames().forEachRemaining(other::add);
            return keys.equals(other) && keys.stream().allMatch(key->sameFacts(left.get(key),right.get(key)));
        }
        if(left.isArray() && right.isArray()) {
            if(left.size()!=right.size()) return false;
            for(int index=0;index<left.size();index++) if(!sameFacts(left.get(index),right.get(index)))return false;
            return true;
        }
        return left.equals(right);
    }
    private JsonNode parseStored(JsonNode value) throws Exception {assertNotNull(value);return value.isTextual()?ProcurementQuoteLifecycleErp.JSON.readTree(value.textValue()):value;}
    private List<JsonNode> rows(String model) {
        assertTrue(MODELS.contains(model));String table=metadata.getTableName(model);assertTrue(table.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"));
        return new JdbcTemplate(postgres).query("SELECT row_to_json(t)::text FROM \""+table+"\" t WHERE tenant_id=? ORDER BY pid",(rs,n)->{
            try{return ProcurementQuoteLifecycleErp.JSON.readTree(rs.getString(1));}catch(java.io.IOException invalid){throw new IllegalStateException("Invalid actual row JSON",invalid);}},tenant);
    }
    private JsonNode raw(String model,String pid) {var found=rows(model).stream().filter(row->pid.equals(row.path("pid").asText())).toList();assertEquals(1,found.size());return found.get(0);}
    private List<JsonNode> submissionLines(String pid) {return rows("qo_procurement_submission_line_common").stream().filter(row->pid.equals(row.path("qo_pr_submission_line_submission_pid").asText())).toList();}
    private Map<String,List<JsonNode>> snapshotOwned(){var result=new LinkedHashMap<String,List<JsonNode>>();for(String model:MODELS) result.put(model,rows(model));return result;}
    private Map<String,Object> sourceFacts() {
        var facts=new LinkedHashMap<String,Object>();facts.put("pr_preq_code",provider);facts.put("pr_preq_product_id",product);facts.put("pr_preq_qty","1500");
        facts.put("pr_preq_source","external_requisition");facts.put("pr_preq_source_no",provider);facts.put("pr_preq_status","pending");facts.put("pr_preq_collaboration_managed",true);
        facts.put("qo_pr_source_key",provider+":501:1");facts.put("qo_pr_source_task_pid",task);facts.put("qo_pr_source_account_id",provider);facts.put("qo_pr_source_external_system","KINGDEE");
        facts.put("qo_pr_source_instance_key",connection);facts.put("qo_pr_source_requisition_id","501");facts.put("qo_pr_source_entry_id","1");facts.put("qo_pr_source_material_id","900");
        facts.put("qo_pr_source_material_code",provider);facts.put("qo_pr_source_material_name","Controlled lifecycle material");facts.put("qo_pr_source_specification","Controlled exact-decimal source");
        facts.put("qo_pr_source_unit","PCS");facts.put("qo_pr_source_remaining_qty","1000");facts.put("qo_pr_source_reserved_qty","0");facts.put("qo_pr_source_delivery_date","2066-12-01");facts.put("qo_pr_source_revision",0);facts.put("qo_pr_source_state","active");return facts;
    }
    private static String password(){String value=required("PROCUREMENT_QUOTE_LIFECYCLE_PASSWORD");assertTrue(value.length()>=12 && value.length()<=128);return value;}
    @Override @AfterEach void close() throws Exception {
        try {if(caseEvidence!=null)writeArtifact("quote-lifecycle-stage.json",Map.of("stage",stage,"completed",completed,"publicActionCount",actions.size(),"receiptExists",Files.exists(caseEvidence.resolve("quote-lifecycle-receipt.json")),"browserExecuted",0));}
        finally {if(erp!=null)erp.close();super.close();}
    }
}
