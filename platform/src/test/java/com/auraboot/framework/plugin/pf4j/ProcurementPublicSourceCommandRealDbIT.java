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

/** Real registered PF4J handler/controller/security/PG transaction; only external ERP is loopback synthetic.
 * Independent tenant imports authoritative DSL with an explicitly different local eligibility policy.
 * Does not prove customer003 selection/callback, production ERP admission, or a browser journey.
 */
@SpringBootTest(classes=TestApplication.class,properties={
    "spring.datasource.url=${TEST_DATABASE_URL}","spring.data.redis.database=${TEST_REDIS_DATABASE}",
    "aura.plugins.dir=${PROCUREMENT_SOURCE_PLUGIN_DIR}",
    "security.field-encryption.key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="})
@ActiveProfiles("integration-test")
@Import(ProcurementPublicSourceCommandRealDbIT.IsolatedScheduling.class)
@EnabledIfEnvironmentVariable(named="PROCUREMENT_PUBLIC_SOURCE_IT",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProcurementPublicSourceCommandRealDbIT {
    private static final String SYNC="qo_procurement_task_common:sync_sources";
    private static final String PASSWORD="public-source-local-fixture-123";
    // The target command permit is s2p-only. Related reads still require actual Rule Center permissions.
    private static final List<String> RELATED_READ_PERMISSIONS=List.of(
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
    private DynamicDataAccessorImpl db;
    private MockMvc mvc;
    private Long tenant,userId,memberId;
    private String actor,provider,token,deniedToken,task,connection,procurementDirectory;
    private HttpServer server;
    private Path caseEvidence;
    private Long baselineTenant, isolationTenant;
    private String baselineTargetTask;
    private Map<String,Object> baselineBootstrap, isolationBootstrap;
    private Map<String,Object> priorCaseFacts;
    private int publicRequestSequence;
    private Map<String,Object> retainedBootstrapPrincipal;
    private final List<Map<String,Object>> retainedActors=new ArrayList<>();
    private final List<String> retainedTaskPids=new ArrayList<>();
    private volatile String response;
    private final AtomicInteger loginCount=new AtomicInteger(),queryCount=new AtomicInteger();

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
        volatile boolean publicRequestActive;
        final java.util.concurrent.atomic.AtomicBoolean targetNotFound=new java.util.concurrent.atomic.AtomicBoolean();
        @org.aspectj.lang.annotation.AfterThrowing(pointcut="execution(* com.auraboot.framework.meta.service.DynamicDataService+.getById(..)) && args(model,recordPid)",throwing="failure")
        public void recordMissingTarget(String model,String recordPid,RuntimeException failure) {
            if(publicRequestActive && Objects.equals(tenant,MetaContext.getCurrentTenantId()) && "s2p_sourcing_event".equals(model)
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
        loginCount.set(0);queryCount.set(0);publicRequestSequence=0;server=null;task=null;connection=null;observed.tenant=null;observed.missingTargetPid=null;observed.targetNotFound.set(false);
        prepareEvidence(test);
        assertEquals("13",System.getenv("TEST_REDIS_DATABASE"));
        try(var sql=postgres.getConnection()){assertEquals("enterprise_22",sql.getCatalog());}
        assertTrue(extensions.getCommandHandler(SYNC).isPresent(),"Frozen production PF4J artifact must register source sync");
        provider="ps_"+UUID.randomUUID().toString().replace("-","").substring(0,24);
        var owner=users.signUp(provider+"@example.invalid",PASSWORD,"Public source local fixture",provider);
        userId=owner.getId();actor=owner.getPid();
        boolean isolated=test.getTestMethod().orElseThrow().getName().equals("publicRoleAndPayloadCannotGrantIntegrationOrSecretCapabilities");
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
            http.getRequestBody().readAllBytes();queryCount.incrementAndGet();
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);http.sendResponseHeaders(200,bytes.length);http.getResponseBody().write(bytes);http.close();
        });server.start();
        var secret=new CloudConfigSaveRequest();secret.setConfigLevel("tenant");secret.setServiceType("integration");secret.setProviderCode(provider);secret.setEnabled(true);secret.setPriority(1);
        secret.setConfig("{\"appSecret\":\"local-test-secret\"}");configs.saveConfig(secret);
        fixture(SYNC,()->{
            String product=pid(db.create("prod_product",Map.of("prod_code",provider,"prod_name","Public source material","prod_type","raw_material","prod_unit","pcs","prod_status","active")));
            connection=pid(db.create("customer_pcba_int_kingdee_connection",Map.of("customer_pcba_kd_profile_name",provider,"customer_pcba_kd_status","active", "customer_pcba_kd_acct_id",provider,
                    "customer_pcba_kd_base_url","http://127.0.0.1:"+server.getAddress().getPort(),"customer_pcba_kd_app_id","local-app","customer_pcba_kd_username","local-reader","customer_pcba_kd_app_secret_ref","cloud-config:"+provider,"customer_pcba_kd_auth_type","app-secret")));
            db.create("customer_pcba_int_external_ref",Map.of("customer_pcba_ref_external_system","KINGDEE","customer_pcba_ref_instance_key",connection,"customer_pcba_ref_account_id",provider,"customer_pcba_ref_external_form_id","BD_MATERIAL","customer_pcba_ref_external_id","900","customer_pcba_ref_local_model_code","prod_product","customer_pcba_ref_local_pid",product));return null;
        });
        task=fixture("qo_procurement_task_common:create",()->pid((Map<?,?>)service("ProcurementTaskService",new Class<?>[]{DataAccessor.class},new Object[]{db},"create",new Class<?>[]{Object.class,String.class,Map.class},tenant,actor,Map.of("title",provider,"connectionPid",connection))));
        retainedTaskPids.add(task);
        if(!isolated && baselineTargetTask==null) baselineTargetTask=task;
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
    private void prepareEvidence(TestInfo test) throws Exception {
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
    private static String gitRead(Path root,String... arguments) throws Exception {
        var command=new ArrayList<String>();command.add("git");command.add("-C");command.add(root.toString());command.addAll(List.of(arguments));
        var process=new ProcessBuilder(command).redirectErrorStream(true).start();
        String value=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        assertEquals(0,process.waitFor(),"Read-only frozen source identity command must succeed");return value;
    }
    private static String sha(Path path) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    private void writeArtifact(String name,Object value) throws Exception {
        Files.write(caseEvidence.resolve(name),json.writerWithDefaultPrettyPrinter().writeValueAsBytes(value),StandardOpenOption.CREATE_NEW);
    }

    private void importDirectory(Path directory) {
        var preview=imports.parseDirectory(directory.toString(),true);assertTrue(preview.isValid(),"Actual DSL import preview must validate");
        var request=ImportRequest.builder().importId(preview.getImportId()).conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE)
            .autoPublishFields(true).autoPublishModels(true).autoPublishCommands(true).autoPublishPages(true).createResourcePermissions(true).build();
        assertTrue(imports.execute(preview.getImportId(),request).isSuccess(),"Actual tenant DSL import must succeed");
    }
    private void configurePolicy() throws Exception {
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
        var changed=snapshot();assertEquals(200,call(token,selected("11")).status());assertEquals(changed,snapshot());
    }
    @Test @Order(4) void publicBatchRollsBackAnActuallyWrittenFirstSourceWhenLaterAllocatedSourceChanges() throws Exception {
        configurePolicy();assertEquals(200,call(token,selected("11")).status());var quote=prepareSubmittedQuote();
        fixture("qo_procurement_task_common:allocate",()->new TransactionTemplate(transactions).execute(status->service("ProcurementAllocationService",new Class<?>[]{DataAccessor.class},new Object[]{db},"allocate",new Class<?>[]{String.class,Map.class},task,
            Map.of("sourceDemands",List.of(Map.of("sourcePid",quote.source(),"sourceRevision",0,"quantity","300")),
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
    private record Submitted(String source,String quote,String submission){}
    /** Actual domain lifecycle preparation; no state SQL or fake confirmed allocation. */
    private Submitted prepareSubmittedQuote() {
        return fixture("qo_procurement_task_common:dispatch",()->new TransactionTemplate(transactions).execute(status->{
            String source=pid(sources().getFirst());
            String category=pid(db.create("pr_spend_category",Map.of("pr_spc_code",provider,"pr_spc_name","Local source category")));
            String supplier=fixture("pr_supplier_common:sync_directory",()->pid(db.create("pr_supplier_common",Map.of("pr_supplier_code",provider,"pr_supplier_name","Local source supplier","pr_supplier_status","enabled","qo_pr_supplier_account_id",provider))));
            fixture("qo_procurement_category_policy_common:create",()->service("ProcurementCategoryPolicyService",new Class<?>[]{DataAccessor.class,Long.class},new Object[]{db,tenant},"createPolicy",new Class<?>[]{Map.class},Map.of("categoryPid",category,"connectionPid",connection,"supplierPids",List.of(supplier),"defaultSupplierPids",List.of(supplier))));
            service("ProcurementPlanningService",new Class<?>[]{DataAccessor.class,Clock.class,Object.class},new Object[]{db,Clock.systemUTC(),tenant},"classify",new Class<?>[]{String.class,Long.class,Map.class},task,version(),Map.of("sourcePid",source,"sourceRevision",0,"categoryPid",category));
            service("ProcurementDispatchService",new Class<?>[]{DataAccessor.class,Clock.class,Long.class},new Object[]{db,Clock.systemUTC(),tenant},"dispatch",new Class<?>[]{String.class,Long.class,Map.class},task,version(),Map.of("supplierPids",List.of(supplier),"cutoffAt","2066-11-15T08:00:00Z"));
            String quote=pid(db.query("s2p_bid",Map.of("s2p_bid_sourcing_event_id",task)).getFirst());
            String contact=fixture("pr_supplier_pcba_contact:sync_directory",()->pid(db.create("pr_supplier_pcba_contact",Map.of("pr_spc_supplier_id",supplier,"pr_spc_name","Local supplier contact","qo_pr_contact_state","active"))));
            fixture("qo_procurement_membership_common:bind_verified_phone",()->db.create("qo_procurement_membership_common",Map.of("qo_pr_membership_key",provider,"qo_pr_membership_user_pid",actor,"qo_pr_membership_supplier_pid",supplier,"qo_pr_membership_contact_pid",contact,"qo_pr_membership_state","active")));
            var line=Map.<String,Object>of("sourcePid",source,"sourceRevision",0,"unitPrice","1.25","currency","CNY","unit","PCS","taxRate","0.13","taxIncluded",true,"availableQty","600","deliveryDate","2066-12-01");
            fixture("qo_procurement_quote_common:save_draft",()->service("ProcurementQuoteService",new Class<?>[]{DataAccessor.class,Clock.class},new Object[]{db,Clock.systemUTC()},"execute",new Class<?>[]{String.class,String.class,String.class,Map.class},"save_draft",quote,actor,Map.of("revision",0,"lines",List.of(line),"paymentTerms","Net 30")));
            var formal=(Map<?,?>)fixture("qo_procurement_quote_common:submit",()->service("ProcurementQuoteService",new Class<?>[]{DataAccessor.class,Clock.class},new Object[]{db,Clock.systemUTC()},"execute",new Class<?>[]{String.class,String.class,String.class,Map.class},"submit",quote,actor,Map.of("revision",1)));
            return new Submitted(source,quote,formal.get("submissionPid").toString());
        }));
    }
    private Long version(){return new BigDecimal(db.getById("s2p_sourcing_event",task).get("row_version").toString()).longValueExact();}

    private record Result(int status,com.fasterxml.jackson.databind.JsonNode body){}
    private Result call(String bearer,Map<String,Object> payload) throws Exception {
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
    private static Map<String,Object> replayRow(String sourcePid,Map<String,Object> row) {
        var copy=new LinkedHashMap<>(row);
        if(sourcePid.equals(copy.get("pid"))) {copy.remove("row_version");copy.remove("updated_at");}
        return copy;
    }
    private static List<String> sourceActorPermissions(boolean integration) {
        var codes=new ArrayList<String>();codes.add("meta.command.execute");codes.addAll(RELATED_READ_PERMISSIONS);
        if(integration) codes.add("qo.procurement.integrate");return List.copyOf(codes);
    }
    private String actorToken(Long id,String pid,Long member,String email,List<String> codes) {
        context();var role=new Role();role.setPid(com.auraboot.framework.common.util.UniqueIdGenerator.generate());role.setCode(provider+"_"+id);role.setName(provider);role.setType("custom");role.setScopeType("tenant");role.setStatus("active");role.setTenantId(tenant);role.setIsDefault(false);role.setIsSystem(false);role.setDeletedFlag(false);role.setPriority(100);role.setCreatedAt(Instant.now());role.setUpdatedAt(Instant.now());role=roles.createRole(role);
        var permissionIds=new ArrayList<Long>();for(String code:codes){var permission=permissions.findByCode(code);assertNotNull(permission);permissionIds.add(permission.getId());}
        assertTrue(roles.assignPermissions(role.getId(),permissionIds));roles.setDefaultDataScopeType(role.getId(),"all");assertTrue(userRoles.assignRolesToMember(member,List.of(role.getId()),tenant,userId));
        MetaContext.setContext(tenant,id,pid,provider);MetaContext.setMemberId(member);
        userPermissions.evictUserPermissions(tenant,id);
        var assignments=userRoles.findByMemberIdAndTenantId(member,tenant);
        validateActorRoleIsolation(assignments.stream().map(com.auraboot.framework.rbac.entity.UserRole::getRoleId).toList(),role.getId(),
            userPermissions.hasPermission(tenant,id,member,"*"),userPermissions.hasPermission(tenant,id,member,"qo.procurement.integrate"),codes.contains("qo.procurement.integrate"));
        var request=new AuthenticationRequest();request.setEmail(email);request.setPassword(PASSWORD);var authenticated=authentication.authenticate(request);
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
    private void context(){MetaContext.setContext(tenant,userId,actor,provider);MetaContext.setMemberId(memberId);}
    /** Preparation/read observation only; the public tested command never runs inside this synthetic permit. */
    private <T>T fixture(String code,Supplier<T> work){context();return MetaContext.runWithCommandPermitPlan("ALL",userId,null,null,()->MetaContext.runWithAuthorizedCommandCode(code,work));}
    private Object service(String name,Class<?>[] constructor,Object[] arguments,String method,Class<?>[] types,Object... values) {
        try{Class<?> type=extensions.getCommandHandler(SYNC).orElseThrow().getClass().getClassLoader().loadClass("com.auraboot.quote.engine.procurement."+name);return type.getMethod(method,types).invoke(type.getConstructor(constructor).newInstance(arguments),values);}
        catch(InvocationTargetException error){throw error.getCause() instanceof RuntimeException runtime?runtime:new IllegalStateException(error.getCause());}
        catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
    }
    private List<Map<String,Object>> sources(){return fixture(SYNC,()->db.query("pr_purchase_request",Map.of("qo_pr_source_task_pid",task)));}
    private List<Map<String,Object>> references(){return fixture(SYNC,()->db.query("customer_pcba_int_external_ref",Map.of("customer_pcba_ref_instance_key",connection)));}
    private Map<String,Object> formalSnapshot(String submissionPid){return fixture(SYNC,()->Map.of(
        "submission",db.getById(domainModel("submission"),submissionPid),
        "submission_line",db.query(domainModel("submission_line"),Map.of("qo_pr_submission_line_submission_pid",submissionPid))));}
    private Map<String,Object> snapshotTenant(Long requestedTenant) {
        Long current=tenant;tenant=requestedTenant;
        try{return snapshot();}finally{tenant=current;context();}
    }
    @SuppressWarnings("unchecked")
    private static void assertPriorFactsUnchanged(Map<String,Object> before,Map<String,Object> after) {
        for(var entity:before.entrySet()) {
            var rows=(List<Map<String,Object>>)entity.getValue();var current=(List<Map<String,Object>>)after.get(entity.getKey());
            for(var row:rows) assertTrue(current.contains(row),"Prior case business fact must remain unchanged: "+entity.getKey()+"/"+row.get("pid"));
        }
    }
    private Map<String,Object> snapshot(){return fixture(SYNC,()->{
        var result=new LinkedHashMap<String,Object>();
        for(String entity:List.of("task","source","quote","quote_line","history","submission","submission_line","allocation","execution","order","order_line"))
            result.put(entity,db.query(domainModel(entity),Map.of()));
        result.put("externalReferences",db.query("customer_pcba_int_external_ref",Map.of()));return result;
    });}
    /** Resolve the artifact's actual domain model mapping; do not invent physical identifiers. */
    private String domainModel(String entity) {
        try{return (String)extensions.getCommandHandler(SYNC).orElseThrow().getClass().getClassLoader()
            .loadClass("com.auraboot.quote.engine.procurement.ProcurementData").getMethod("model",String.class).invoke(null,entity);}
        catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
    }
    private static String pid(Map<?,?> row){Object value=row.containsKey("recordPid")?row.get("recordPid"):row.get("pid");assertNotNull(value);return value.toString();}
    private static String required(String name){String value=System.getenv(name);assertNotNull(value,name+" required");assertFalse(value.isBlank());return value;}
    private static Map<String,Object> selected(String... entries){return Map.of("selected",Arrays.stream(entries).map(entry->Map.of("requisitionId","100","entryId",entry)).toList(),"pageSize",20);}
    private static String row(String entry,int remaining,String status){return "[[100,\"LOCAL-REQ\","+entry+",900,\"LOCAL-MATERIAL\",\"Local material\",\"local\",1500,1400,400,300,"+remaining+",\"PCS\",\"2066-12-01T00:00:00\",\"LOCAL-ORG\",\""+status+"\",\"A\",\"A\"]]";}
    private static String combine(String first,String second){return first.substring(0,first.length()-1)+","+second.substring(1);}
}
