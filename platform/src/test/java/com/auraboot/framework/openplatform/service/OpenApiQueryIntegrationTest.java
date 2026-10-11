package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.application.security.MachineDataAccessContext;
import com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.*;
import com.auraboot.framework.openplatform.mapper.OpenPlatformAuthMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import java.time.Instant;
import java.util.*;
import static com.auraboot.framework.openplatform.service.OpenApiResourceContract.*;
import static org.assertj.core.api.Assertions.*;

/** Real two-source SQL, token/installation grants, source pinning and authenticated public HTTP. */
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class OpenApiQueryIntegrationTest extends BaseIntegrationTest {
    @Autowired private com.auraboot.framework.plugin.service.impl.PluginPublicPublicationLifecycle pluginPublications;
    @Autowired private com.auraboot.framework.plugin.service.PluginImportService pluginImports;
    @Autowired private com.auraboot.framework.plugin.mapper.PluginRecordMapper plugins;
    @Autowired private com.auraboot.framework.plugin.mapper.PluginResourceMapper pluginResources;
    @Autowired private com.auraboot.framework.plugin.service.PluginResourceService pluginResourceService;
    @Autowired private MetaModelService models;
    @Autowired private SchemaManagementService schemas;
    @Autowired private DynamicDataService data;
    @Autowired private NamedQueryService queries;
    @Autowired private OpenApiPublicationService resources;
    @Autowired private OpenApiQueryPublicationService publications;
    @Autowired private OpenApiQueryService publicQueries;
    @Autowired private OpenPlatformManagementService management;
    @Autowired private OpenPlatformTokenService tokens;
    @Autowired private OpenPlatformSecretCodec secrets;
    @Autowired private OpenPlatformAuthMapper auth;
    @Autowired private org.springframework.test.web.servlet.MockMvc http;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired private com.auraboot.framework.openplatform.mapper.OpenApiPublicationMapper publicationMapper;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired private com.auraboot.framework.openplatform.controller.OpenApiQueryPublicationController documents;
    private OpenApiResourceContract root,child;
    private OpenApiQueryContract rows,summary;
    private NamedQueryDTO rowsDefinition;
    private MachinePrincipal principalA,principalB;
    private String tokenA;
    private Set<String> scopes;

    @BeforeEach void fixture() {
        grantCommittedPermissionToTestRole("sys.connector.update","system","connector","update","Manage Open Platform");
        String nonce=UniqueIdGenerator.generate().toLowerCase();scopes=Set.of("query."+nonce+".root","query."+nonce+".child","query."+nonce+".execute");
        root=model("query_root_"+nonce,"root."+nonce,Set.of("name","zone","secret"),"query."+nonce+".root");
        child=model("query_child_"+nonce,"child."+nonce,Set.of("parent","name","zone"),"query."+nonce+".child");
        String rootA=humanCreate(root,Map.of("name","Root A","zone","A","secret","Private A"));
        String rootB=humanCreate(root,Map.of("name","Root B","zone","B","secret","Private B"));
        humanCreate(child,Map.of("name","Child A","zone","A","parent",rootA));
        humanCreate(child,Map.of("name","Forbidden sibling","zone","B","parent",rootA));
        humanCreate(child,Map.of("name","Child B","zone","B","parent",rootB));
        String join="mt_"+root.modelCode()+" a JOIN mt_"+child.modelCode()+" b ON a.pid = b.parent";
        rowsDefinition=createQuery("query_rows_"+nonce,join+" WHERE a.zone = #{params.zone}",
                List.of(nqField("pid","a.pid","string"),nqField("name","a.name","string"),nqField("childName","b.name","string")));
        var aggregate=createQuery("query_summary_"+nonce,"SELECT a.zone AS zone, COUNT(*) AS total FROM "+join+
                " WHERE a.zone = #{params.zone} GROUP BY a.zone",List.of(nqField("zone","_nq.zone","string"),nqField("total","_nq.total","number")));
        rows=publish("rows."+nonce,rowsDefinition,Map.of("pid",output("pid",Type.PID),"name",output("name",Type.STRING),"childName",output("childName",Type.STRING)));
        summary=publish("summary."+nonce,aggregate,Map.of("zone",output("zone",Type.STRING),"count",output("total",Type.INTEGER)));
        principalA=installation("A");principalB=installation("B");
    }
    private OpenApiResourceContract model(String model,String code,Set<String> names,String scope) {
        models.saveDefinition(ModelDefinition.builder().code(model).tableName("mt_"+model).displayName("Public query source fixture")
                .status("published").sourceType("physical").primaryKey("id").capabilities(ModelCapabilities.fullPhysical())
                .fields(names.stream().sorted().map(name->FieldDefinition.builder().code(name).columnName(name).dataType("STRING").displayName(name)
                        .filterable("zone".equals(name)).build()).toList()).build());
        var result=schemas.createTableByModel(model);assertThat(result.isSuccess()).as("query source schema: %s",result.getErrorMessage()).isTrue();
        var fields=new LinkedHashMap<String,Field>();fields.put("pid",output("pid",Type.PID));
        names.forEach(name->fields.put(name,output(name,"parent".equals(name)?Type.PID:Type.STRING)));
        fields.remove("secret");
        fields.put("zone",new Field("zone",Type.STRING,true,false,false,false,Set.of("EQ"),false,null,Map.of(),null));
        var contract=new OpenApiResourceContract(code,model,1,Set.of(Operation.LIST,Operation.GET),Map.of(Operation.LIST,scope,Operation.GET,scope),
                DataPolicy.INSTALLATION_FILTER,fields,50,8,5000,Set.of());
        resources.saveDraft(code,new OpenApiPublicationService.DraftRequest(0,contract));resources.publish(code,1);return contract;
    }
    private Field output(String source,Type type) {return new Field(source,type,true,false,false,false,Set.of(),false,null,Map.of(),null);}
    private String humanCreate(OpenApiResourceContract resource,Map<String,Object> values) {
        return MetaContext.runWithCommandPermitScope("ALL",()->data.create(resource.modelCode(),new HashMap<>(values))).get("pid").toString();
    }
    private NamedQueryFieldRequest nqField(String alias,String expression,String type) {
        var field=new NamedQueryFieldRequest();field.setFieldCode(alias);field.setColumnExpr(expression);field.setDataType(type);field.setSearchable(false);field.setSortable(true);return field;
    }
    private NamedQueryDTO createQuery(String code,String sql,List<NamedQueryFieldRequest> fields) {
        return createQuery(code,sql,fields,null);
    }
    private NamedQueryDTO createQuery(String code,String sql,List<NamedQueryFieldRequest> fields,com.fasterxml.jackson.databind.JsonNode baseWhere) {
        var request=new NamedQueryCreateRequest();request.setCode(code);request.setTitle("Public query fixture");request.setFromSql(sql);
        request.setBaseWhere(baseWhere);
        request.setResourceCode(root.modelCode());request.setActionCode("read");request.setFields(fields);
        var order=json.createArrayNode();fields.forEach(field->order.addObject().put("field",field.getFieldCode()).put("direction","asc"));
        request.setDefaultOrder(order);
        var created=queries.create(request);queries.updateStatus(created.getPid(),"testing");queries.updateStatus(created.getPid(),"published");return queries.findByCode(code);
    }
    private OpenApiQueryContract publish(String code,NamedQueryDTO definition,Map<String,Field> fields) {
        var source=queries.describeExecution(definition.getCode());
        Map<String,String> bindings=new HashMap<>();for(var resource:List.of(root,child))if(source.sourceModels().contains(resource.modelCode()))bindings.put(resource.modelCode(),resource.code());
        var contract=new OpenApiQueryContract(code,definition.getCode(),1,source.fingerprint(),Map.of("execute",scopes.stream().filter(s->s.endsWith(".execute")).findFirst().orElseThrow()),
                bindings,Map.of("zone",new OpenApiQueryContract.Parameter(Type.STRING,true,false,null)),fields,20,100,5000,2);
        publications.saveDraft(code,new OpenApiQueryPublicationService.DraftRequest(0,contract));publications.publish(code,1);return contract;
    }
    private MachinePrincipal installation(String zone) {
        var application=management.createApplication(new CreateApplicationRequest("Query installation "+zone,"Distinct row and output boundary"));
        var installation=management.install(application.pid(),new InstallApplicationRequest("staging",scopes,600));
        for(var source:List.of(root,child)) resources.grant(installation.pid(),source.code(),new OpenApiInstallationPolicy(source.operations(),source.fields().keySet(),Set.of(),
                List.of(new OpenApiInstallationPolicy.RowConstraint("zone",List.of(zone)))));
        publications.grant(installation.pid(),rows.code(),new OpenApiQueryPolicy(rows.fields().keySet()));
        publications.grant(installation.pid(),summary.code(),new OpenApiQueryPolicy(summary.fields().keySet()));
        var credential=management.createCredential(installation.pid());var token=tokens.issue("client_credentials",credential.clientId(),credential.clientSecret(),null);
        if("A".equals(zone))tokenA=token.accessToken();
        var identity=auth.findToken(secrets.sha256(token.accessToken()),OpenPlatformTokenService.AUDIENCE,Instant.now());
        return new MachinePrincipal(identity.tenantId(),identity.tokenPid(),"machine",scopes,identity.applicationPid(),identity.installationPid(),identity.environment(),identity.tokenPid());
    }
    private Map<String,Object> run(MachinePrincipal principal,OpenApiQueryContract contract,String zone) {
        var previous=MetaContext.snapshot();
        MetaContext.setContext(principal.tenantId(),null,principal.subjectPid(),"machine");
        try(var machine=MachineDataAccessContext.begin(principal)) {return publicQueries.execute(principal,contract.code(),new OpenApiQueryService.ExecuteRequest(Map.of("zone",zone),10,null));}
        finally {if(previous==null)MetaContext.clear();else MetaContext.restore(previous);}
    }
    @Test void pluginQueryPublicationUsesRealSourcePinsGrantsAndWithdrawal() throws Exception {
        String suffix = UniqueIdGenerator.generate().toLowerCase();
        var pluginManifest = com.auraboot.framework.plugin.dto.PluginManifest.builder().pluginId("com.example.query-" + suffix)
                .namespace("query_plugin_" + suffix).version("1.0.0").displayName("Public query plugin").build();
        var plugin = com.auraboot.framework.plugin.entity.PluginRecord.builder().pid(UniqueIdGenerator.generate())
                .tenantId(testTenant.getId()).pluginId(pluginManifest.getPluginId()).namespace(pluginManifest.getNamespace())
                .version(pluginManifest.getVersion()).displayName(pluginManifest.getDisplayName()).manifest(pluginManifest)
                .status("installed").deletedFlag(false).build();
        assertThat(plugins.insert(plugin)).isEqualTo(1);
        var contract = new OpenApiQueryContract("plugin." + suffix, rows.queryCode(), 1, rows.sourceFingerprint(),
                rows.scopes(), rows.sourceResources(), rows.parameters(), rows.fields(), rows.maxPageSize(),
                rows.maxRows(), rows.timeoutMs(), rows.maxConcurrent());
        var manifest = new com.auraboot.framework.plugin.dto.imports.PluginManifestExtended();
        manifest.setPluginId(plugin.getPluginId()); manifest.setNamespace(plugin.getNamespace()); manifest.setVersion("1.0.0");
        manifest.setPublicQueries(List.of(contract));
        var request = new com.auraboot.framework.plugin.dto.imports.ImportRequest();
        var preview = pluginImports.parseJson(json.writeValueAsString(manifest), "public-query-plugin.json");
        assertThat(preview.isValid()).as("query import preview: %s", preview.getErrors()).isTrue();
        var imported = pluginImports.execute(preview.getImportId(), request);
        assertThat(imported.isSuccess()).as("query import failed: %s", imported.getErrorMessage()).isTrue();
        assertThat(imported.getResourceCounts().get("PUBLIC_QUERY")).containsEntry("CREATE", 1);
        assertThat(publications.active(testTenant.getId(), contract.code())).contains(contract);
        assertThatThrownBy(() -> run(principalA, contract, "A")).isInstanceOf(AccessDeniedException.class);
        publications.grant(principalA.installationPid(), contract.code(), new OpenApiQueryPolicy(contract.fields().keySet()));
        String route = "/api/open/v1/queries/" + contract.code() + ":execute";
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(route)
                .header("Authorization", "Bearer " + tokenA).contentType("application/json")
                .content("{\"parameters\":{\"zone\":\"A\"},\"limit\":10}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items.length()").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].childName").value("Child A"));
        applyTestMetaContext();
        var owned = pluginResources.findByTypeAndCode(testTenant.getId(),
                com.auraboot.framework.plugin.dto.imports.ResourceType.PUBLIC_QUERY.code(), contract.code());
        assertThat(owned.getResourcePid()).isNull();
        assertThat(owned.getImportId()).isEqualTo(preview.getImportId());
        assertThat(pluginResourceService.exportPluginConfig(plugin.getPluginId()).get("publicQueries"))
                .singleElement().satisfies(declaration -> assertThat(declaration).containsEntry("code", contract.code())
                        .containsEntry("sourceFingerprint", contract.sourceFingerprint()).doesNotContainKey("draftRevision"));
        pluginResourceService.deleteResource(owned);
        assertThat(publications.active(testTenant.getId(), contract.code())).isEmpty();
        assertThat(publicationMapper.resourceSnapshots(testTenant.getId(), contract.storageCode())).hasSize(1);
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(route)
                .header("Authorization", "Bearer " + tokenA).contentType("application/json")
                .content("{\"parameters\":{\"zone\":\"A\"},\"limit\":10}"))
                 .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("open_api_capability_not_found"));
        applyTestMetaContext();
        pluginPublications.importDeclarations(manifest, request, new com.auraboot.framework.plugin.dto.imports.ImportExecuteResult(),
                plugin.getPid(), UniqueIdGenerator.generate(), testTenant.getId());
        assertThat(publicationMapper.resourceSnapshots(testTenant.getId(), contract.storageCode())).hasSize(1);
        assertThat(run(principalA, contract, "A").get("items").toString()).contains("Child A").doesNotContain("Forbidden sibling", "Root B");
    }

    @Test void administrationMetadataPinsTheExecutionSourceAndOffersOnlyCurrentListPublications() throws Exception {
        var source = publications.source(rowsDefinition.getCode());
        assertThat(source.fingerprint()).isEqualTo(queries.describeExecution(rowsDefinition.getCode()).fingerprint());
        assertThat(source.parameters()).containsExactly("zone");
        assertThat(source.sources()).extracting(OpenApiQueryValidator.SourceModel::modelCode).containsExactlyInAnyOrder(root.modelCode(), child.modelCode());
        assertThat(source.sources().stream().flatMap(model -> model.resources().stream()).map(OpenApiQueryValidator.SourceResource::code))
                .containsExactlyInAnyOrder(root.code(), child.code());
        assertThat(source.fields()).extracting(OpenApiQueryValidator.SourceField::code).containsExactlyInAnyOrder("pid", "name", "childName");
        assertThat(source.fields().stream().filter(field -> "pid".equals(field.code())).findFirst().orElseThrow().types()).contains(Type.PID, Type.STRING);
        assertThat(json.writeValueAsString(source)).doesNotContain("fromSql", "columnExpr", "a.pid", "JOIN", "tenantId", "SELECT");
        var choices = publications.sources(rowsDefinition.getTitle(), 1);
        assertThat(choices.getRecords()).extracting(OpenApiQueryPublicationService.SourceChoice::code).contains(rowsDefinition.getCode());
        assertThat(choices.getRecords().stream().filter(choice -> rowsDefinition.getCode().equals(choice.code())).findFirst().orElseThrow().available()).isTrue();
        resources.withdraw(root.code(), 1);
        assertThat(publications.source(rowsDefinition.getCode()).sources().stream().filter(model -> root.modelCode().equals(model.modelCode())).findFirst().orElseThrow().resources()).isEmpty();
    }
    @Test void administrationMetadataAndDiscoveryRejectMachineIdentityAndInvalidPagination() {
        assertThatThrownBy(() -> publications.sources("", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> publications.sources("x".repeat(201), 1)).isInstanceOf(IllegalArgumentException.class);
        var previous = MetaContext.snapshot();
        MetaContext.setContext(principalA.tenantId(), null, principalA.subjectPid(), "machine");
        try (var machine = MachineDataAccessContext.begin(principalA)) {
            assertThatThrownBy(() -> publications.source(rowsDefinition.getCode())).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> publications.sources("", 1)).isInstanceOf(AccessDeniedException.class);
        } finally { MetaContext.restore(previous); }
    }
    @Test void installationAdministrationReadsSnapshotAndDistinctCurrentGrantsIncludingWithdrawal() {
        var resourceDraft = json.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(root);
        resourceDraft.put("schemaVersion", 2);
        ((com.fasterxml.jackson.databind.node.ObjectNode) resourceDraft.get("fields")).remove("name");
        resources.saveDraft(root.code(), new OpenApiPublicationService.DraftRequest(1, json.convertValue(resourceDraft, OpenApiResourceContract.class)));
        var queryDraft = json.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(rows);
        queryDraft.put("schemaVersion", 2);
        ((com.fasterxml.jackson.databind.node.ObjectNode) queryDraft.get("fields")).remove("childName");
        publications.saveDraft(rows.code(), new OpenApiQueryPublicationService.DraftRequest(1, json.convertValue(queryDraft, OpenApiQueryContract.class)));
        publications.grant(principalA.installationPid(), rows.code(), new OpenApiQueryPolicy(Set.of("pid")));
        var resourceA = resources.installationGrants(principalA.installationPid()).stream().filter(view -> root.code().equals(view.code())).findFirst().orElseThrow();
        var resourceB = resources.installationGrants(principalB.installationPid()).stream().filter(view -> root.code().equals(view.code())).findFirst().orElseThrow();
        assertThat(resourceA.contract()).isEqualTo(root);
        assertThat(resourceA.policy().rowConstraints()).extracting(constraint -> constraint.values()).containsExactly(List.of("A"));
        assertThat(resourceB.policy().rowConstraints()).extracting(constraint -> constraint.values()).containsExactly(List.of("B"));
        var queryA = publications.installationGrants(principalA.installationPid()).stream().filter(view -> rows.code().equals(view.code())).findFirst().orElseThrow();
        var queryB = publications.installationGrants(principalB.installationPid()).stream().filter(view -> rows.code().equals(view.code())).findFirst().orElseThrow();
        assertThat(queryA.contract()).isEqualTo(rows);
        assertThat(queryA.policy().readableFields()).containsExactly("pid");
        assertThat(queryB.policy().readableFields()).containsExactlyInAnyOrderElementsOf(rows.fields().keySet());
        resources.withdraw(root.code(), 2); publications.withdraw(rows.code(), 2);
        assertThat(resources.installationGrants(principalA.installationPid()).stream().filter(view -> root.code().equals(view.code())).findFirst().orElseThrow().status()).isEqualTo("withdrawn");
        assertThat(publications.installationGrants(principalA.installationPid()).stream().filter(view -> rows.code().equals(view.code())).findFirst().orElseThrow().status()).isEqualTo("withdrawn");
        resources.revoke(principalA.installationPid(), root.code()); publications.revoke(principalA.installationPid(), rows.code());
        assertThat(resources.installationGrants(principalA.installationPid()).stream().filter(view -> root.code().equals(view.code())).findFirst().orElseThrow().policy()).isNull();
        assertThat(publications.installationGrants(principalA.installationPid()).stream().filter(view -> rows.code().equals(view.code())).findFirst().orElseThrow().policy()).isNull();
    }
    @Test void installationAdministrationRejectsMissingForeignAndMachineInstallationReads() {
        assertThatThrownBy(() -> resources.installationGrants("missing-installation")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> publications.installationGrants("missing-installation")).isInstanceOf(IllegalArgumentException.class);
        var previous = MetaContext.snapshot();
        Long administratorId = MetaContext.getCurrentUserId();
        String administratorPid = MetaContext.getCurrentUserPid();
        try {
            MetaContext.setContext(principalA.tenantId() + 1, administratorId, administratorPid, "user");
            assertThatThrownBy(() -> resources.installationGrants(principalA.installationPid())).isInstanceOfAny(AccessDeniedException.class, IllegalArgumentException.class);
            assertThatThrownBy(() -> publications.installationGrants(principalA.installationPid())).isInstanceOfAny(AccessDeniedException.class, IllegalArgumentException.class);
            MetaContext.setContext(principalA.tenantId() + 1, null, principalA.subjectPid(), "machine");
            assertThatThrownBy(() -> resources.installationGrants(principalA.installationPid())).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> publications.installationGrants(principalA.installationPid())).isInstanceOf(AccessDeniedException.class);
            MetaContext.setContext(principalA.tenantId(), null, principalA.subjectPid(), "machine");
            assertThatThrownBy(() -> resources.installationGrants(principalA.installationPid())).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> publications.installationGrants(principalA.installationPid())).isInstanceOf(AccessDeniedException.class);
        } finally { MetaContext.restore(previous); }
    }
    @Test void joinsAndAggregatesScopeEverySourceForEachInstallationWithoutHumanMembership() {
        var a=run(principalA,rows,"A");var b=run(principalB,rows,"B");
        assertThat(a.get("items").toString()).contains("Root A","Child A").doesNotContain("Forbidden sibling","Root B");
        assertThat(b.get("items").toString()).contains("Root B","Child B").doesNotContain("Root A");
        assertThat(a.get("total")).isEqualTo(1L);assertThat(b.get("total")).isEqualTo(1L);
        assertThat(run(principalA,summary,"A").get("items")).isEqualTo(List.of(Map.of("zone","A","count",1L)));
        assertThat(run(principalB,summary,"B").get("items")).isEqualTo(List.of(Map.of("zone","B","count",1L)));
        assertThat(run(principalB,rows,"A").get("items")).isEqualTo(List.of());
    }
    @Test void currentSourceGrantRevocationAndQueryOutputNarrowingAreRecheckedEveryCall() {
        assertThat(run(principalA,rows,"A").get("items").toString()).contains("Child A");
        publications.grant(principalA.installationPid(),rows.code(),new OpenApiQueryPolicy(Set.of("pid")));
        var narrowed=run(principalA,rows,"A");assertThat(narrowed.get("items").toString()).doesNotContain("Child A","Root A");
        resources.revoke(principalA.installationPid(),child.code());
        assertThatThrownBy(()->run(principalA,rows,"A")).isInstanceOf(AccessDeniedException.class);
        assertThat(run(principalB,rows,"B").get("items").toString()).contains("Child B");
    }
    @Test void sourceSemanticsCannotFloatBehindAnImmutablePublicSnapshot() {
        var update=new NamedQueryUpdateRequest();update.setActionCode("update");queries.update(rowsDefinition.getPid(),update);
        assertThatThrownBy(()->run(principalA,rows,"A")).isInstanceOf(AccessDeniedException.class).hasMessageContaining("source is unavailable");
    }
    @Test void cursorBindsParametersInstallationAndCurrentSourceFieldAuthority() {
        String parent=((Map<?,?>)((List<?>)run(principalA,rows,"A").get("items")).getFirst()).get("pid").toString();
        humanCreate(child,Map.of("name","Child A2","zone","A","parent",parent));
        MetaContext.setContext(principalA.tenantId(),null,principalA.subjectPid(),"machine");
        String cursor;
        try(var machine=MachineDataAccessContext.begin(principalA)) {
            var first=publicQueries.execute(principalA,rows.code(),new OpenApiQueryService.ExecuteRequest(Map.of("zone","A"),1,null));
            assertThat(first.get("hasMore")).isEqualTo(true);cursor=first.get("nextCursor").toString();
            var next=publicQueries.execute(principalA,rows.code(),new OpenApiQueryService.ExecuteRequest(Map.of("zone","A"),1,cursor));
            assertThat(next.get("items").toString()).contains("Child A2");assertThat(next.get("hasMore")).isEqualTo(false);
            assertThatThrownBy(()->publicQueries.execute(principalA,rows.code(),new OpenApiQueryService.ExecuteRequest(Map.of("zone","B"),1,cursor)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Cursor");
        } finally {applyTestMetaContext();}
        MetaContext.setContext(principalB.tenantId(),null,principalB.subjectPid(),"machine");
        try(var machine=MachineDataAccessContext.begin(principalB)) {
            assertThatThrownBy(()->publicQueries.execute(principalB,rows.code(),new OpenApiQueryService.ExecuteRequest(Map.of("zone","A"),1,cursor)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Cursor");
        } finally {applyTestMetaContext();}
        resources.grant(principalA.installationPid(),child.code(),new OpenApiInstallationPolicy(child.operations(),Set.of("pid","parent","zone"),Set.of(),
                List.of(new OpenApiInstallationPolicy.RowConstraint("zone",List.of("A")))));
        MetaContext.setContext(principalA.tenantId(),null,principalA.subjectPid(),"machine");
        try(var machine=MachineDataAccessContext.begin(principalA)) {
            assertThatThrownBy(()->publicQueries.execute(principalA,rows.code(),new OpenApiQueryService.ExecuteRequest(Map.of("zone","A"),1,cursor)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Cursor");
            assertThatThrownBy(()->publicQueries.execute(principalA,rows.code(),new OpenApiQueryService.ExecuteRequest(Map.of("zone","A"),1,null)))
                    .isInstanceOf(AccessDeniedException.class).hasMessageContaining("non-public");
        } finally {applyTestMetaContext();}
    }
    @Test void engineAddedBasePredicatesCannotInferHiddenFields() {
        String suffix=UniqueIdGenerator.generate().toLowerCase();var base=json.createArrayNode();
        base.addObject().put("field","secret").put("operator","eq").put("value","Private A");
        var source=createQuery("query_hidden_"+suffix,"mt_"+root.modelCode()+" a",
                List.of(nqField("pid","a.pid","string"),nqField("name","a.name","string")),base);
        var published=new OpenApiQueryContract("hidden."+suffix,source.getCode(),1,queries.describeExecution(source.getCode()).fingerprint(),rows.scopes(),
                Map.of(root.modelCode(),root.code()),Map.of(),Map.of("pid",output("pid",Type.PID),"name",output("name",Type.STRING)),20,100,5000,2);
        publications.saveDraft(published.code(),new OpenApiQueryPublicationService.DraftRequest(0,published));publications.publish(published.code(),1);
        publications.grant(principalA.installationPid(),published.code(),new OpenApiQueryPolicy(published.fields().keySet()));
        MetaContext.setContext(principalA.tenantId(),null,principalA.subjectPid(),"machine");
        try(var machine=MachineDataAccessContext.begin(principalA)) {
            assertThatThrownBy(()->publicQueries.execute(principalA,published.code(),new OpenApiQueryService.ExecuteRequest(Map.of(),10,null)))
                    .isInstanceOf(AccessDeniedException.class).hasMessageContaining("non-public");
        }finally{applyTestMetaContext();}
    }
    @Test void queryConcurrencySlotsAreSharedAcrossIndependentDatabaseTransactions() throws Exception {
        var ready=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        var held=pool.submit(()-> {
            MetaContext.setContext(principalA.tenantId(),null,principalA.subjectPid(),"machine");
            try {new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status-> {
                for(int slot=0;slot<rows.maxConcurrent();slot++)assertThat(publicationMapper.tryQuerySlot(principalA.tenantId(),rows.code(),slot)).isTrue();
                ready.countDown();
                try {assertThat(release.await(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
            });}finally{MetaContext.clear();}
        });
        try {
            assertThat(ready.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->run(principalA,rows,"A")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .hasMessageContaining("429");
            assertThatThrownBy(()->run(principalB,rows,"B")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .hasMessageContaining("429");
        }finally{release.countDown();held.get(10,java.util.concurrent.TimeUnit.SECONDS);pool.shutdownNow();}
        assertThat(run(principalA,rows,"A").get("total")).isEqualTo(1L);
    }
    @Test void boundedArrayParametersAndStructuredResultPropertiesRemainPublicOnly() {
        String suffix=UniqueIdGenerator.generate().toLowerCase();
        var source=createQuery("query_json_"+suffix,"mt_"+root.modelCode()+" a WHERE CAST(#{params.zones} AS jsonb) @> to_jsonb(a.zone)",
                List.of(nqField("payload","jsonb_build_object('name', a.name, 'hidden', 'private literal')","json")));
        var payload=new Field("payload",Type.OBJECT,true,false,false,false,Set.of(),false,null,Map.of("name",output("name",Type.STRING)),null);
        var contract=new OpenApiQueryContract("json."+suffix,source.getCode(),1,queries.describeExecution(source.getCode()).fingerprint(),rows.scopes(),
                Map.of(root.modelCode(),root.code()),Map.of("zones",new OpenApiQueryContract.Parameter(Type.ARRAY,true,false,output("item",Type.STRING))),
                Map.of("payload",payload),20,100,5000,2);
        publications.saveDraft(contract.code(),new OpenApiQueryPublicationService.DraftRequest(0,contract));publications.publish(contract.code(),1);
        publications.grant(principalA.installationPid(),contract.code(),new OpenApiQueryPolicy(contract.fields().keySet()));
        MetaContext.setContext(principalA.tenantId(),null,principalA.subjectPid(),"machine");
        try(var machine=MachineDataAccessContext.begin(principalA)) {
            var result=publicQueries.execute(principalA,contract.code(),new OpenApiQueryService.ExecuteRequest(Map.of("zones",List.of("A","B")),10,null));
            assertThat(result.get("items")).isEqualTo(List.of(Map.of("payload",Map.of("name","Root A"))));
            assertThatThrownBy(()->publicQueries.execute(principalA,contract.code(),new OpenApiQueryService.ExecuteRequest(Map.of("zones",List.of("A",12)),10,null)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("String");
        }finally{applyTestMetaContext();}
    }
    @Test void databaseStatementDeadlineCancelsTheQueryAndReturnsAStableTimeout() {
        String suffix=UniqueIdGenerator.generate().toLowerCase();
        var source=createQuery("query_timeout_"+suffix,"mt_"+root.modelCode()+" a WHERE a.zone = #{params.zone}",
                List.of(nqField("name","a.name || pg_sleep(0.2)::text","string")));
        var contract=new OpenApiQueryContract("timeout."+suffix,source.getCode(),1,queries.describeExecution(source.getCode()).fingerprint(),rows.scopes(),
                Map.of(root.modelCode(),root.code()),rows.parameters(),Map.of("name",output("name",Type.STRING)),20,100,30,2);
        publications.saveDraft(contract.code(),new OpenApiQueryPublicationService.DraftRequest(0,contract));publications.publish(contract.code(),1);
        publications.grant(principalA.installationPid(),contract.code(),new OpenApiQueryPolicy(contract.fields().keySet()));
        var boundary=new org.springframework.transaction.support.TransactionTemplate(transactions);
        boundary.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
        assertThatThrownBy(()->boundary.executeWithoutResult(status->run(principalA,contract,"A")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("504").hasMessageContaining("open_api_query_timeout");
        assertThat(run(principalA,rows,"A").get("total")).isEqualTo(1L);
    }
    @Test void exhaustedSourceRateBudgetReturnsPublic429AndRecordsTheMachineDenial() throws Exception {
        var policy = new com.auraboot.framework.meta.entity.NamedQueryPolicy();policy.setRateLimitPerMinute(1);
        var update = new NamedQueryUpdateRequest();update.setPolicy(policy);queries.update(rowsDefinition.getPid(), update);
        var source = queries.describeExecution(rowsDefinition.getCode());
        String quotaCode = rows.code() + ".quota";
        var next = new OpenApiQueryContract(quotaCode, rows.queryCode(), 1, source.fingerprint(),
                rows.scopes(), rows.sourceResources(), rows.parameters(), rows.fields(), rows.maxPageSize(), rows.maxRows(), rows.timeoutMs(), rows.maxConcurrent());
        publications.saveDraft(quotaCode, new OpenApiQueryPublicationService.DraftRequest(0, next));publications.publish(quotaCode, 1);
        publications.grant(principalA.installationPid(), quotaCode, new OpenApiQueryPolicy(rows.fields().keySet()));
        String route = "/api/open/v1/queries/" + quotaCode + ":execute";
        String marker = "quota-" + UniqueIdGenerator.generate();
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(route)
                .header("Authorization", "Bearer " + tokenA).contentType("application/json")
                .content("{\"parameters\":{\"zone\":\"A\"},\"limit\":10}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        var rejected = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(route)
                .header("Authorization", "Bearer " + tokenA).header("X-Request-Id", marker).contentType("application/json")
                .content("{\"parameters\":{\"zone\":\"A\"},\"limit\":10}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isTooManyRequests())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Retry-After", "60"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Request-Id", marker))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("open_api_query_rate_limited"))
                .andReturn().getResponse();
        assertThat(rejected.getContentAsString()).doesNotContain(rowsDefinition.getCode(), root.modelCode(), child.modelCode(), tokenA, "fromSql");
        applyTestMetaContext();
        var audits = management.listCallAudits(principalA.installationPid(), marker, null, 10);assertThat(audits).hasSize(1);
        var audit = audits.getFirst();assertThat(audit.status()).isEqualTo(429);
        assertThat(audit.actorKind()).isEqualTo("machine");assertThat(audit.applicationPid()).isEqualTo(principalA.applicationPid());
        assertThat(audit.installationPid()).isEqualTo(principalA.installationPid());assertThat(audit.resource()).isEqualTo(quotaCode);
        assertThat(audit.operation()).isEqualTo("execute");assertThat(audit.capability()).isEqualTo("query." + quotaCode + ".execute");
        assertThat(audit.idempotentReplay()).isNull();assertThat(MachineDataAccessContext.isActive()).isFalse();
    }
    @Test void realPublicHttpRejectsUnknownEnvelopeParametersAndIgnoresSpoofedTenant() throws Exception {
        String route="/api/open/v1/queries/"+rows.code()+":execute";
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(route)
                .header("Authorization","Bearer "+tokenA).header("X-Tenant-ID",principalA.tenantId()+1000000)
                .contentType("application/json").content("{\"parameters\":{\"zone\":\"A\"},\"limit\":10}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].childName").value("Child A"));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(route).header("Authorization","Bearer "+tokenA)
                .contentType("application/json").content("{\"parameters\":{\"zone\":\"A\"},\"fromSql\":\"ab_user\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(route).header("Authorization","Bearer "+tokenA)
                .contentType("application/json").content("{\"parameters\":{\"zone\":\"A\",\"tenantId\":1}}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        assertThat(MachineDataAccessContext.isActive()).isFalse();
    }
    @Test void queryDocumentsAndDeclarationsUsePublishedAliasesAndHumanAdministration() {
        var document = documents.document(summary.code());
        assertThat(document).containsEntry("x-schema-version", summary.schemaVersion());
        assertThat(json.valueToTree(document).toString()).contains("count").doesNotContain(summary.queryCode(), root.modelCode(), child.modelCode());
        assertThat(documents.types(summary.code()).getBody()).contains("QueryParameters", "QueryItem", "\"count\"?: number").doesNotContain(summary.queryCode());
        var context = MetaContext.snapshot();
        try {
            MetaContext.setContext(principalA.tenantId(), null, principalA.subjectPid(), "machine");
            assertThatThrownBy(() -> documents.document(summary.code())).isInstanceOf(AccessDeniedException.class);
        } finally { MetaContext.restore(context); }
        publications.withdraw(summary.code(), 1);
        assertThatThrownBy(() -> documents.types(summary.code())).isInstanceOf(IllegalArgumentException.class);
    }

}
