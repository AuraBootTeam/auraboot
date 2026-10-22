package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.TestApplication;
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

/** Shared native preparation and observation; no test methods or independent suite activation. */
abstract class ProcurementPublicSourceNativeFixture {

    protected static final String SYNC="qo_procurement_task_common:sync_sources";
    protected static final String PASSWORD="public-source-local-fixture-123";
    // The target command permit is s2p-only. Related reads still require actual Rule Center permissions.
    protected static final List<String> RELATED_READ_PERMISSIONS=List.of(
        "model.customer_pcba_int_kingdee_connection.read","model.customer_pcba_int_external_ref.read",
        "model.s2p_sourcing_event.read","model.prod_product.read","model.pr_purchase_request.read","model.s2p_bid.read",
        "model.qo_procurement_allocation_common.read","model.qo_procurement_quote_line_common.read");
    @Autowired WebApplicationContext application;
    @Autowired FilterChainProxy springSecurityFilterChain;
    @Autowired ExtensionRegistry extensions;
    @Autowired CommandService commands;
    @Autowired PluginImportService imports;
    @Autowired ObjectMapper json;
    @Autowired DynamicDataService data;
    @Autowired UserService users;
    @Autowired TenantService tenants;
    @Autowired com.auraboot.framework.tenant.service.TenantBootstrapService tenantBootstrap;
    @Autowired com.auraboot.framework.permission.service.UserPermissionService userPermissions;
    @Autowired TenantMemberService members;
    @Autowired RoleService roles;
    @Autowired UserRoleService userRoles;
    @Autowired PermissionService permissions;
    @Autowired AuthService authentication;
    @Autowired CloudConfigService configs;
    @Autowired PlatformTransactionManager transactions;
    @Autowired javax.sql.DataSource postgres;
    @Autowired SourceWriteObservation observed;
    protected DynamicDataAccessorImpl db;
    protected MockMvc mvc;
    protected Long tenant,userId,memberId;
    protected String actor,provider,token,deniedToken,task,connection,procurementDirectory;
    protected HttpServer server;
    protected Path caseEvidence;
    protected Long baselineTenant, isolationTenant;
    protected String baselineTargetTask;
    protected Map<String,Object> baselineBootstrap, isolationBootstrap;
    protected Map<String,Object> priorCaseFacts;
    protected int publicRequestSequence;
    protected Map<String,Object> retainedBootstrapPrincipal;
    protected final List<Map<String,Object>> retainedActors=new ArrayList<>();
    protected final List<String> retainedTaskPids=new ArrayList<>();
    protected volatile String response;
    protected final AtomicInteger loginCount=new AtomicInteger(),queryCount=new AtomicInteger();

    /** This separate test context disables scheduling entry points, not services or persistence. */
    @TestConfiguration(proxyBeanMethods=false)
    static class IsolatedScheduling {
        @Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor preventPollers() {
            return factory->{
                var registry=(org.springframework.beans.factory.support.BeanDefinitionRegistry)factory;
                String scheduled=org.springframework.scheduling.config.TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME;
                if(registry.containsBeanDefinition(scheduled)) registry.removeBeanDefinition(scheduled);
                Class<com.auraboot.framework.scheduler.service.SchedulerEngine> type=com.auraboot.framework.scheduler.service.SchedulerEngine.class;
                String[] engines=factory.getBeanNamesForType(type,false,false);
                if(engines.length!=1) throw new IllegalStateException("Exactly one scheduling entry point required");
                var definition=new org.springframework.beans.factory.support.RootBeanDefinition(type);
                definition.setInstanceSupplier(()->org.mockito.Mockito.mock(type));
                registry.removeBeanDefinition(engines[0]);registry.registerBeanDefinition(engines[0],definition);
            };
        }
        @Bean SourceWriteObservation sourceWriteObservation(){return new SourceWriteObservation();}
    }
    /** Observation after real create returns; it does not replace data access, return values, or exceptions. */
    @Aspect static class SourceWriteObservation {
        final AtomicReference<String> created=new AtomicReference<>();
        volatile Long tenant;
        volatile String missingTargetPid;
        volatile String missingTargetModel="s2p_sourcing_event";
        volatile boolean publicRequestActive;
        final java.util.concurrent.atomic.AtomicBoolean targetNotFound=new java.util.concurrent.atomic.AtomicBoolean();
        @org.aspectj.lang.annotation.AfterThrowing(pointcut="execution(* com.auraboot.framework.meta.service.DynamicDataService+.getById(..)) && args(model,recordPid)",throwing="failure")
        public void recordMissingTarget(String model,String recordPid,RuntimeException failure) {
            if(publicRequestActive && Objects.equals(tenant,MetaContext.getCurrentTenantId()) && Objects.equals(missingTargetModel,model)
                    && Objects.equals(missingTargetPid,recordPid)
                    && failure instanceof com.auraboot.framework.meta.exception.MetaRecordNotFoundException)
                targetNotFound.set(true);
        }
        @AfterReturning(pointcut="execution(* com.auraboot.framework.meta.service.DynamicDataService+.create(..)) && args(model,values)",returning="result")
        public void record(String model,Map<String,Object> values,Object result) {
            if(Objects.equals(tenant,MetaContext.getCurrentTenantId()) && "pr_purchase_request".equals(model)
                    && result instanceof Map<?,?> row) created.set(String.valueOf(row.get("pid")));
        }
    }

    @BeforeEach void prepare(TestInfo test) throws Exception {
        retainedActors.clear();retainedTaskPids.clear();retainedBootstrapPrincipal=null;priorCaseFacts=null;
        caseEvidence=null;tenant=null;userId=null;memberId=null;actor=null;provider=null;token=null;deniedToken=null;
        loginCount.set(0);queryCount.set(0);orderSaveCount.set(0);orderLookupCount.set(0);orderReadCount.set(0);readClosedStatus="LOCAL_OPEN";readQuantity="300.000001";readReceivedQuantity="250";savedOrder=null;ambiguousOrderSave=false;publicRequestSequence=0;server=null;task=null;connection=null;observed.tenant=null;observed.missingTargetPid=null;observed.missingTargetModel="s2p_sourcing_event";observed.targetNotFound.set(false);
        prepareEvidence(test);
        admitEncryption();
        assertEquals("13",System.getenv("TEST_REDIS_DATABASE"));
        try(var sql=postgres.getConnection()){assertEquals("enterprise_22",sql.getCatalog());}
        assertTrue(extensions.getCommandHandler(SYNC).isPresent(),"Frozen production PF4J artifact must register source sync");
        provider="ps_"+UUID.randomUUID().toString().replace("-","").substring(0,24);
        var owner=users.signUp(provider+"@example.invalid",PASSWORD,"Public source local fixture",provider);
        userId=owner.getId();actor=owner.getPid();
        boolean isolated=Set.of("publicRoleAndPayloadCannotGrantIntegrationOrSecretCapabilities","publicOrderTargetCannotCrossIntoAnotherFormallyImportedTenant").contains(test.getTestMethod().orElseThrow().getName());
        tenant=isolated?isolationTenant:baselineTenant;
        boolean initialize=tenant==null;
        if(initialize) {
            var organization=new Tenant();organization.setName(provider+"_baseline");organization.setDisplayName(provider);organization.setStatus("active");
            organization.setPid(com.auraboot.framework.common.util.UniqueIdGenerator.generate());
            tenant=tenants.createTenant(organization).getId();
            assertNotEquals(Long.valueOf(System.getenv("TEST_TENANT_ID")),tenant,"Never alter the existing public tenant");
            if(isolated) isolationTenant=tenant;else baselineTenant=tenant;
        }
        memberId=members.addMember(userId,tenant,"active").getId();context();
        db=new DynamicDataAccessorImpl(data);
        if(initialize) {
            // This principal initializes the complete formal baseline; it never authenticates a public request.
            var bootstrap=users.signUp(provider+"-bootstrap@example.invalid",PASSWORD,"Local bootstrap principal",provider+"_bootstrap");
            var bootstrapMember=members.addMember(bootstrap.getId(),tenant,"active");
            assertNotEquals(actor,bootstrap.getPid());assertNotEquals(memberId,bootstrapMember.getId());
            retainedBootstrapPrincipal=Map.of("tenantId",tenant.toString(),"userPid",bootstrap.getPid(),"memberPid",bootstrapMember.getPid(),
                "purpose","Formal TenantBootstrapService initialization only","usedForTestedPublicRequest",false,"authenticatedForPublicRequest",false);
            assertTrue(tenantBootstrap.bootstrapTenant(tenant,bootstrap.getId()).isSuccess());context();
            assertNotNull(permissions.findByCode("report.definition.view"));
            var directories=json.readTree(required("PROCUREMENT_SOURCE_IMPORT_DIRECTORIES"));
            assertTrue(directories.isArray() && directories.size()==23,"Complete frozen ordered DSL profile required");
            for(var directory:directories) {
                Path path=Path.of(directory.asText()).toRealPath();assertTrue(Files.isDirectory(path));
                if(Files.isRegularFile(path.resolve("config/commands/procurement.json"))) procurementDirectory=path.toString();
                importDirectory(path);
            }
            if(isolated) isolationBootstrap=retainedBootstrapPrincipal;else baselineBootstrap=retainedBootstrapPrincipal;
        } else {
            retainedBootstrapPrincipal=baselineBootstrap;
            // Reset via the same formal importer, never an SQL update of shared command metadata.
            importDirectory(Path.of(procurementDirectory));
        }
        assertNotNull(procurementDirectory);
        imports.verifyImportReferenceIntegrity();
        var definition=commands.findByCode(SYNC);assertEquals(tenant,definition.getTenantId());
        var execution=json.readTree(definition.getExecutionConfig());
        assertEquals("s2p_sourcing_event",definition.getModelCode());assertEquals(SYNC,execution.path("handler").asText());
        assertEquals("qo.procurement.integrate",execution.path("permissions").get(0).asText());
        assertTrue(execution.path("handlerParams").path("procurementSourceEligibilityPolicies").isArray());
        assertEquals(0,execution.path("handlerParams").path("procurementSourceEligibilityPolicies").size());
        priorCaseFacts=snapshot();
        writeArtifact("baseline-reuse.json",Map.of("tenantId",tenant.toString(),"completeProfileDirectoryCount",23,
            "initializedThisCase",initialize,"independentIsolationTenant",isolated,"productionDefaultPolicies",List.of(),
            "priorBusinessFacts",priorCaseFacts));
        token=actorToken(owner.getId(),owner.getPid(),memberId,provider+"@example.invalid",sourceActorPermissions(true));
        context();var denied=users.signUp(provider+"-denied@example.invalid",PASSWORD,"No integration authority",provider+"_denied");
        var deniedMember=members.addMember(denied.getId(),tenant,"active");
        deniedToken=actorToken(denied.getId(),denied.getPid(),deniedMember.getId(),provider+"-denied@example.invalid",sourceActorPermissions(false));
        context();mvc=MockMvcBuilders.webAppContextSetup(application).addFilters(springSecurityFilterChain).build();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);response=row("11",1000,"C");
        server.createContext("/K3Cloud/Kingdee.BOS.WebApi.ServicesStub.AuthService.LoginByAppSecret.common.kdsvc",http->{
            var body=json.readTree(http.getRequestBody().readAllBytes());
            assertEquals("local-test-secret",body.path("appSecret").asText());loginCount.incrementAndGet();
            http.getResponseHeaders().add("Set-Cookie","public-source-local=authenticated; Path=/K3Cloud");
            byte[] bytes="{\"LoginResultType\":1}".getBytes(StandardCharsets.UTF_8);http.sendResponseHeaders(200,bytes.length);http.getResponseBody().write(bytes);http.close();
        });
        server.createContext("/K3Cloud/Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.ExecuteBillQuery.common.kdsvc",http->{
            var request=json.readTree(http.getRequestBody().readAllBytes());String content=response;
            if("FixturePurchase".equals(request.path("data").path("FormId").asText())) {
                assertNotNull(savedOrder);assertEquals(Set.of("data"),json.convertValue(request,Map.class).keySet());var query=request.path("data");
                assertEquals(2,query.path("Limit").asInt(-1));
                if("OrderId,BillNo,Marker".equals(query.path("FieldKeys").asText())) {
                    assertEquals(0,query.path("StartRow").asInt(-1));orderLookupCount.incrementAndGet();assertEquals("Marker = '"+savedOrder.path("Marker").asText()+"'",query.path("FilterString").asText());
                    content=json.writeValueAsString(List.of(List.of("local-order-1","LOCAL-PO-1",savedOrder.path("Marker").asText())));
                } else {
                    orderReadCount.incrementAndGet();assertEquals(String.join(",",READ_COLUMNS.stream().map(column->"Read_"+column).toList()),query.path("FieldKeys").asText());
                    String filter=query.path("FilterString").asText();assertTrue(filter.equals("Read_erp_id = 'local-order-1'") || filter.equals(""));
                    assertEquals("Read_erp_id ASC,Read_erp_entry_id ASC",query.path("OrderString").asText());assertEquals(0,query.path("TopRowCount").asInt(-1));
                    var rows=orderReadRows();int start=query.path("StartRow").asInt(-1);
                    assertTrue(start>=0 && start<=rows.size() && start%2==0,"Controlled read page must be canonical");
                    content=json.writeValueAsString(rows.subList(start,Math.min(start+2,rows.size())));
                }
            } else queryCount.incrementAndGet();
            byte[] bytes=content.getBytes(StandardCharsets.UTF_8);http.sendResponseHeaders(200,bytes.length);http.getResponseBody().write(bytes);http.close();
        });
        server.createContext("/K3Cloud/Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.Save.common.kdsvc",http->{
            var request=json.readTree(http.getRequestBody().readAllBytes());assertEquals("FixturePurchase",request.path("formid").asText());
            orderSaveCount.incrementAndGet();savedOrder=request.path("data").path("Model");assertTrue(savedOrder.isObject());
            String content=ambiguousOrderSave?"not-json":"{\"Result\":{\"ResponseStatus\":{\"IsSuccess\":true,\"SuccessEntitys\":[{\"Id\":\"local-order-1\",\"Number\":\"LOCAL-PO-1\"}]}}}";
            byte[] bytes=content.getBytes(StandardCharsets.UTF_8);http.sendResponseHeaders(200,bytes.length);http.getResponseBody().write(bytes);http.close();
        });server.start();
        var secret=new CloudConfigSaveRequest();secret.setConfigLevel("tenant");secret.setServiceType("integration");secret.setProviderCode(provider);secret.setEnabled(true);secret.setPriority(1);
        secret.setConfig("{\"appSecret\":\"local-test-secret\"}");configs.saveConfig(secret);
        prepareInitialSourceRecords();
        retainedTaskPids.add(task);
        if(!isolated && baselineTargetTask==null) baselineTargetTask=task;
    }
    /** Override only prerequisite writes; the original Source/Order fixture retains this exact body. */
    /** Optional admission for consumers that bind the Native bean to the current runtime crypto proof. */
    protected void admitEncryption() throws Exception {}

    protected void prepareInitialSourceRecords() throws Exception {
        fixture(SYNC,()->{
            String product=pid(db.create("prod_product",Map.of("prod_code",provider,"prod_name","Public source material","prod_type","raw_material","prod_unit","pcs","prod_status","active")));
            connection=pid(db.create("customer_pcba_int_kingdee_connection",Map.of("customer_pcba_kd_profile_name",provider,"customer_pcba_kd_status","active", "customer_pcba_kd_acct_id",provider,
                    "customer_pcba_kd_base_url","http://127.0.0.1:"+server.getAddress().getPort(),"customer_pcba_kd_app_id","local-app","customer_pcba_kd_username","local-reader","customer_pcba_kd_app_secret_ref","cloud-config:"+provider,"customer_pcba_kd_auth_type","app-secret")));
            db.create("customer_pcba_int_external_ref",Map.of("customer_pcba_ref_external_system","KINGDEE","customer_pcba_ref_instance_key",connection,"customer_pcba_ref_account_id",provider,"customer_pcba_ref_external_form_id","BD_MATERIAL","customer_pcba_ref_external_id","900","customer_pcba_ref_local_model_code","prod_product","customer_pcba_ref_local_pid",product));return null;
        });
        task=fixture("qo_procurement_task_common:create",()->pid((Map<?,?>)service("ProcurementTaskService",new Class<?>[]{DataAccessor.class},new Object[]{db},"create",new Class<?>[]{Object.class,String.class,Map.class},tenant,actor,Map.of("title",provider,"connectionPid",connection))));
    }
    @AfterEach void close() throws Exception {
        try {
            if(caseEvidence!=null) {
                var identities=new LinkedHashMap<String,Object>();
                identities.put("retention","Retained exact independent tenant namespace; no database cleanup was performed");
                identities.put("tenantId",tenant==null?null:tenant.toString());identities.put("ownerUserPid",actor);
                identities.put("ownerMemberId",memberId==null?null:memberId.toString());identities.put("namespace",provider);
                identities.put("taskPids",retainedTaskPids);identities.put("bootstrapPrincipal",retainedBootstrapPrincipal);identities.put("actors",retainedActors);identities.put("connectionPid",connection);identities.put("accountId",provider);identities.put("targetTaskPid",task);
                identities.put("externalErpSynthetic",true);identities.put("customer003AdmissionVerified",false);
                writeArtifact("retained-identities.json",identities);
                if(priorCaseFacts!=null) {
                    var after=snapshot();assertPriorFactsUnchanged(priorCaseFacts,after);
                    writeArtifact("prior-case-facts-after.json",after);
                }
            }
        } finally {observed.tenant=null;if(server!=null)server.stop(0);MetaContext.clear();}
    }
    /** Fail before creating a fixture user; resolve all ownership roots rather than trusting a path prefix. */
    protected void prepareEvidence(TestInfo test) throws Exception {
        String runtime=required("AURA_RUNTIME_NAME");assertEquals("procurement-web-stack-20261010",runtime);
        Path pluginDirectory=Path.of(required("PROCUREMENT_SOURCE_PLUGIN_DIR")).toRealPath();
        Path runtimeDirectory=pluginDirectory.getParent().getParent();
        assertEquals(runtime,runtimeDirectory.getFileName().toString());
        Path workspace=runtimeDirectory.getParent().getParent().getParent().toRealPath();
        assertEquals(workspace.resolve(".workspace/runtimes").resolve(runtime).toRealPath(),runtimeDirectory);
        Path runtimeManifest=runtimeDirectory.resolve("runtime-manifest.json").toRealPath();
        var published=json.readTree(Files.readAllBytes(runtimeManifest));assertEquals(runtime,published.path("runtime").asText());
        Path sourceManifest=runtimeDirectory.resolve(published.path("sourceManifest").asText()).toRealPath();
        assertTrue(sourceManifest.startsWith(runtimeDirectory),"Manifest must remain inside the owned runtime");
        var source=json.readTree(Files.readAllBytes(sourceManifest));assertEquals(runtime,source.path("runtime").asText());
        assertEquals(workspace,Path.of(source.path("paths").path("workspaceRoot").asText()).toRealPath());
        assertEquals(pluginDirectory,Path.of(source.path("paths").path("pluginDir").asText()).toRealPath());
        Path evidenceRoot=workspace.resolve(".workspace/evidence").resolve(runtime).toRealPath();
        Path attempt=Path.of(required("PROCUREMENT_PUBLIC_SOURCE_EVIDENCE_DIR")).toRealPath();
        assertTrue(Files.isDirectory(attempt) && attempt.startsWith(evidenceRoot) && !attempt.equals(evidenceRoot),"Attempt evidence must belong to this workspace/runtime");
        Path inputPath=Path.of(required("PROCUREMENT_PUBLIC_SOURCE_READINESS_INPUT")).toRealPath();
        assertEquals(attempt,inputPath.getParent(),"Readiness must belong to the same owned attempt");
        assertEquals("readiness-input.json",inputPath.getFileName().toString());
        String inputSha=required("PROCUREMENT_PUBLIC_SOURCE_READINESS_SHA256");assertTrue(inputSha.matches("[a-f0-9]{64}"));
        assertEquals(inputSha,sha(inputPath),"Runner input must remain sealed");
        var input=json.readTree(Files.readAllBytes(inputPath));assertEquals(runtime,input.path("runtime").asText());
        assertEquals(workspace.toString(),input.path("workspaceRoot").asText());
        assertEquals(pluginDirectory.toString(),input.path("pluginDir").asText());
        assertEquals(sha(runtimeManifest),input.path("publicationSha256").asText());
        assertEquals(sha(sourceManifest),input.path("sourceManifestSha256").asText());
        assertEquals(published.path("sourceManifestHash").asText(),input.path("sourceManifestHash").asText());
        assertEquals(sha(pluginDirectory.resolve("quote-engine-plugin-0.1.0.jar")),input.path("productJarSha256").asText());
        var directories=json.readTree(required("PROCUREMENT_SOURCE_IMPORT_DIRECTORIES"));
        var configured=input.path("orderedImportDirectories");assertTrue(configured.isArray());assertEquals(23,configured.size());
        assertTrue(directories.isArray());assertEquals(configured.size(),directories.size());
        for(int index=0;index<configured.size();index++) {
            var row=configured.get(index);Path directory=Path.of(row.path("path").asText()).toRealPath();
            assertEquals(directory.toString(),directories.get(index).asText());
            assertEquals(sha(directory.resolve("plugin.json")),row.path("pluginJsonSha256").asText());
            assertTrue(row.path("configFiles").isArray());
            for(var file:row.path("configFiles")) {
                Path actual=directory.resolve(file.path("relativePath").asText()).toRealPath();
                assertTrue(actual.startsWith(directory.resolve("config").toRealPath()),"Only frozen plugin configuration may be bound");
                assertEquals(file.path("sha256").asText(),sha(actual));
            }
        }
        caseEvidence=Files.createDirectory(attempt.resolve("c-"+UUID.randomUUID()));
        assertTrue(caseEvidence.resolve("configured-dsl").toString().length()<=256,"Formal import history source_name must remain bounded");
        writeArtifact("case-identity.json",Map.of("method",test.getTestMethod().orElseThrow().getName(),"directory",caseEvidence.toString()));
        var readiness=new LinkedHashMap<String,Object>();readiness.put("runtime",runtime);readiness.put("workspaceRoot",workspace.toString());
        readiness.put("runtimeManifestSha256",sha(runtimeManifest));readiness.put("sourceManifestSha256",sha(sourceManifest));
        readiness.put("sourceManifestHash",published.path("sourceManifestHash").asText());
        readiness.put("pluginJarSha256",sha(pluginDirectory.resolve("quote-engine-plugin-0.1.0.jar")));
        readiness.put("database","enterprise_22");readiness.put("redisDatabase","13");readiness.put("publicTransport","MockMvc with actual Spring security filters; no HTTP socket");
        var commits=new LinkedHashMap<String,String>();
        var sourcePaths=Map.of("oss","ossRoot","quote","quoteRoot","enterprise","enterpriseRoot","crm","crmRoot","plugins","pluginsRoot");
        for(String component:List.of("oss","quote","enterprise","crm","plugins")) {
            Path frozen=Path.of(source.path("paths").path(sourcePaths.get(component)).asText()).toRealPath();
            String full=gitRead(frozen,"rev-parse","HEAD").strip();
            assertEquals("",gitRead(frozen,"status","--porcelain").strip(),"Frozen source must remain clean");
            assertEquals(frozen.toString(),input.path("verifiedSourceIdentities").path(component).path("root").asText());
            validateSourceIdentity(component,source.path("commits").path(component).asText(),full,input);
            commits.put(component,full);
        }
        readiness.put("runnerReadinessSha256",inputSha);
        readiness.put("sourceCommits",commits);writeArtifact("readiness.json",readiness);
    }
    /** Pure identity contract, shared with hermetic counterexamples; canonical manifest uses exactly 12 digits. */
    static void validateSourceIdentity(String component,String manifestShort,String full,com.fasterxml.jackson.databind.JsonNode input) {
        assertTrue(manifestShort.matches("[a-f0-9]{12}"),"Canonical manifest must carry exactly 12 commit digits");
        assertTrue(full.matches("[a-f0-9]{40}"),"Actual frozen git commit must be full length");
        assertEquals(full.substring(0,12),manifestShort,"Manifest short commit must exactly match actual frozen git");
        assertEquals(full,input.path("sourceSet").path(component).asText(),"Sealed runner source must match actual git");
        var verified=input.path("verifiedSourceIdentities").path(component);
        assertEquals("ok",verified.path("status").asText());
        assertEquals(full,verified.path("expectedCommit").asText());assertEquals(full,verified.path("actualCommit").asText());
        assertEquals("clean",verified.path("expectedDirtyFingerprint").asText());assertEquals("clean",verified.path("actualDirtyFingerprint").asText());
    }
    protected static String gitRead(Path root,String... arguments) throws Exception {
        var command=new ArrayList<String>();command.add("git");command.add("-C");command.add(root.toString());command.addAll(List.of(arguments));
        var process=new ProcessBuilder(command).redirectErrorStream(true).start();
        String value=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        assertEquals(0,process.waitFor(),"Read-only frozen source identity command must succeed");return value;
    }
    protected static String sha(Path path) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    protected void writeArtifact(String name,Object value) throws Exception {
        Files.write(caseEvidence.resolve(name),json.writerWithDefaultPrettyPrinter().writeValueAsBytes(value),StandardOpenOption.CREATE_NEW);
    }

    protected void importDirectory(Path directory) {
        var preview=imports.parseDirectory(directory.toString(),true);assertTrue(preview.isValid(),"Actual DSL import preview must validate");
        var request=ImportRequest.builder().importId(preview.getImportId()).conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE)
            .autoPublishFields(true).autoPublishModels(true).autoPublishCommands(true).autoPublishPages(true).createResourcePermissions(true).build();
        assertTrue(imports.execute(preview.getImportId(),request).isSuccess(),"Actual tenant DSL import must succeed");
    }
    protected void configurePolicy() throws Exception {
        context();Path original=Path.of(procurementDirectory);Path copy=Files.createDirectory(caseEvidence.resolve("configured-dsl"));
        try(var files=Files.walk(original)){for(Path path:files.toList()) {Path target=copy.resolve(original.relativize(path));if(Files.isDirectory(path))Files.createDirectories(target);else Files.copy(path,target);}}
        Files.copy(original.resolve("config/commands/procurement.json"),caseEvidence.resolve("production-default-command.json"));
        Path commandsFile=copy.resolve("config/commands/procurement.json");var commandsNode=json.readTree(commandsFile.toFile());
        int changed=0;for(var command:commandsNode) if(SYNC.equals(command.path("code").asText())) {
            var policies=(ArrayNode)command.path("handlerParams").path("procurementSourceEligibilityPolicies");assertTrue(policies.isEmpty());
            policies.add(json.valueToTree(Map.of("connectionPid",connection,"accountId",provider,"rules",List.of(Map.of("documentStatus","C","cancelStatus","A","closeStatus","A","eligible",true)))));changed++;
        }
        assertEquals(1,changed);json.writeValue(commandsFile.toFile(),commandsNode);
        String originalSha=sha(original.resolve("config/commands/procurement.json"));
        writeArtifact("configuration-diff.json",Map.of("sourceDirectory",original.toString(),"sourceCommandFileSha256",originalSha,
            "configuredCommandFileSha256",sha(commandsFile),"command",SYNC,"scope","Independent test tenant only; metadata differs production default",
            "changedPath","handlerParams.procurementSourceEligibilityPolicies","before",List.of(),"after",commandsNode.findValues("procurementSourceEligibilityPolicies").getFirst()));
        importDirectory(copy);assertEquals(originalSha,sha(original.resolve("config/commands/procurement.json")),"Authoritative command file remains unchanged");
        assertEquals(tenant,commands.findByCode(SYNC).getTenantId());
        assertEquals(connection,json.readTree(commands.findByCode(SYNC).getExecutionConfig()).path("handlerParams").path("procurementSourceEligibilityPolicies").get(0).path("connectionPid").asText());
        // Keep the temporary test manifest as evidence; it contains only synthetic fixture configuration.
    }






    protected record Submitted(String source,String quote,String submission,long sourceRevision){}
    /** Actual domain lifecycle preparation; no state SQL or fake confirmed allocation. */
    protected Submitted prepareSubmittedQuote() {
        return fixture("qo_procurement_task_common:dispatch",()->new TransactionTemplate(transactions).execute(status->{
            String source=pid(sources().getFirst());
            String category=pid(db.create("pr_spend_category",Map.of("pr_spc_code",provider,"pr_spc_name","Local source category")));
            String supplier=fixture("pr_supplier_common:sync_directory",()->pid(db.create("pr_supplier_common",Map.of("pr_supplier_code",provider,"pr_supplier_name","Local source supplier","pr_supplier_status","enabled","qo_pr_supplier_account_id",provider))));
            fixture("qo_procurement_category_policy_common:create",()->service("ProcurementCategoryPolicyService",new Class<?>[]{DataAccessor.class,Long.class},new Object[]{db,tenant},"createPolicy",new Class<?>[]{Map.class},Map.of("categoryPid",category,"connectionPid",connection,"supplierPids",List.of(supplier),"defaultSupplierPids",List.of(supplier))));
            long beforeClassification=new BigDecimal(db.getById("pr_purchase_request",source).get("qo_pr_source_revision").toString()).longValueExact();
            service("ProcurementPlanningService",new Class<?>[]{DataAccessor.class,Clock.class,Object.class},new Object[]{db,Clock.systemUTC(),tenant},"classify",new Class<?>[]{String.class,Long.class,Map.class},task,version(),Map.of("sourcePid",source,"sourceRevision",beforeClassification,"categoryPid",category));
            long sourceRevision=new BigDecimal(db.getById("pr_purchase_request",source).get("qo_pr_source_revision").toString()).longValueExact();
            assertEquals(beforeClassification+1,sourceRevision,"Classification must advance the actual source business revision");
            service("ProcurementDispatchService",new Class<?>[]{DataAccessor.class,Clock.class,Long.class},new Object[]{db,Clock.systemUTC(),tenant},"dispatch",new Class<?>[]{String.class,Long.class,Map.class},task,version(),Map.of("supplierPids",List.of(supplier),"cutoffAt","2066-11-15T08:00:00Z"));
            String quote=pid(db.query("s2p_bid",Map.of("s2p_bid_sourcing_event_id",task)).getFirst());
            String contact=fixture("pr_supplier_pcba_contact:sync_directory",()->pid(db.create("pr_supplier_pcba_contact",Map.of("pr_spc_supplier_id",supplier,"pr_spc_contact_name","Local supplier contact","qo_pr_contact_state","active"))));
            fixture("qo_procurement_membership_common:bind_verified_phone",()->db.create("qo_procurement_membership_common",Map.of("qo_pr_membership_key",provider,"qo_pr_membership_user_pid",actor,"qo_pr_membership_supplier_pid",supplier,"qo_pr_membership_contact_pid",contact,"qo_pr_membership_state","active")));
            var line=Map.<String,Object>of("sourcePid",source,"sourceRevision",sourceRevision,"unitPrice","1.25","currency","CNY","unit","PCS","taxRate","0.13","taxIncluded",true,"availableQty","600","deliveryDate","2066-12-01");
            fixture("qo_procurement_quote_common:save_draft",()->service("ProcurementQuoteService",new Class<?>[]{DataAccessor.class,Clock.class},new Object[]{db,Clock.systemUTC()},"execute",new Class<?>[]{String.class,String.class,String.class,Map.class},"save_draft",quote,actor,Map.of("revision",0,"lines",List.of(line),"paymentTerms","Net 30")));
            var formal=(Map<?,?>)fixture("qo_procurement_quote_common:submit",()->service("ProcurementQuoteService",new Class<?>[]{DataAccessor.class,Clock.class},new Object[]{db,Clock.systemUTC()},"execute",new Class<?>[]{String.class,String.class,String.class,Map.class},"submit",quote,actor,Map.of("revision",1)));
            return new Submitted(source,quote,formal.get("submissionPid").toString(),sourceRevision);
        }));
    }
    protected Long version(){return new BigDecimal(db.getById("s2p_sourcing_event",task).get("row_version").toString()).longValueExact();}

    protected record Result(int status,com.fasterxml.jackson.databind.JsonNode body){}
    protected static final String SYNC_ORDERS="qo_procurement_order_common:sync_orders",SYNC_CLOSURES="qo_procurement_order_common:sync_closures";
    protected static final List<String> READ_COLUMNS=List.of("erp_id","bill_no","erp_entry_id","supplier_external_id","material_id","order_date","business_status","closed_status","ordered_qty","submitted_qty","received_qty","material_code","material_name","specification","unit","delivery_date","requisition_id","entry_id");
    protected String readClosedStatus="LOCAL_OPEN",readQuantity="300.000001",readReceivedQuantity="250";
    /** Controlled ERP read fixture seam; default rows preserve all original source/order cases. */
    protected List<List<Object>> orderReadRows() {
        var line=savedOrder.path("Lines").get(0);
        return List.of(List.of("local-order-1","LOCAL-PO-1","local-entry-1",savedOrder.path("Supplier").path("Id").asText(),line.path("Material").path("Id").asText(),savedOrder.path("Date").asText(),"LOCAL_APPROVED",readClosedStatus,readQuantity,"0",readReceivedQuantity,"LOCAL-MATERIAL","Local material","local","PCS",line.path("DeliveryDate").asText(),line.path("Lines_Link").get(0).path("Lines_Link_Bill").asText(),line.path("Lines_Link").get(0).path("Lines_Link_Entry").asText()));
    }
    protected final AtomicInteger orderReadCount=new AtomicInteger();
    protected void verifyPublicOrderSynchronization(PreparedOrder order,Map<String,Object> source,List<Map<String,Object>> allocations,Map<String,Object> formal) throws Exception {
        assertTrue(extensions.getCommandHandler(SYNC_ORDERS).isPresent());assertTrue(extensions.getCommandHandler(SYNC_CLOSURES).isPresent());
        String bearer=orderSyncActor();var before=snapshot();int loginBefore=loginCount.get();
        assertEquals(400,orderSyncCall(bearer,SYNC_ORDERS,order.execution()).status());assertEquals(before,snapshot());assertEquals(loginBefore,loginCount.get());assertEquals(0,orderReadCount.get());
        configureOrderReadPolicy();readClosedStatus="UNCONFIRMED";before=snapshot();
        assertEquals(400,orderSyncCall(bearer,SYNC_ORDERS,order.execution()).status());assertEquals(before,snapshot(),"Unknown external status must reject the whole read before publication");
        readClosedStatus="LOCAL_OPEN";var published=orderSyncCall(bearer,SYNC_ORDERS,order.execution());assertEquals(200,published.status());assertEquals("published",published.body().path("data").path("data").path("status").asText());
        var orders=fixture(SYNC,()->db.query(domainModel("order"),Map.of("qo_pr_order_account_id",provider,"qo_pr_order_erp_id","local-order-1")));assertEquals(1,orders.size());String orderPid=pid(orders.getFirst());
        var original=fixture(SYNC,()->db.getById(domainModel("order"),orderPid));assertEquals("LOCAL-PO-1",original.get("pr_po_code"));assertEquals(order.supplier(),original.get("pr_po_supplier"));
        var lines=fixture(SYNC,()->db.query(domainModel("order_line"),Map.of("pr_pol_order_id",orderPid)));assertEquals(1,lines.size());var line=lines.getFirst();
        assertEquals(order.quote().source(),line.get("qo_pr_order_line_source_pid"));assertEquals(source.get("pr_preq_product_id"),line.get("pr_pol_product_id"));
        assertEquals("LOCAL-MATERIAL",line.get("qo_pr_order_line_material_code"));assertEquals("Local material",line.get("qo_pr_order_line_material_name"));assertEquals("local",line.get("qo_pr_order_line_specification"));assertEquals("PCS",line.get("qo_pr_order_line_unit"));
        assertEquals(0,new BigDecimal("300.000001").compareTo(new BigDecimal(line.get("pr_pol_qty").toString())));
        assertEquals(0,new BigDecimal(line.get("qo_pr_order_line_submitted_qty").toString()).signum());assertEquals(0,new BigDecimal("250").compareTo(new BigDecimal(line.get("pr_pol_received_qty").toString())));
        before=snapshot();assertEquals(200,orderSyncCall(bearer,SYNC_ORDERS,order.execution()).status());assertExactOrderCasReplay(orderPid,before,snapshot());
        readQuantity="400.000001";var reviewed=orderSyncCall(bearer,SYNC_ORDERS,order.execution());assertEquals(200,reviewed.status());assertEquals("manual_review_required",reviewed.body().path("data").path("data").path("status").asText());
        var changed=fixture(SYNC,()->db.getById(domainModel("order"),orderPid));assertEquals(Boolean.TRUE,changed.get("qo_pr_order_change_pending"));
        for(String field:List.of("pid","qo_pr_order_key","qo_pr_order_account_id","qo_pr_order_erp_id","pr_po_code","pr_po_supplier","pr_po_date","pr_po_status","qo_pr_order_content_hash","qo_pr_order_published_at","qo_pr_order_state"))assertEquals(original.get(field),changed.get(field),"Published identity and content remain immutable: "+field);
        assertEquals(lines,fixture(SYNC,()->db.query(domainModel("order_line"),Map.of("pr_pol_order_id",orderPid))));
        assertOrderDomainRetained(order,source,allocations,formal);
        readClosedStatus="UNCONFIRMED";before=snapshot();assertEquals(400,orderSyncCall(bearer,SYNC_CLOSURES,order.execution()).status());assertEquals(before,snapshot());
        readClosedStatus="LOCAL_CLOSED";assertEquals(200,orderSyncCall(bearer,SYNC_CLOSURES,order.execution()).status());assertEquals("closed",fixture(SYNC,()->db.getById(domainModel("order"),orderPid)).get("qo_pr_order_state"));
        readClosedStatus="LOCAL_OPEN";before=snapshot();assertEquals(200,orderSyncCall(bearer,SYNC_CLOSURES,order.execution()).status());assertEquals(before,snapshot(),"Explicit false observations do not reopen or rewrite closed orders");
        assertEquals(lines,fixture(SYNC,()->db.query(domainModel("order_line"),Map.of("pr_pol_order_id",orderPid))));assertOrderDomainRetained(order,source,allocations,formal);
        writeArtifact("public-p12-order-facts.json",Map.of("orderPid",orderPid,"executionPid",order.execution(),"orderedQty","300.000001","incomingChangedQty","400.000001","submittedQty","0","receivedQty","250","initialReceivedSynthetic",true,"trustedReceiptWriterVerified",false,"customer007Verified",false,"readCount",orderReadCount.get()));
    }
    protected void assertOrderDomainRetained(PreparedOrder order,Map<String,Object> source,List<Map<String,Object>> allocations,Map<String,Object> formal) {
        assertEquals(source,fixture(SYNC,()->db.getById(domainModel("source"),order.quote().source())));
        assertEquals(allocations,fixture(SYNC,()->db.query(domainModel("allocation"),Map.of("qo_pr_allocation_execution_pid",order.execution()))));assertEquals(formal,formalSnapshot(order.quote().submission()));
    }
    protected String orderSyncActor() {
        context();String email=provider+"-order-sync@example.invalid";var user=users.signUp(email,PASSWORD,"Public order sync local fixture",provider+"-order-sync");var member=members.addMember(user.getId(),tenant,"active");
        var codes=new ArrayList<>(ORDER_PERMISSIONS);codes.add("model.pr_purchase_order.read");codes.add("model.pr_purchase_order_line.read");assertEquals(15,codes.size());return actorToken(user.getId(),user.getPid(),member.getId(),email,codes);
    }
    protected Map<String,Object> orderReadContract() {
        var fields=new LinkedHashMap<String,String>();for(String column:READ_COLUMNS)fields.put(column,"Read_"+column);
        return Map.of("connectionPid",connection,"accountId",provider,"formId","FixturePurchase","supplierReferenceFormId","FixtureSupplier","fields",fields,"businessStatusValues",Map.of("LOCAL_APPROVED","approved"),"closedStatusValues",Map.of("LOCAL_OPEN",false,"LOCAL_CLOSED",true),"pageSize",2,"maxRows",20,"stableCompleteRead",true);
    }
    protected void configureOrderReadPolicy() throws Exception {
        context();Path original=Path.of(procurementDirectory);Path copy=Files.createDirectory(caseEvidence.resolve("read-dsl"));
        try(var files=Files.walk(original)){for(Path path:files.toList()){Path target=copy.resolve(original.relativize(path));if(Files.isDirectory(path))Files.createDirectories(target);else Files.copy(path,target);}}
        Path commandFile=copy.resolve("config/commands/procurement.json");Files.copy(commandFile,caseEvidence.resolve("read-production-default-command.json"));var definitions=json.readTree(commandFile.toFile());int changed=0;
        for(var definition:definitions)if(Set.of(SYNC_ORDERS,SYNC_CLOSURES).contains(definition.path("code").asText())){
            var policies=(ArrayNode)definition.path("handlerParams").path("procurementOrderReadContracts");assertTrue(policies.isEmpty());policies.add(json.valueToTree(orderReadContract()));changed++;
            assertEquals("qo_procurement_execution_common",definition.path("modelCode").asText());assertEquals("qo.procurement.integrate",definition.path("permissions").get(0).asText());
            assertEquals(SYNC_CLOSURES.equals(definition.path("code").asText()),definition.path("handlerParams").path("tenantProjectionRead").asBoolean());
        }
        assertEquals(2,changed);Files.write(commandFile,json.writerWithDefaultPrettyPrinter().writeValueAsBytes(definitions));importDirectory(copy);
        for(String code:List.of(SYNC_ORDERS,SYNC_CLOSURES)){var definition=commands.findByCode(code);assertEquals(tenant,definition.getTenantId());var config=json.readTree(definition.getExecutionConfig());assertEquals(code,config.path("handler").asText());assertEquals(1,config.path("handlerParams").path("procurementOrderReadContracts").size());assertEquals(SYNC_CLOSURES.equals(code),config.path("handlerParams").path("tenantProjectionRead").asBoolean());}
        writeArtifact("p12-read-server-policy.json",Map.of("originCommandSha256",sha(original.resolve("config/commands/procurement.json")),"configuredCommandSha256",sha(commandFile),"metadataDiffersFromProductionDefault",true,"tenantId",tenant.toString(),"contract",orderReadContract(),"customer007Verified",false));
    }
    protected Result orderSyncCall(String bearer,String command,String execution) throws Exception {
        assertTrue(Set.of(SYNC_ORDERS,SYNC_CLOSURES).contains(command));MetaContext.clear();org.springframework.test.web.servlet.MvcResult result;observed.publicRequestActive=true;
        try {result=mvc.perform(post("/api/meta/commands/execute/"+command).header("Authorization","Bearer "+bearer).header("X-Locale","en-US").contentType("application/json").content(json.writeValueAsBytes(Map.of("targetRecordPid",execution,"payload",Map.of())))).andReturn();}
        finally {observed.publicRequestActive=false;}context();var body=json.readTree(result.getResponse().getContentAsByteArray());
        var diagnostic=new LinkedHashMap<String,Object>();diagnostic.put("commandCode",command);diagnostic.put("targetRecordPid",execution);diagnostic.put("httpStatus",result.getResponse().getStatus());diagnostic.put("businessCode",safeNumericCode(body.path("code")));diagnostic.put("contextBusinessCode",safeNumericCode(body.path("context").path("code")));diagnostic.put("typedTargetNotFoundObserved",observed.targetNotFound.get());diagnostic.put("readCount",orderReadCount.get());diagnostic.put("rawResponseOrCredentialsPersisted",false);writeArtifact("public-p12-request-"+(++publicRequestSequence)+".json",diagnostic);return new Result(result.getResponse().getStatus(),body);
    }
    /** Only the matched native order's proven CAS metadata changes; every other order/domain stays exact. */
    @SuppressWarnings("unchecked")
    protected static void assertExactOrderCasReplay(String pid,Map<String,Object> before,Map<String,Object> after) {
        var oldRows=(List<Map<String,Object>>)before.get("order");var newRows=(List<Map<String,Object>>)after.get("order");var oldTargets=oldRows.stream().filter(row->pid.equals(row.get("pid"))).toList();var newTargets=newRows.stream().filter(row->pid.equals(row.get("pid"))).toList();assertEquals(1,oldTargets.size());assertEquals(1,newTargets.size());
        var old=oldTargets.getFirst();var fresh=newTargets.getFirst();assertEquals(new BigDecimal(old.get("row_version").toString()).longValueExact()+1,new BigDecimal(fresh.get("row_version").toString()).longValueExact());
        assertFalse(LocalDateTime.parse(fresh.get("updated_at").toString().replace(' ','T')).isBefore(LocalDateTime.parse(old.get("updated_at").toString().replace(' ','T'))));
        var a=new LinkedHashMap<>(before);var b=new LinkedHashMap<>(after);a.put("order",oldRows.stream().map(row->replayRow(pid,row)).toList());b.put("order",newRows.stream().map(row->replayRow(pid,row)).toList());assertEquals(a,b);
    }

    protected static final String GENERATE="qo_procurement_execution_common:generate_orders";
    protected static final List<String> ORDER_PERMISSIONS=List.of("meta.command.execute","qo.procurement.integrate",
        "model.qo_procurement_execution_common.read","model.s2p_sourcing_event.read","model.pr_supplier_common.read",
        "model.qo_procurement_allocation_common.read","model.s2p_bid.read","model.qo_procurement_submission_common.read",
        "model.qo_procurement_submission_line_common.read","model.pr_purchase_request.read","model.prod_product.read",
        "model.customer_pcba_int_kingdee_connection.read","model.customer_pcba_int_external_ref.read");
    protected final AtomicInteger orderSaveCount=new AtomicInteger(),orderLookupCount=new AtomicInteger();
    protected com.fasterxml.jackson.databind.JsonNode savedOrder;
    protected boolean ambiguousOrderSave;
    protected String baselineOrderExecution;
    protected record PreparedOrder(Submitted quote,String execution,String supplier){}
    /** The tested execution itself enters the registered controller with a separate exact-role authenticated actor. */



    protected PreparedOrder prepareAllocatedOrder() throws Exception {
        assertTrue(extensions.getCommandHandler(GENERATE).isPresent());configurePolicy();assertEquals(200,call(token,selected("11")).status());
        var submitted=prepareSubmittedQuote();
        var created=fixture("qo_procurement_task_common:allocate",()->new TransactionTemplate(transactions).execute(status->service("ProcurementAllocationService",new Class<?>[]{DataAccessor.class},new Object[]{db},"allocate",new Class<?>[]{String.class,Map.class},task,
            Map.of("sourceDemands",List.of(Map.of("sourcePid",submitted.source(),"sourceRevision",submitted.sourceRevision(),"quantity","300.000001")),"allocations",List.of(Map.of("quotePid",submitted.quote(),"sourcePid",submitted.source(),"submissionPid",submitted.submission(),"quantity","300.000001"))))));
        assertTrue(created instanceof List<?>);var group=(Map<?,?>)((List<?>)created).getFirst();String execution=group.get("executionPid").toString();
        String supplier=fixture(SYNC,()->db.getById("s2p_bid",submitted.quote()).get("s2p_bid_supplier_id").toString());
        fixture(SYNC,()->db.create("customer_pcba_int_external_ref",Map.of("customer_pcba_ref_external_system","KINGDEE","customer_pcba_ref_instance_key",connection,
            "customer_pcba_ref_account_id",provider,"customer_pcba_ref_external_form_id","FixtureSupplier","customer_pcba_ref_external_id","supplier-local","customer_pcba_ref_local_model_code","pr_supplier_common","customer_pcba_ref_local_pid",supplier)));
        baselineOrderExecution=execution;return new PreparedOrder(submitted,execution,supplier);
    }
    protected String orderActor(boolean integration) {
        context();String email=provider+"-order-"+(integration?"allow":"deny")+"@example.invalid";
        var user=users.signUp(email,PASSWORD,"Public order local fixture",provider+"-order-"+(integration?"allow":"deny"));var member=members.addMember(user.getId(),tenant,"active");
        var codes=new ArrayList<>(ORDER_PERMISSIONS);if(!integration)codes.remove("qo.procurement.integrate");assertEquals(integration?13:12,codes.size());
        return actorToken(user.getId(),user.getPid(),member.getId(),email,codes);
    }
    protected Map<String,Object> orderContract() {
        var row=new LinkedHashMap<String,Object>();row.put("connectionPid",connection);row.put("accountId",provider);row.put("formId","FixturePurchase");
        row.put("markerField","Marker");row.put("orderIdField","OrderId");row.put("billNoField","BillNo");row.put("linesField","Lines");
        row.put("headerFields",Map.of("supplierExternalId","Supplier.Id","organization","Organization.Id","currency","Currency.Id","paymentTerms","Payment.Id","orderDate","Date"));
        row.put("lineFields",Map.of("materialExternalId","Material.Id","quantity","Quantity","unitPrice","Price","unit","Unit.Id","taxRate","TaxRate","taxIncluded","TaxIncluded","deliveryDate","DeliveryDate"));
        row.put("saveOptions",Map.of("fixedHeaderFields",Map.of("BillType.Number","CONTROLLED-PO"),
            "fixedLineFields",Map.of("SourceType","CONTROLLED-SOURCE"),"sourceLink",Map.of(
                "entityKey","Lines_Link","ruleId","controlled-rule","sourceTableNumber","CONTROLLED_SOURCE_ENTRY",
                "fieldKeys",Map.of("ruleId","Lines_Link_Rule","sourceTableNumber","Lines_Link_Table","sourceBillId","Lines_Link_Bill",
                    "sourceEntryId","Lines_Link_Entry","originalBaseQty","Lines_Link_OldQty","baseQty","Lines_Link_Qty"),
                "baseUnitRatios",Map.of("unit-local","2.5"),"originalQuantityMode","allocatedBaseQuantity")));
        row.put("supplierReferenceFormId","FixtureSupplier");row.put("organizationValues",Map.of("LOCAL-ORG","org-local"));row.put("currencyValues",Map.of("CNY","currency-local"));
        row.put("paymentTermValues",Map.of("Net 30","payment-local"));row.put("unitValues",Map.of("PCS","unit-local"));row.put("lookupAbsenceAuthoritative",true);row.put("saveCreatesOrder",true);return row;
    }
    protected void configureOrderPolicy() throws Exception {
        context();Path original=Path.of(procurementDirectory);Path copy=Files.createDirectory(caseEvidence.resolve("order-dsl"));
        try(var files=Files.walk(original)){for(Path path:files.toList()){Path target=copy.resolve(original.relativize(path));if(Files.isDirectory(path))Files.createDirectories(target);else Files.copy(path,target);}}
        Path commandsFile=copy.resolve("config/commands/procurement.json");Files.copy(commandsFile,caseEvidence.resolve("order-production-default-command.json"));
        var definitions=json.readTree(commandsFile.toFile());int changed=0;
        for(var definition:definitions)if(GENERATE.equals(definition.path("code").asText())){
            var policies=(ArrayNode)definition.path("handlerParams").path("procurementOrderWriteContracts");assertTrue(policies.isEmpty());policies.add(json.valueToTree(orderContract()));changed++;
        }
        assertEquals(1,changed);Files.write(commandsFile,json.writerWithDefaultPrettyPrinter().writeValueAsBytes(definitions));importDirectory(copy);
        var definition=commands.findByCode(GENERATE);assertEquals(tenant,definition.getTenantId());assertEquals("qo_procurement_execution_common",definition.getModelCode());
        var config=json.readTree(definition.getExecutionConfig());assertEquals(GENERATE,config.path("handler").asText());assertEquals("qo.procurement.integrate",config.path("permissions").get(0).asText());
        assertTrue(config.path("handlerParams").path("tenantSecretRead").asBoolean());
        writeArtifact("order-server-policy.json",Map.of("originCommandSha256",sha(original.resolve("config/commands/procurement.json")),"configuredCommandSha256",sha(commandsFile),"tenantId",tenant.toString(),"metadataDiffersFromProductionDefault",true,"customer006Verified",false,"contract",orderContract()));
    }
    protected Result orderCall(String bearer,String execution,Map<String,Object> payload) throws Exception {
        MetaContext.clear();org.springframework.test.web.servlet.MvcResult result;observed.publicRequestActive=true;
        try {result=mvc.perform(post("/api/meta/commands/execute/"+GENERATE).header("Authorization","Bearer "+bearer).header("X-Locale","en-US")
            .contentType("application/json").content(json.writeValueAsBytes(Map.of("targetRecordPid",execution,"payload",payload)))).andReturn();}
        finally {observed.publicRequestActive=false;}context();
        var body=json.readTree(result.getResponse().getContentAsByteArray());
        var diagnostics=new LinkedHashMap<String,Object>();diagnostics.put("commandCode",GENERATE);diagnostics.put("targetRecordPid",execution);diagnostics.put("httpStatus",result.getResponse().getStatus());
        diagnostics.put("businessCode",safeNumericCode(body.path("code")));diagnostics.put("contextBusinessCode",safeNumericCode(body.path("context").path("code")));
        diagnostics.put("saveCount",orderSaveCount.get());diagnostics.put("lookupCount",orderLookupCount.get());diagnostics.put("rawResponseOrCredentialsPersisted",false);
        writeArtifact("public-order-request-"+(++publicRequestSequence)+".json",diagnostics);return new Result(result.getResponse().getStatus(),body);
    }

    protected Result call(String bearer,Map<String,Object> payload) throws Exception {
        MetaContext.clear();org.springframework.test.web.servlet.MvcResult result;observed.publicRequestActive=true;
        try {result=mvc.perform(post("/api/meta/commands/execute/"+SYNC).header("Authorization","Bearer "+bearer)
            .header("X-Locale","en-US").contentType("application/json").content(json.writeValueAsBytes(Map.of("targetRecordPid",task,"payload",payload)))).andReturn();}
        finally {observed.publicRequestActive=false;}
        context();var body=json.readTree(result.getResponse().getContentAsByteArray());
        var diagnostic=new LinkedHashMap<String,Object>();diagnostic.put("commandCode",SYNC);diagnostic.put("targetRecordPid",task);
        diagnostic.put("tenantId",tenant.toString());diagnostic.put("httpStatus",result.getResponse().getStatus());
        diagnostic.put("businessCode",safeNumericCode(body.path("code")));
        diagnostic.put("contextBusinessCode",safeNumericCode(body.path("context").path("code")));
        diagnostic.put("loginCount",loginCount.get());diagnostic.put("queryCount",queryCount.get());
        diagnostic.put("typedTargetNotFoundObserved",observed.targetNotFound.get());
        diagnostic.put("safeTypedClassName",observed.targetNotFound.get()?com.auraboot.framework.meta.exception.MetaRecordNotFoundException.class.getName():null);
        diagnostic.put("responseShape",body.isObject()?"object":body.getNodeType().name());
        String internalResponse=body.toString();
        diagnostic.put("targetBoundaryReadDeniedObserved",internalResponse.contains("Access denied: you do not have permission to view this record"));
        diagnostic.put("unknownTargetObserved",internalResponse.contains("Record not found") || internalResponse.contains("RECORD_NOT_FOUND"));
        diagnostic.put("messageOrDetailPersisted",false);
        diagnostic.put("targetReadPermissionVerified",userPermissions.hasPermission(tenant,userId,memberId,"model.s2p_sourcing_event.read"));
        diagnostic.put("rawResponsePersisted",false);diagnostic.put("credentialsOrHeadersPersisted",false);
        writeArtifact("public-request-"+(++publicRequestSequence)+".json",diagnostic);
        return new Result(result.getResponse().getStatus(),body);
    }
    static String safeNumericCode(com.fasterxml.jackson.databind.JsonNode value) {
        return (value.isIntegralNumber() || value.isTextual()) && value.asText().matches("[0-9]{1,12}")?value.asText():null;
    }
    /** Only the exact duplicate source's proven CAS lock metadata may differ; every other field/row stays strict. */
    @SuppressWarnings("unchecked")
    static void assertReplaySnapshot(String sourcePid,Map<String,Object> before,Map<String,Object> after) {
        var oldRows=(List<Map<String,Object>>)before.get("source");var newRows=(List<Map<String,Object>>)after.get("source");
        assertNotNull(oldRows);assertNotNull(newRows);
        var oldTargets=oldRows.stream().filter(row->sourcePid.equals(row.get("pid"))).toList();
        var newTargets=newRows.stream().filter(row->sourcePid.equals(row.get("pid"))).toList();
        assertEquals(1,oldTargets.size());assertEquals(1,newTargets.size());
        var oldRow=oldTargets.getFirst();var newRow=newTargets.getFirst();
        assertNotNull(oldRow.get("row_version"));assertNotNull(newRow.get("row_version"));
        long oldVersion=new BigDecimal(oldRow.get("row_version").toString()).longValueExact();
        long newVersion=new BigDecimal(newRow.get("row_version").toString()).longValueExact();
        assertEquals(Math.addExact(oldVersion,1),newVersion,"Exactly one source CAS lock must increment the native row version once");
        assertNotNull(oldRow.get("updated_at"));assertNotNull(newRow.get("updated_at"));
        var oldTime=LocalDateTime.parse(oldRow.get("updated_at").toString().replace(' ','T'));
        var newTime=LocalDateTime.parse(newRow.get("updated_at").toString().replace(' ','T'));
        assertFalse(newTime.isBefore(oldTime),"Source lock timestamp cannot move backwards");
        var normalizedBefore=new LinkedHashMap<>(before);var normalizedAfter=new LinkedHashMap<>(after);
        normalizedBefore.put("source",oldRows.stream().map(row->replayRow(sourcePid,row)).toList());
        normalizedAfter.put("source",newRows.stream().map(row->replayRow(sourcePid,row)).toList());
        assertEquals(normalizedBefore,normalizedAfter,"No other metadata, business field, row identity/order or domain may differ");
    }
    /** Critical-source replay locks the exact associated quote before returning unchanged. */
    @SuppressWarnings("unchecked")
    static void assertCriticalReplaySnapshot(String sourcePid,String quotePid,Map<String,Object> before,Map<String,Object> after) {
        var oldRows=(List<Map<String,Object>>)before.get("quote");var newRows=(List<Map<String,Object>>)after.get("quote");
        assertNotNull(oldRows);assertNotNull(newRows);
        var oldTargets=oldRows.stream().filter(row->quotePid.equals(row.get("pid"))).toList();
        var newTargets=newRows.stream().filter(row->quotePid.equals(row.get("pid"))).toList();
        assertEquals(1,oldTargets.size());assertEquals(1,newTargets.size());
        var oldRow=oldTargets.getFirst();var newRow=newTargets.getFirst();
        long oldVersion=new BigDecimal(oldRow.get("row_version").toString()).longValueExact();
        long newVersion=new BigDecimal(newRow.get("row_version").toString()).longValueExact();
        assertEquals(Math.addExact(oldVersion,1),newVersion,"Exactly the associated quote CAS lock must increment once");
        var oldTime=LocalDateTime.parse(oldRow.get("updated_at").toString().replace(' ','T'));
        var newTime=LocalDateTime.parse(newRow.get("updated_at").toString().replace(' ','T'));
        assertFalse(newTime.isBefore(oldTime),"Associated quote lock timestamp cannot move backwards");
        var normalizedBefore=new LinkedHashMap<>(before);var normalizedAfter=new LinkedHashMap<>(after);
        normalizedBefore.put("quote",oldRows.stream().map(row->replayRow(quotePid,row)).toList());
        normalizedAfter.put("quote",newRows.stream().map(row->replayRow(quotePid,row)).toList());
        assertReplaySnapshot(sourcePid,normalizedBefore,normalizedAfter);
    }
    protected static Map<String,Object> replayRow(String sourcePid,Map<String,Object> row) {
        var copy=new LinkedHashMap<>(row);
        if(sourcePid.equals(copy.get("pid"))) {copy.remove("row_version");copy.remove("updated_at");}
        return copy;
    }
    protected static List<String> sourceActorPermissions(boolean integration) {
        var codes=new ArrayList<String>();codes.add("meta.command.execute");codes.addAll(RELATED_READ_PERMISSIONS);
        if(integration) codes.add("qo.procurement.integrate");return List.copyOf(codes);
    }
    protected String actorToken(Long id,String pid,Long member,String email,List<String> codes) {
        return actorToken(id,pid,member,email,codes,PASSWORD);
    }
    protected String actorToken(Long id,String pid,Long member,String email,List<String> codes,String password) {
        context();var role=new Role();role.setPid(com.auraboot.framework.common.util.UniqueIdGenerator.generate());role.setCode(provider+"_"+id);role.setName(provider);role.setType("custom");role.setScopeType("tenant");role.setStatus("active");role.setTenantId(tenant);role.setIsDefault(false);role.setIsSystem(false);role.setDeletedFlag(false);role.setPriority(100);role.setCreatedAt(Instant.now());role.setUpdatedAt(Instant.now());role=roles.createRole(role);
        var permissionIds=new ArrayList<Long>();for(String code:codes){var permission=permissions.findByCode(code);assertNotNull(permission);permissionIds.add(permission.getId());}
        assertTrue(roles.assignPermissions(role.getId(),permissionIds));roles.setDefaultDataScopeType(role.getId(),"all");assertTrue(userRoles.assignRolesToMember(member,List.of(role.getId()),tenant,userId));
        MetaContext.setContext(tenant,id,pid,provider);MetaContext.setMemberId(member);
        userPermissions.evictUserPermissions(tenant,id);
        var assignments=userRoles.findByMemberIdAndTenantId(member,tenant);
        validateActorRoleIsolation(assignments.stream().map(com.auraboot.framework.rbac.entity.UserRole::getRoleId).toList(),role.getId(),
            userPermissions.hasPermission(tenant,id,member,"*"),userPermissions.hasPermission(tenant,id,member,"qo.procurement.integrate"),codes.contains("qo.procurement.integrate"));
        var request=new AuthenticationRequest();request.setEmail(email);request.setPassword(password);var authenticated=authentication.authenticate(request);
        assertEquals(tenant,authenticated.getTenantId());assertEquals(id,authenticated.getUserId());assertNotNull(authenticated.getJwt());
        var identity=new LinkedHashMap<String,Object>();identity.put("userPid",pid);identity.put("memberId",member.toString());identity.put("email",email);
        identity.put("roleCode",role.getCode());identity.put("permissionCodes",codes);identity.put("authenticationVerified",true);
        identity.put("exactCustomRoleOnlyVerified",true);identity.put("wildcardDeniedVerified",true);identity.put("dataScopeType","all");
        identity.put("dataScopeAuthority","Synthetic fixture role only inside the newly created independent tenant; not a production default policy");
        retainedActors.add(identity);
        return authenticated.getJwt();
    }
    /** Extra bootstrap role or wildcard must never become a hidden public-request authority. */
    static void validateActorRoleIsolation(List<Long> actualRoles,Long expectedRole,boolean wildcard,boolean integration,boolean expectedIntegration) {
        assertEquals(List.of(expectedRole),actualRoles,"Public actor must have only its exact newly created custom role");
        assertFalse(wildcard,"Public actor must not inherit bootstrap wildcard authority");
        assertEquals(expectedIntegration,integration,"Denial actor differs only by integration permission");
    }
    protected void context(){MetaContext.setContext(tenant,userId,actor,provider);MetaContext.setMemberId(memberId);}
    /** Preparation/read observation only; the public tested command never runs inside this synthetic permit. */
    protected <T>T fixture(String code,Supplier<T> work){context();return MetaContext.runWithCommandPermitPlan("ALL",userId,null,null,()->MetaContext.runWithAuthorizedCommandCode(code,work));}
    protected Object service(String name,Class<?>[] constructor,Object[] arguments,String method,Class<?>[] types,Object... values) {
        try{Class<?> type=extensions.getCommandHandler(SYNC).orElseThrow().getClass().getClassLoader().loadClass("com.auraboot.quote.engine.procurement."+name);return type.getMethod(method,types).invoke(type.getConstructor(constructor).newInstance(arguments),values);}
        catch(InvocationTargetException error){throw error.getCause() instanceof RuntimeException runtime?runtime:new IllegalStateException(error.getCause());}
        catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
    }
    protected List<Map<String,Object>> sources(){return fixture(SYNC,()->db.query("pr_purchase_request",Map.of("qo_pr_source_task_pid",task)));}
    protected List<Map<String,Object>> references(){return fixture(SYNC,()->db.query("customer_pcba_int_external_ref",Map.of("customer_pcba_ref_instance_key",connection)));}
    protected Map<String,Object> formalSnapshot(String submissionPid){return fixture(SYNC,()->Map.of(
        "submission",db.getById(domainModel("submission"),submissionPid),
        "submission_line",db.query(domainModel("submission_line"),Map.of("qo_pr_submission_line_submission_pid",submissionPid))));}
    protected Map<String,Object> snapshotTenant(Long requestedTenant) {
        Long current=tenant;tenant=requestedTenant;
        try{return snapshot();}finally{tenant=current;context();}
    }
    @SuppressWarnings("unchecked")
    protected static void assertPriorFactsUnchanged(Map<String,Object> before,Map<String,Object> after) {
        for(var entity:before.entrySet()) {
            var rows=(List<Map<String,Object>>)entity.getValue();var current=(List<Map<String,Object>>)after.get(entity.getKey());
            for(var row:rows) assertTrue(current.contains(row),"Prior case business fact must remain unchanged: "+entity.getKey()+"/"+row.get("pid"));
        }
    }
    protected Map<String,Object> snapshot(){return fixture(SYNC,()->{
        var result=new LinkedHashMap<String,Object>();
        for(String entity:List.of("task","source","quote","quote_line","history","submission","submission_line","allocation","execution","order","order_line"))
            result.put(entity,db.query(domainModel(entity),Map.of()));
        result.put("externalReferences",db.query("customer_pcba_int_external_ref",Map.of()));return result;
    });}
    /** Resolve the artifact's actual domain model mapping; do not invent physical identifiers. */
    protected String domainModel(String entity) {
        try{return (String)extensions.getCommandHandler(SYNC).orElseThrow().getClass().getClassLoader()
            .loadClass("com.auraboot.quote.engine.procurement.ProcurementData").getMethod("model",String.class).invoke(null,entity);}
        catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
    }
    protected static String pid(Map<?,?> row){Object value=row.containsKey("recordPid")?row.get("recordPid"):row.get("pid");assertNotNull(value);return value.toString();}
    protected static String required(String name){String value=System.getenv(name);assertNotNull(value,name+" required");assertFalse(value.isBlank());return value;}
    protected static Map<String,Object> selected(String... entries){return Map.of("selected",Arrays.stream(entries).map(entry->Map.of("requisitionId","100","entryId",entry)).toList(),"pageSize",20);}
    protected static String row(String entry,int remaining,String status){return "[[100,\"LOCAL-REQ\","+entry+",900,\"LOCAL-MATERIAL\",\"Local material\",\"local\",1500,1400,400,300,"+remaining+",\"PCS\",\"2066-12-01T00:00:00\",\"LOCAL-ORG\",\""+status+"\",\"A\",\"A\"]]";}
    protected static String combine(String first,String second){return first.substring(0,first.length()-1)+","+second.substring(1);}
}
