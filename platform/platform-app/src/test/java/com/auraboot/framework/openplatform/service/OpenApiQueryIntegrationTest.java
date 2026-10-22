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
    @Autowired private com.auraboot.framework.tenant.service.TenantService tenantService;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate fixtureJdbc;
    @Autowired private MetaModelService models;
    @Autowired private SchemaManagementService schemas;
    @Autowired private DynamicDataService data;
    @Autowired private NamedQueryService queries;
    @Autowired private OpenApiPublicationService resources;
    @Autowired private OpenApiQueryPublicationService publications;
    @Autowired private OpenApiQueryService publicQueries;
    @Autowired private OpenApiEndpointPublicationService endpointPublications;
    @Autowired private OpenApiEndpointService publicEndpoints;

    @Autowired private OpenApiResourceService publicResources;
    @Autowired private OpenPlatformManagementService management;
    @Autowired private OpenPlatformTokenService tokens;
    @Autowired private IntegrationTokenService integrationTokens;
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
    private String tokenA, tokenB;
    private Set<String> scopes;

    @Test void controllerQueryEndpointKeepsTypedMultiSourceRowsAndExplicitFixedOrPermanentAuthority() throws Exception {
        String code = "endpoint.query." + UniqueIdGenerator.generate().toLowerCase();
        var source = endpointPublications.source("query.execute", rows.code());
        var endpoint = new OpenApiEndpointContract(code, "query.execute", "query.execute", rows.code(), 1,
                source.fingerprint(), Map.of("invoke", code + ".invoke"), Math.max(5000, source.minTimeoutMs()), 2);
        endpointPublications.saveDraft(code, new OpenApiEndpointPublicationService.DraftRequest(0, endpoint)); endpointPublications.publish(code, 1);
        endpointPublications.grant(principalA.installationPid(), code, new OpenApiEndpointPolicy(true));
        var allowed = new HashSet<>(scopes); allowed.add(code + ".invoke");
        management.updateScopes(principalA.installationPid(),new UpdateInstallationScopesRequest(allowed));
        var human = MetaContext.snapshot();
        for (String mode : List.of("fixed", "never")) {
            MetaContext.restore(human);
            var issued = integrationTokens.create(principalA.installationPid(),new com.auraboot.framework.openplatform.dto.IntegrationTokenDtos.Create("Endpoint query",mode,null),"controller-query-"+mode);
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(endpoint.path()).header("Authorization","Bearer "+issued.token())
                    .contentType("application/json").content("{\"parameters\":{\"zone\":\"A\"},\"limit\":10}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].childName").value("Child A"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].secret").doesNotExist());
        }
        MetaContext.restore(human);
        assertThat(endpointPublications.types(code)).contains("EndpointRequest","QueryParameters","EndpointResult", "parameters: QueryParameters").doesNotContain("parameters?: QueryParameters");
        assertThat(json.valueToTree(endpointPublications.document(code)).toString()).contains(endpoint.path()).doesNotContain("/api/meta",rows.queryCode());
    }

    @Test void fixedAndPermanentIntegrationTokensUseRealResourceAndQueryAuthority() throws Exception {
        var human = MetaContext.snapshot();
        for (String mode : List.of("fixed", "never")) {
            MetaContext.restore(human);
            var issued = integrationTokens.create(principalA.installationPid(),
                    new com.auraboot.framework.openplatform.dto.IntegrationTokenDtos.Create("Query " + mode, mode, null),
                    "query-integration-" + mode);
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/open/v1/resources/" + root.code())
                    .header("Authorization", "Bearer " + issued.token()))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items.length()").value(1))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].name").value("Root A"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].secret").doesNotExist());
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/open/v1/queries/" + rows.code() + ":execute")
                    .header("Authorization", "Bearer " + issued.token()).contentType("application/json")
                    .content("{\"parameters\":{\"zone\":\"A\"},\"limit\":10}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].childName").value("Child A"));
        }
        MetaContext.restore(human);
    }

    @Test void integrationResourceAuthorityRechecksLiveScopeRotationAndRevocationForStalePrincipal() {
        var issued = integrationTokens.create(principalA.installationPid(),
                new com.auraboot.framework.openplatform.dto.IntegrationTokenDtos.Create("Live authority", "never", null), "live-authority-create");
        var identity = auth.findToken(secrets.sha256(issued.token()), OpenPlatformTokenService.AUDIENCE, Instant.now());
        var principal = new MachinePrincipal(identity.tenantId(), identity.tokenPid(), "machine", scopes,
                identity.applicationPid(), identity.installationPid(), identity.environment(), identity.tokenPid());
        assertThat(run(principal, rows, "A").get("items")).asList().hasSize(1);
        String queryScope = rows.scopes().get("execute");
        var narrowed = new HashSet<>(scopes); narrowed.remove(queryScope);
        management.updateScopes(principal.installationPid(), new com.auraboot.framework.openplatform.dto.OpenPlatformDtos.UpdateInstallationScopesRequest(narrowed));
        assertThatThrownBy(() -> run(principal, rows, "A")).isInstanceOf(AccessDeniedException.class);
        management.updateScopes(principal.installationPid(), new com.auraboot.framework.openplatform.dto.OpenPlatformDtos.UpdateInstallationScopesRequest(scopes));
        assertThat(run(principal, rows, "A").get("items")).asList().hasSize(1);
        var rotated = integrationTokens.rotate(principal.installationPid(), issued.metadata().pid(),
                new com.auraboot.framework.openplatform.dto.IntegrationTokenDtos.Rotate("never", null, 0), "live-authority-rotate");
        assertThatThrownBy(() -> run(principal, rows, "A")).isInstanceOf(AccessDeniedException.class);
        var replacement = auth.findToken(secrets.sha256(rotated.credential().token()), OpenPlatformTokenService.AUDIENCE, Instant.now());
        var next = new MachinePrincipal(replacement.tenantId(), replacement.tokenPid(), "machine", scopes,
                replacement.applicationPid(), replacement.installationPid(), replacement.environment(), replacement.tokenPid());
        assertThat(run(next, rows, "A").get("items")).asList().hasSize(1);
        integrationTokens.revoke(principal.installationPid(), rotated.credential().metadata().pid());
        assertThatThrownBy(() -> run(next, rows, "A")).isInstanceOf(AccessDeniedException.class);
    }

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
        return createQuery(code, sql, fields, baseWhere, root.modelCode());
    }
    private NamedQueryDTO createQuery(String code,String sql,List<NamedQueryFieldRequest> fields,com.fasterxml.jackson.databind.JsonNode baseWhere,String resourceModel) {
        var request=new NamedQueryCreateRequest();request.setCode(code);request.setTitle("Public query fixture");request.setFromSql(sql);
        request.setBaseWhere(baseWhere);
        request.setResourceCode(resourceModel);request.setActionCode("read");request.setFields(fields);
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
        if("A".equals(zone))tokenA=token.accessToken(); else tokenB=token.accessToken();
        var identity=auth.findToken(secrets.sha256(token.accessToken()),OpenPlatformTokenService.AUDIENCE,Instant.now());
        return new MachinePrincipal(identity.tenantId(),identity.tokenPid(),"machine",scopes,identity.applicationPid(),identity.installationPid(),identity.environment(),identity.tokenPid());
    }
    private void activateTenantAdministrator(Long tenantId) {
        var members = applicationContext.getBean(com.auraboot.framework.tenant.service.TenantMemberService.class);
        var roles = applicationContext.getBean(com.auraboot.framework.rbac.service.RoleService.class);
        var userRoles = applicationContext.getBean(com.auraboot.framework.rbac.service.UserRoleService.class);
        var permissions = applicationContext.getBean(com.auraboot.framework.permission.mapper.PermissionMapper.class);
        var rolePermissions = applicationContext.getBean(com.auraboot.framework.rbac.mapper.RolePermissionMapper.class);
        var permissionService = applicationContext.getBean(com.auraboot.framework.permission.service.UserPermissionService.class);
        var member = members.addMember(testUser.getId(), tenantId, "active");
        MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(), testUser.getUserName());
        MetaContext.setMemberId(member.getId());
        var role = new com.auraboot.framework.rbac.entity.Role();
        org.springframework.beans.BeanUtils.copyProperties(testRole, role);
        role.setId(null); role.setPid(UniqueIdGenerator.generate()); role.setTenantId(tenantId);
        role.setCode("virtual_admin_" + role.getPid().toLowerCase()); role.setName("Virtual tenant administrator");
        role = roles.createRole(role);
        assertThat(userRoles.assignRolesToMember(member.getId(), List.of(role.getId()), tenantId, null)).isTrue();
        var permission = new com.auraboot.framework.permission.entity.Permission();
        permission.setPid(UniqueIdGenerator.generate()); permission.setTenantId(tenantId);
        permission.setCode("sys.connector.update"); permission.setName("Manage Open Platform");
        permission.setResourceType("system"); permission.setResourceCode("connector"); permission.setAction("update");
        permission.setSource("test"); permission.setStatus("active"); permission.setDeletedFlag(false);
        permission.setCreatedAt(Instant.now()); permission.setUpdatedAt(Instant.now());
        permissions.insert(permission);
        var binding = new com.auraboot.framework.rbac.entity.RolePermission();
        binding.setPid(UniqueIdGenerator.generate()); binding.setTenantId(tenantId);
        binding.setRoleId(role.getId()); binding.setPermissionId(permission.getId());
        binding.setGrantType("grant"); binding.setStatus("active"); binding.setDeletedFlag(false);
        binding.setCreatedAt(Instant.now()); binding.setUpdatedAt(Instant.now()); rolePermissions.insert(binding);
        permissionService.evictPermissionDefinitions(tenantId);
        permissionService.evictUserPermissions(tenantId, testUser.getId());
        assertThat(permissionService.hasPermission(testUser.getId(), "sys.connector.update")).isTrue();
    }

    private Map<String,Object> run(MachinePrincipal principal,OpenApiQueryContract contract,String zone) {
        var previous=MetaContext.snapshot();
        MetaContext.setContext(principal.tenantId(),null,principal.subjectPid(),"machine");
        try(var machine=MachineDataAccessContext.begin(principal)) {return publicQueries.execute(principal,contract.code(),new OpenApiQueryService.ExecuteRequest(Map.of("zone",zone),10,null));}
        finally {if(previous==null)MetaContext.clear();else MetaContext.restore(previous);}
    }
    @org.junit.jupiter.params.ParameterizedTest(name = "new NamedQuery publication via {0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"json", "directory", "zip", "source"})
    void pluginQueryPublicationUsesRealSourcePinsGrantsAndWithdrawal(String entry,
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        String suffix = UniqueIdGenerator.generate().toLowerCase();
        var pluginManifest = com.auraboot.framework.plugin.dto.PluginManifest.builder().pluginId("com.example.query-" + suffix)
                .namespace("query_plugin_" + suffix).version("1.0.0").displayName("Public query plugin").build();
        var plugin = com.auraboot.framework.plugin.entity.PluginRecord.builder().pid(UniqueIdGenerator.generate())
                .tenantId(testTenant.getId()).pluginId(pluginManifest.getPluginId()).namespace(pluginManifest.getNamespace())
                .version(pluginManifest.getVersion()).displayName(pluginManifest.getDisplayName()).manifest(pluginManifest)
                .status("installed").deletedFlag(false).build();
        assertThat(plugins.insert(plugin)).isEqualTo(1);
        String importedQueryCode = "plugin_query_" + suffix;
        var expectedSource = json.readValue(json.writeValueAsBytes(rowsDefinition), NamedQueryDTO.class);
        expectedSource.setCode(importedQueryCode);
        expectedSource.setCurrentVersion(0);
        var declaredFields = rowsDefinition.getFields().stream().map(field -> {
            var declared = new NamedQueryFieldRequest();
            declared.setFieldCode(field.getFieldCode()); declared.setColumnExpr(field.getColumnExpr());
            declared.setDataType(field.getDataType()); declared.setOperators(field.getOperators() == null ? null : Arrays.asList(field.getOperators()));
            declared.setSortable(field.getSortable()); declared.setSearchable(field.getSearchable());
            declared.setSortOrder(field.getSortOrder());
            return declared;
        }).toList();
        var declaredQuery = com.auraboot.framework.plugin.dto.imports.NamedQueryDefinitionDTO.builder()
                .code(importedQueryCode).title("New plugin query source").resourceCode(rowsDefinition.getResourceCode())
                .actionCode(rowsDefinition.getActionCode()).fromSql(rowsDefinition.getFromSql())
                .baseWhere(rowsDefinition.getBaseWhere()).defaultOrder(rowsDefinition.getDefaultOrder())
                .status("published").fields(declaredFields).policy(rowsDefinition.getPolicy()).build();
        assertThatThrownBy(() -> queries.describeExecution(importedQueryCode))
                .as("the plugin query source must not exist before this import")
                .isInstanceOf(com.auraboot.framework.meta.exception.MetaServiceException.class).hasMessageContaining("not found");
        var contract = new OpenApiQueryContract("plugin." + suffix, importedQueryCode, 1,
                com.auraboot.framework.meta.util.NamedQueryDefinitionFingerprint.of(expectedSource),
                rows.scopes(), rows.sourceResources(), rows.parameters(), rows.fields(), rows.maxPageSize(),
                rows.maxRows(), rows.timeoutMs(), rows.maxConcurrent());
        var manifest = new com.auraboot.framework.plugin.dto.imports.PluginManifestExtended();
        manifest.setPluginId(plugin.getPluginId()); manifest.setNamespace(plugin.getNamespace()); manifest.setVersion("1.0.0");
        manifest.setNamedQueries(List.of(declaredQuery));
        manifest.setPublicQueries(List.of(contract));
        var request = new com.auraboot.framework.plugin.dto.imports.ImportRequest();
        var preview = parsePublicQueryFixture(manifest, entry, dir);
        assertThat(preview.isValid()).as("query import preview: %s", preview.getErrors()).isTrue();
        var imported = pluginImports.execute(preview.getImportId(), request);
        assertThat(imported.isSuccess()).as("query import failed: %s", imported.getErrorMessage()).isTrue();
        assertThat(imported.getResourceCounts().get("NAMED_QUERY")).containsEntry("CREATE", 1);
        assertThat(imported.getResourceCounts().get("PUBLIC_QUERY")).containsEntry("CREATE", 1);
        var actualSource = queries.describeExecution(importedQueryCode);
        assertThat(actualSource.definition().getStatus()).isEqualTo("published");
        assertThat(actualSource.definition().getPid()).isNotEqualTo(rowsDefinition.getPid());
        assertThat(actualSource.fingerprint()).isEqualTo(contract.sourceFingerprint());
        var sourceOwner = pluginResources.findByTypeAndCode(testTenant.getId(),
                com.auraboot.framework.plugin.dto.imports.ResourceType.NAMED_QUERY.code(), importedQueryCode);
        assertThat(sourceOwner.getPluginPid()).isEqualTo(plugin.getPid());
        assertThat(sourceOwner.getImportId()).isEqualTo(preview.getImportId());
        assertThat(sourceOwner.getResourcePid()).isEqualTo(actualSource.definition().getPid());
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
        var originalOwnership = owned.getOwnershipType();
        var originalModified = owned.getUserModified();
        var staleOwner = json.readValue(json.writeValueAsBytes(owned),
                com.auraboot.framework.plugin.entity.PluginResource.class);
        staleOwner.setImportId(UniqueIdGenerator.generate());
        assertThatThrownBy(() -> pluginPublications.restore(staleOwner))
                .isInstanceOf(com.auraboot.framework.plugin.exception.PluginException.class)
                .hasMessageContaining("owner changed");
        var foreignTenant = json.readValue(json.writeValueAsBytes(owned),
                com.auraboot.framework.plugin.entity.PluginResource.class);
        foreignTenant.setTenantId(testTenant.getId() + 1000000);
        assertThatThrownBy(() -> pluginPublications.restore(foreignTenant))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(publications.active(testTenant.getId(), contract.code())).contains(contract);
        pluginResourceService.claimByUser(testTenant.getId(),
                com.auraboot.framework.plugin.dto.imports.ResourceType.PUBLIC_QUERY, contract.code());
        assertThatThrownBy(() -> pluginImports.rollback(preview.getImportId()))
                .as("plugin rollback must not withdraw a user-claimed public query")
                .isInstanceOf(com.auraboot.framework.plugin.exception.PluginException.class)
                .hasMessageContaining("claimed");
        assertThat(publications.active(testTenant.getId(), contract.code())).contains(contract);
        assertThat(queries.describeExecution(importedQueryCode).definition().getPid())
                .isEqualTo(actualSource.definition().getPid());
        var claimed = pluginResources.findByTypeAndCode(testTenant.getId(),
                com.auraboot.framework.plugin.dto.imports.ResourceType.PUBLIC_QUERY.code(), contract.code());
        assertThat(claimed.getOwnershipTypeEnum())
                .isEqualTo(com.auraboot.framework.plugin.dto.imports.OwnershipType.USER_CLAIMED);
        // Restore this fixture's ownership before exercising the existing explicit withdrawal journey.
        claimed.setOwnershipType(originalOwnership);
        claimed.setUserModified(originalModified);
        assertThat(pluginResources.updateById(claimed)).isEqualTo(1);
        pluginResourceService.deleteResource(claimed);
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

    private com.auraboot.framework.plugin.dto.imports.ImportPreviewResult parsePublicQueryFixture(
            com.auraboot.framework.plugin.dto.imports.PluginManifestExtended manifest, String entry,
            java.nio.file.Path dir) throws Exception {
        if ("json".equals(entry)) return pluginImports.parseJson(json.writeValueAsString(manifest), "public-query-plugin.json");
        var fileManifest = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(manifest);
        var contracts = fileManifest.remove("publicQueries");
        fileManifest.putObject("resourceDirs").put("publicQueries", "config/public-queries.json");
        java.nio.file.Files.createDirectories(dir.resolve("config"));
        java.nio.file.Files.writeString(dir.resolve("plugin.json"), json.writeValueAsString(fileManifest));
        java.nio.file.Files.writeString(dir.resolve("config/public-queries.json"), json.writeValueAsString(contracts));
        return switch (entry) {
            case "directory" -> pluginImports.parseDirectory(dir.toString());
            case "source" -> pluginImports.parseSource(new com.auraboot.framework.plugin.source.FileSystemPluginSource(dir));
            case "zip" -> {
                var bytes = new java.io.ByteArrayOutputStream();
                try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
                    for (String file : List.of("plugin.json", "config/public-queries.json")) {
                        zip.putNextEntry(new java.util.zip.ZipEntry(file));
                        zip.write(java.nio.file.Files.readAllBytes(dir.resolve(file))); zip.closeEntry();
                    }
                }
                yield pluginImports.parse(new java.io.ByteArrayInputStream(bytes.toByteArray()), "public-query-plugin.zip");
            }
            default -> throw new IllegalArgumentException("Unknown fixture import entry: " + entry);
        };
    }

    @Test void namedQueryVirtualResourcePinsDelegateSemantics() throws Exception {
        String suffix = UniqueIdGenerator.generate().toLowerCase();
        var source = createQuery("virtual_source_" + suffix, "mt_" + root.modelCode() + " a",
                List.of(nqField("pid", "a.pid", "string"), nqField("name", "a.name", "string"), nqField("zone", "a.zone", "string")));
        String virtualModel = "virtual_public_" + suffix;
        models.saveDefinition(ModelDefinition.builder().code(virtualModel).displayName("Granted virtual public source")
                .sourceType("namedQuery").sourceRef(source.getCode()).primaryKey("pid").status("published")
                .capabilities(ModelCapabilities.virtualReadOnly()).fields(List.of(
                        FieldDefinition.builder().code("name").dataType("STRING").displayName("Name").build(),
                        FieldDefinition.builder().code("zone").dataType("STRING").displayName("Zone").filterable(true).build())).build());
        var contract = new OpenApiResourceContract("virtual." + suffix, virtualModel, 1,
                Set.of(Operation.LIST, Operation.GET), Map.of(Operation.LIST, root.scopes().get(Operation.LIST),
                        Operation.GET, root.scopes().get(Operation.GET)), DataPolicy.INSTALLATION_FILTER,
                root.fields(), 50, 8, 5000, Set.of(), Map.of(root.modelCode(), root.code()));
        resources.saveDraft(contract.code(), new OpenApiPublicationService.DraftRequest(0, contract));
        resources.publish(contract.code(), 1);
        resources.grant(principalA.installationPid(), contract.code(), new OpenApiInstallationPolicy(contract.operations(),
                contract.fields().keySet(), Set.of(), List.of(new OpenApiInstallationPolicy.RowConstraint("zone", List.of("A")))));
        var model = models.getModelDefinition(virtualModel).orElseThrow();
        resources.requireSourceBinding(principalA.tenantId(), contract, model);
        var presentation = new NamedQueryUpdateRequest();
        presentation.setTitle("Renamed delegated query");
        queries.update(source.getPid(), presentation);
        resources.requireSourceBinding(principalA.tenantId(), contract, model);
        String binding = publicationMapper.sourceBinding(principalA.tenantId(), contract.code(), 1);
        assertThat(json.readTree(binding).path("queryFingerprint").asText())
                .isEqualTo(queries.describeExecution(source.getCode()).fingerprint());
        var next = new OpenApiResourceContract(contract.code(), virtualModel, 2, contract.operations(), contract.scopes(),
                contract.dataPolicy(), contract.fields(), 50, 8, 5000, Set.of(), contract.sourceResources());
        resources.saveDraft(contract.code(), new OpenApiPublicationService.DraftRequest(1, next));
        var update = new NamedQueryUpdateRequest();
        update.setActionCode("update");
        queries.update(source.getPid(), update);
        assertThatThrownBy(() -> resources.requireSourceBinding(principalA.tenantId(), contract, model))
                .isInstanceOf(OpenApiPublicationService.SourceBindingConflictException.class);
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/open/v1/resources/" + contract.code())
                .header("Authorization", "Bearer " + tokenA))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/open/v1/resources/" + contract.code() + "/fixture_pid")
                .header("Authorization", "Bearer " + tokenA))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        applyTestMetaContext();
        assertThatThrownBy(() -> resources.publish(contract.code(), 2))
                .isInstanceOf(OpenApiPublicationService.SourceBindingConflictException.class);
        assertThat(publicationMapper.snapshot(principalA.tenantId(), contract.code(), 2)).isNull();
        assertThat(publicationMapper.sourceBinding(principalA.tenantId(), contract.code(), 1)).isEqualTo(binding);
        assertThat(resources.active(principalA.tenantId(), contract.code())).contains(contract);
        queries.delete(source.getPid());
        assertThatThrownBy(() -> resources.requireSourceBinding(principalA.tenantId(), contract, model))
                .isInstanceOf(OpenApiPublicationService.SourceBindingConflictException.class)
                .hasCauseInstanceOf(com.auraboot.framework.meta.exception.MetaServiceException.class);
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/open/v1/resources/" + contract.code())
                .header("Authorization", "Bearer " + tokenA))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
    }

    @Test void namedQueryVirtualResourceReadsWithExplicitPhysicalSourceGrants() throws Exception {
        String suffix = UniqueIdGenerator.generate().toLowerCase();
        var source = createQuery("virtual_read_" + suffix, "mt_" + root.modelCode() + " a", List.of(
                nqField("pid", "a.pid", "string"), nqField("name", "a.name", "string"),
                nqField("zone", "a.zone", "string"), nqField("privateValue", "a.secret", "string")));
        String extra = humanCreate(root, Map.of("name", "Extra A", "zone", "A", "secret", "Private extra"));
        var foreignTenant = new com.auraboot.framework.tenant.dao.entity.Tenant();
        foreignTenant.setPid(UniqueIdGenerator.generate()); foreignTenant.setName("virtual-tenant-" + suffix);
        foreignTenant.setDisplayName("Virtual isolation tenant"); foreignTenant.setStatus("active");
        foreignTenant.setContactEmail("virtual@example.test"); foreignTenant.setDeletedFlag(false);
        foreignTenant.setCreatedAt(Instant.now()); foreignTenant.setUpdatedAt(Instant.now());
        foreignTenant = tenantService.createTenant(foreignTenant);
        String foreignPid = UniqueIdGenerator.generate();
        String mutation = System.getenv("AURA_IT_NEGATIVE_MUTATION");
        assertThat(mutation).isIn(null, "foreign-row-as-own", "foreign-token-as-own");
        Long seededTenant = "foreign-row-as-own".equals(mutation) ? testTenant.getId() : foreignTenant.getId();
        // Same physical source and allowed business values: only tenant isolation may exclude this row.
        fixtureJdbc.update("INSERT INTO mt_" + root.modelCode() + " (pid, tenant_id, name, zone, secret) VALUES (?, ?, ?, ?, ?)",
                foreignPid, seededTenant, "Root A", "A", "Foreign private value");

        String modelCode = "virtual_read_" + suffix;
        models.saveDefinition(ModelDefinition.builder().code(modelCode).displayName("Granted virtual public source")
                .sourceType("namedQuery").sourceRef(source.getCode()).primaryKey("pid").status("published")
                .capabilities(ModelCapabilities.virtualReadOnly()).fields(List.of(
                        FieldDefinition.builder().code("name").dataType("STRING").displayName("Name").sortable(true).filterable(true).build(),
                        FieldDefinition.builder().code("zone").dataType("STRING").displayName("Zone").filterable(true).build())).build());
        var fields = new LinkedHashMap<>(root.fields());
        fields.put("name", new Field("name", Type.STRING, true, false, false, true, Set.of("EQ"), true, null, Map.of(), null));
        var contract = new OpenApiResourceContract("virtual-read." + suffix, modelCode, 1,
                root.operations(), root.scopes(), DataPolicy.INSTALLATION_FILTER, fields, 50, 8, 5000,
                Set.of(), Map.of(root.modelCode(), root.code()));
        resources.saveDraft(contract.code(), new OpenApiPublicationService.DraftRequest(0, contract));
        resources.publish(contract.code(), 1);
        var metadata = resources.sourceForAdministration(modelCode);
        assertThat(metadata.sources()).extracting(OpenApiQueryValidator.SourceModel::modelCode).containsExactly(root.modelCode());
        assertThat(metadata.sources().getFirst().resources()).extracting(OpenApiQueryValidator.SourceResource::code).contains(root.code());
        assertThat(metadata.fields()).allMatch(field -> !field.writable());
        assertThat(json.writeValueAsString(metadata)).doesNotContain("fromSql", "a.secret", "privateValue");
        resources.grant(principalA.installationPid(), contract.code(), new OpenApiInstallationPolicy(contract.operations(),
                contract.fields().keySet(), Set.of(), List.of(new OpenApiInstallationPolicy.RowConstraint("name", List.of("Root A", "Extra A", "Root B")))));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/open/v1/resources/" + contract.code())
                .header("Authorization", "Bearer " + tokenA))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[*].name")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("Root A", "Extra A")));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/open/v1/resources/" + contract.code())
                .header("Authorization", "Bearer " + tokenA).header("X-Tenant-ID", foreignTenant.getId()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[*].name")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("Root A", "Extra A")));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/open/v1/resources/" + contract.code() + "/" + foreignPid)
                .header("Authorization", "Bearer " + tokenA).header("X-Tenant-ID", foreignTenant.getId()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        applyTestMetaContext();
        resources.grant(principalB.installationPid(), contract.code(), new OpenApiInstallationPolicy(contract.operations(),
                contract.fields().keySet(), Set.of(), List.of(new OpenApiInstallationPolicy.RowConstraint("zone", List.of("B")))));
        var secondInstallation = json.readTree(http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/open/v1/resources/" + contract.code()).header("Authorization", "Bearer " + tokenB))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[*].name")
                        .value(org.hamcrest.Matchers.contains("Root B"))).andReturn().getResponse().getContentAsString());
        String secondPid = secondInstallation.path("items").get(0).path("pid").asText();
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/open/v1/resources/" + contract.code() + "/" + secondPid)
                .header("Authorization", "Bearer " + tokenA))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        applyTestMetaContext();
        resources.grant(principalA.installationPid(), contract.code(), new OpenApiInstallationPolicy(contract.operations(),
                contract.fields().keySet(), Set.of(), List.of(new OpenApiInstallationPolicy.RowConstraint("name", List.of("Root A", "Root A Renamed")))));
        var initial = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/open/v1/resources/" + contract.code())
                .header("Authorization", "Bearer " + tokenA))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items.length()").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].name").value("Root A"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].privateValue").doesNotExist())
                .andReturn().getResponse();
        String pid = json.readTree(initial.getContentAsString()).path("items").get(0).path("pid").asText();
        String path = "/api/open/v1/resources/" + contract.code() + "/" + pid;
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + tokenB))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/open/v1/resources/" + contract.code() + "/" + secondPid)
                .header("Authorization", "Bearer " + tokenB))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.name").value("Root B"));

        applyTestMetaContext();
        var physicalDefinition = models.getModelDefinition(root.modelCode()).orElseThrow();
        var virtualDefinition = models.getModelDefinition(modelCode).orElseThrow();
        String foreignToken;
        try {
            activateTenantAdministrator(foreignTenant.getId());
            // Both tenants publish the same public/model/query codes against the same physical table.
            models.saveDefinition(physicalDefinition);
            resources.saveDraft(root.code(), new OpenApiPublicationService.DraftRequest(0, root));
            resources.publish(root.code(), 1);
            createQuery(source.getCode(), "mt_" + root.modelCode() + " a", List.of(
                    nqField("pid", "a.pid", "string"), nqField("name", "a.name", "string"),
                    nqField("zone", "a.zone", "string"), nqField("privateValue", "a.secret", "string")));
            models.saveDefinition(virtualDefinition);
            resources.saveDraft(contract.code(), new OpenApiPublicationService.DraftRequest(0, contract));
            resources.publish(contract.code(), 1);
            var foreignApplication = management.createApplication(new CreateApplicationRequest("Foreign virtual consumer", "Tenant token boundary"));
            var foreignInstallation = management.install(foreignApplication.pid(), new InstallApplicationRequest("staging",
                    Set.of(root.scopes().get(Operation.LIST)), 600));
            var foreignPolicy = new OpenApiInstallationPolicy(root.operations(), root.fields().keySet(), Set.of(),
                    List.of(new OpenApiInstallationPolicy.RowConstraint("zone", List.of("A"))));
            resources.grant(foreignInstallation.pid(), root.code(), foreignPolicy);
            resources.grant(foreignInstallation.pid(), contract.code(), foreignPolicy);
            var credential = management.createCredential(foreignInstallation.pid());
            foreignToken = tokens.issue("client_credentials", credential.clientId(), credential.clientSecret(), null).accessToken();
            var identity = auth.findToken(secrets.sha256(foreignToken), OpenPlatformTokenService.AUDIENCE, Instant.now());
            assertThat(identity.tenantId()).isEqualTo(foreignTenant.getId()).isNotEqualTo(principalA.tenantId());
            assertThat(identity.installationPid()).isNotIn(principalA.installationPid(), principalB.installationPid());
        } finally {
            applyTestMetaContext();
        }
        String foreignReadToken = "foreign-token-as-own".equals(mutation) ? tokenA : foreignToken;
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/open/v1/resources/" + contract.code())
                .header("Authorization", "Bearer " + foreignReadToken).header("X-Tenant-ID", testTenant.getId()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items.length()").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].pid").value(foreignPid))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].name").value("Root A"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].privateValue").doesNotExist());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/open/v1/resources/" + contract.code() + "/" + foreignPid)
                .header("Authorization", "Bearer " + foreignToken).header("X-Tenant-ID", testTenant.getId()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.pid").value(foreignPid))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.privateValue").doesNotExist());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + foreignToken))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/open/v1/resources/" + contract.code() + "/" + foreignPid)
                .header("Authorization", "Bearer " + tokenA))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());

        var first = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + tokenA))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.name").value("Root A")).andReturn().getResponse();
        assertThat(first.getHeader("ETag")).matches("\"[a-f0-9]{64}\"");
        assertThat(http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + tokenA))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getHeader("ETag"))
                .isEqualTo(first.getHeader("ETag"));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/open/v1/resources/" + contract.code() + "/" + extra)
                .header("Authorization", "Bearer " + tokenA)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        applyTestMetaContext();
        MetaContext.runWithCommandPermitScope("ALL", () -> data.update(root.modelCode(), pid, new HashMap<>(Map.of("name", "Root A Renamed"))));
        var changed = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + tokenA))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.name").value("Root A Renamed")).andReturn().getResponse();
        assertThat(changed.getHeader("ETag")).isNotEqualTo(first.getHeader("ETag"));
        applyTestMetaContext();
        String nullName = humanCreate(root, Map.of("zone", "A", "secret", "Private null"));
        resources.grant(principalA.installationPid(), contract.code(), new OpenApiInstallationPolicy(contract.operations(),
                Set.of("pid", "name"), Set.of(), List.of(new OpenApiInstallationPolicy.RowConstraint("zone", List.of("A")))));
        String originalCursor = null;
        for (String order : List.of("PID", "ASC", "DESC")) {
            String cursor = null;
            var names = new java.util.ArrayList<String>(); var pids = new java.util.ArrayList<String>();
            do {
                var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/open/v1/resources/" + contract.code())
                        .header("Authorization", "Bearer " + tokenA).param("limit", "1");
                if (!"PID".equals(order)) request.param("sort", "name").param("direction", order);
                if (cursor != null) request.param("cursor", cursor);
                var page = json.readTree(http.perform(request).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                        .andReturn().getResponse().getContentAsString());
                assertThat(page.path("items").size()).isEqualTo(1);
                var row = page.path("items").get(0); pids.add(row.path("pid").asText());
                names.add(row.path("name").isNull() || row.path("name").isMissingNode() ? "<null>" : row.path("name").asText());
                assertThat(row.has("zone") || row.has("privateValue")).isFalse();
                cursor = page.path("hasMore").asBoolean() ? page.path("nextCursor").asText() : null;
                if (originalCursor == null && cursor != null) originalCursor = cursor;
                assertThat(pids.size()).isLessThanOrEqualTo(3);
            } while (cursor != null);
            assertThat(pids).containsExactlyInAnyOrder(pid, extra, nullName);
            if ("ASC".equals(order)) assertThat(names).containsExactly("Extra A", "Root A Renamed", "<null>");
            if ("DESC".equals(order)) assertThat(names).containsExactly("<null>", "Root A Renamed", "Extra A");
        }
        assertThat(originalCursor).isNotBlank();
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/open/v1/resources/" + contract.code())
                .header("Authorization", "Bearer " + foreignToken).param("limit", "1").param("cursor", originalCursor))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/open/v1/resources/" + contract.code())
                .header("Authorization", "Bearer " + tokenB).param("limit", "1").param("cursor", originalCursor))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        applyTestMetaContext();
        resources.grant(principalA.installationPid(), root.code(), new OpenApiInstallationPolicy(root.operations(), Set.of("pid", "zone"), Set.of(),
                List.of(new OpenApiInstallationPolicy.RowConstraint("zone", List.of("A")))));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/open/v1/resources/" + contract.code())
                .header("Authorization", "Bearer " + tokenA)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/open/v1/resources/" + contract.code())
                .header("Authorization", "Bearer " + tokenA).param("limit", "1").param("cursor", originalCursor))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        applyTestMetaContext(); resources.revoke(principalA.installationPid(), root.code());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + tokenA))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        applyTestMetaContext();
        resources.grant(principalA.installationPid(), root.code(), new OpenApiInstallationPolicy(root.operations(), root.fields().keySet(), Set.of(),
                List.of(new OpenApiInstallationPolicy.RowConstraint("zone", List.of("A")))));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + tokenA))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        verifyAmbiguousVirtualGetAndSourceBudgetBoundary(pid, path);
        verifyNullVirtualIdentityBeforePagination(pid, extra, path);
        applyTestMetaContext(); resources.withdraw(root.code(), 1);
        assertThat(resources.active(testTenant.getId(), contract.code())).contains(contract);
        for (String unavailable : List.of("/api/open/v1/resources/" + contract.code(), path)) {
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(unavailable).header("Authorization", "Bearer " + tokenA))
                    .andExpect(result -> assertThat(result.getResolvedException()).isInstanceOf(AccessDeniedException.class))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("403"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(
                            org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Query source resource is not published"))));
        }
    }

    @Test void namedQueryVirtualResourceKeepsTypedDateBoundariesAndFilters() throws Exception {
        String suffix = UniqueIdGenerator.generate().toLowerCase();
        String sourceModel = "date_source_" + suffix;
        var modelFields = new ArrayList<>(List.of(FieldDefinition.builder().code("name").columnName("name").dataType("STRING").build(),
                FieldDefinition.builder().code("day").columnName("day").dataType("DATE").filterable(true).sortable(true).build(),
                FieldDefinition.builder().code("zone").columnName("zone").dataType("STRING").filterable(true).build()));
        for (var entry : Map.of("units", "INTEGER", "amount", "DECIMAL", "enabled", "BOOLEAN", "moment", "DATETIME").entrySet()) {
            modelFields.add(FieldDefinition.builder().code(entry.getKey()).columnName(entry.getKey())
                    .dataType(entry.getValue()).filterable(true).build());
        }
        models.saveDefinition(ModelDefinition.builder().code(sourceModel).tableName("mt_" + sourceModel).displayName("Date source")
                .sourceType("physical").primaryKey("id").status("published").capabilities(ModelCapabilities.fullPhysical()).fields(modelFields).build());
        assertThat(schemas.createTableByModel(sourceModel).isSuccess()).isTrue();
        var fields = new LinkedHashMap<String, Field>(); fields.put("pid", output("pid", Type.PID)); fields.put("name", output("name", Type.STRING));
        fields.put("day", new Field("day", Type.DATE, true, false, false, false, Set.of("EQ", "IN", "GE", "LE"), true, null, Map.of(), null));
        fields.put("zone", new Field("zone", Type.STRING, true, false, false, false, Set.of("EQ"), false, null, Map.of(), null));
        for (var entry : Map.of("units", Type.INTEGER, "amount", Type.DECIMAL, "enabled", Type.BOOLEAN, "moment", Type.DATETIME).entrySet()) {
            fields.put(entry.getKey(), new Field(entry.getKey(), entry.getValue(), true, false, false, false,
                    Set.of("EQ"), false, null, Map.of(), null));
        }
        var physical = new OpenApiResourceContract("date-source." + suffix, sourceModel, 1, root.operations(), root.scopes(),
                DataPolicy.INSTALLATION_FILTER, fields, 50, 8, 5000, Set.of());
        resources.saveDraft(physical.code(), new OpenApiPublicationService.DraftRequest(0, physical)); resources.publish(physical.code(), 1);
        resources.grant(principalA.installationPid(), physical.code(), new OpenApiInstallationPolicy(physical.operations(), fields.keySet(), Set.of(),
                List.of(new OpenApiInstallationPolicy.RowConstraint("zone", List.of("A")))));
        humanCreate(physical, Map.of("name", "Date A1", "day", java.time.LocalDate.of(2026, 10, 11), "zone", "A",
                "units", 7, "amount", new java.math.BigDecimal("12.50"), "enabled", true, "moment", Instant.parse("2026-10-11T12:34:56Z")));
        humanCreate(physical, Map.of("name", "Date A2", "day", java.time.LocalDate.of(2026, 10, 12), "zone", "A",
                "units", 8, "amount", new java.math.BigDecimal("13.25"), "enabled", false, "moment", Instant.parse("2026-10-12T12:34:56Z")));
        humanCreate(physical, Map.of("name", "Date B", "day", java.time.LocalDate.of(2026, 10, 11), "zone", "B",
                "units", 7, "amount", new java.math.BigDecimal("12.50"), "enabled", true, "moment", Instant.parse("2026-10-11T12:34:56Z")));
        var source = createQuery("date_query_" + suffix, "mt_" + sourceModel + " a", List.of(nqField("pid", "a.pid", "string"),
                nqField("name", "a.name", "string"), nqField("day", "a.day", "date"), nqField("zone", "a.zone", "string"),
                nqField("units", "a.units", "number"), nqField("amount", "a.amount", "number"),
                nqField("enabled", "a.enabled", "boolean"), nqField("moment", "a.moment", "datetime")), null, sourceModel);
        String virtualModel = "date_virtual_" + suffix;
        models.saveDefinition(ModelDefinition.builder().code(virtualModel).displayName("Date virtual resource").sourceType("namedQuery")
                .sourceRef(source.getCode()).primaryKey("pid").status("published").capabilities(ModelCapabilities.virtualReadOnly()).fields(modelFields).build());
        var contract = new OpenApiResourceContract("date-virtual." + suffix, virtualModel, 1, root.operations(), root.scopes(),
                DataPolicy.INSTALLATION_FILTER, fields, 50, 8, 5000, Set.of(), Map.of(sourceModel, physical.code()));
        resources.saveDraft(contract.code(), new OpenApiPublicationService.DraftRequest(0, contract)); resources.publish(contract.code(), 1);
        resources.grant(principalA.installationPid(), contract.code(), new OpenApiInstallationPolicy(contract.operations(), fields.keySet(), Set.of(),
                List.of(new OpenApiInstallationPolicy.RowConstraint("day", List.of("2026-10-11")))));
        String path = "/api/open/v1/resources/" + contract.code();
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + tokenA))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items.length()").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].day").value("2026-10-11"));
        applyTestMetaContext();
        resources.grant(principalA.installationPid(), contract.code(), new OpenApiInstallationPolicy(contract.operations(), fields.keySet(), Set.of(),
                List.of(new OpenApiInstallationPolicy.RowConstraint("day", List.of("2026-10-11", "2026-10-12")))));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + tokenA)
                .param("filters", "[{\"field\":\"day\",\"operator\":\"EQ\",\"value\":\"2026-10-11\"}]"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[*].name").value(org.hamcrest.Matchers.contains("Date A1")));
        for (String operator : List.of("IN", "GE", "LE")) {
            String value = "IN".equals(operator) ? "[\"2026-10-11\"]" : "\"2026-10-11\"";
            var response = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                    .header("Authorization", "Bearer " + tokenA)
                    .param("filters", "[{\"field\":\"day\",\"operator\":\"" + operator + "\",\"value\":" + value + "}]"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
            var items = json.readTree(response.getResponse().getContentAsString()).path("items");
            assertThat(items.size()).isEqualTo("GE".equals(operator) ? 2 : 1);
            assertThat(items.toString()).doesNotContain("Date B");
        }
        for (String direction : List.of("ASC", "DESC")) {
            String cursor = null;
            var days = new ArrayList<String>();
            for (int page = 0; page < 2; page++) {
                var call = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                        .header("Authorization", "Bearer " + tokenA).param("limit", "1")
                        .param("sort", "day").param("direction", direction);
                if (cursor != null) call.param("cursor", cursor);
                var response = http.perform(call).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
                var body = json.readTree(response.getResponse().getContentAsString());
                assertThat(body.path("items").size()).isEqualTo(1);
                days.add(body.path("items").get(0).path("day").asText());
                cursor = body.path("nextCursor").isNull() ? null : body.path("nextCursor").asText();
                if (page == 0) assertThat(cursor).isNotBlank();
            }
            assertThat(days).containsExactlyElementsOf("ASC".equals(direction)
                    ? List.of("2026-10-11", "2026-10-12") : List.of("2026-10-12", "2026-10-11"));
            assertThat(cursor).isNull();
        }
        applyTestMetaContext();
        for (var variation : Map.<String, Object>of("units", 8, "amount", new java.math.BigDecimal("99.00"),
                "enabled", false, "moment", Instant.parse("2026-10-12T12:34:56Z")).entrySet()) {
            var values = new HashMap<String, Object>(Map.of("name", "Typed denied " + variation.getKey(),
                    "day", java.time.LocalDate.of(2026, 10, 11), "zone", "A", "units", 7,
                    "amount", new java.math.BigDecimal("12.50"), "enabled", true, "moment", Instant.parse("2026-10-11T12:34:56Z")));
            values.put(variation.getKey(), variation.getValue()); humanCreate(physical, values);
        }
        var typedConstraints = new ArrayList<>(List.of(new OpenApiInstallationPolicy.RowConstraint("units", List.of("7")),
                new OpenApiInstallationPolicy.RowConstraint("amount", List.of("12.50")),
                new OpenApiInstallationPolicy.RowConstraint("enabled", List.of("true")),
                new OpenApiInstallationPolicy.RowConstraint("moment", List.of("2026-10-11T12:34:56Z"))));
        String typedMutation = System.getenv("AURA_IT_NEGATIVE_MUTATION");
        assertThat(typedMutation).isIn(null, "typed-omit-units", "typed-policy-and-quota");
        if (Set.of("typed-omit-units", "typed-policy-and-quota").contains(typedMutation == null ? "" : typedMutation)) typedConstraints.removeIf(constraint -> "units".equals(constraint.field()));
        resources.grant(principalA.installationPid(), contract.code(), new OpenApiInstallationPolicy(contract.operations(),
                fields.keySet(), Set.of(), typedConstraints));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + tokenA))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[*].name").value(org.hamcrest.Matchers.contains("Date A1")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].units").value(7))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].amount").value(12.5))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].enabled").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].moment").value("2026-10-11T12:34:56Z"));
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
    @Test void sourceSemanticsCannotFloatBehindAnImmutablePublicSnapshot() throws Exception {
        String originalSnapshot = publicationMapper.snapshot(principalA.tenantId(), rows.storageCode(), 1);
        assertThat(originalSnapshot).isNotBlank();
        publications.grant(principalA.installationPid(), rows.code(), new OpenApiQueryPolicy(Set.of("pid")));
        var originalPolicy = publications.authority(principalA, rows.code()).orElseThrow().policy();
        assertThat(originalPolicy.readableFields()).containsExactly("pid");
        var renamedFields = new LinkedHashMap<>(rows.fields());
        renamedFields.put("displayName", renamedFields.remove("name"));
        var incompatible = new OpenApiQueryContract(rows.code(), rows.queryCode(), 2, rows.sourceFingerprint(),
                rows.scopes(), rows.sourceResources(), rows.parameters(), renamedFields, rows.maxPageSize(),
                rows.maxRows(), rows.timeoutMs(), rows.maxConcurrent());
        publications.saveDraft(rows.code(), new OpenApiQueryPublicationService.DraftRequest(1, incompatible));
        assertThatThrownBy(() -> publications.publish(rows.code(), 2)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Existing query output cannot change");
        assertThat(publicationMapper.snapshot(principalA.tenantId(), rows.storageCode(), 2)).isNull();
        var major = new OpenApiQueryContract(rows.code() + ".v2", rows.queryCode(), 1, rows.sourceFingerprint(),
                rows.scopes(), rows.sourceResources(), rows.parameters(), renamedFields, rows.maxPageSize(),
                rows.maxRows(), rows.timeoutMs(), rows.maxConcurrent());
        publications.saveDraft(major.code(), new OpenApiQueryPublicationService.DraftRequest(0, major));
        publications.publish(major.code(), 1);
        assertThat(publications.active(principalA.tenantId(), major.code())).contains(major);
        assertThat(publications.authority(principalA, major.code())).isEmpty();
        assertThatThrownBy(() -> run(principalA, major, "A")).isInstanceOf(AccessDeniedException.class);
        String route = "/api/open/v1/queries/" + major.code() + ":execute";
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(route)
                .header("Authorization", "Bearer " + tokenA).contentType("application/json")
                .content("{\"parameters\":{\"zone\":\"A\"},\"limit\":10}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        applyTestMetaContext();
        publications.grant(principalA.installationPid(), major.code(), new OpenApiQueryPolicy(Set.of("pid", "displayName")));
        var oldItem = (Map<?, ?>) ((List<?>) run(principalA, rows, "A").get("items")).getFirst();
        var newItem = (Map<?, ?>) ((List<?>) run(principalA, major, "A").get("items")).getFirst();
        assertThat(oldItem).hasSize(1);
        assertThat(oldItem.containsKey("pid")).isTrue();
        assertThat(newItem.get("pid")).isEqualTo(oldItem.get("pid"));
        assertThat(newItem.get("displayName")).isEqualTo("Root A");
        assertThat(newItem.containsKey("name")).isFalse();
        assertThat(publications.authority(principalB, major.code())).isEmpty();
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(route)
                .header("Authorization", "Bearer " + tokenA).contentType("application/json")
                .content("{\"parameters\":{\"zone\":\"A\"},\"limit\":10}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items.length()").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].displayName").value("Root A"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].name").doesNotExist());
        applyTestMetaContext();
        publications.revoke(principalA.installationPid(), major.code());
        assertThatThrownBy(() -> run(principalA, major, "A")).isInstanceOf(AccessDeniedException.class);
        assertThat(run(principalA, rows, "A").get("items")).isEqualTo(List.of(oldItem));
        assertThat(publications.authority(principalA, rows.code()).orElseThrow().policy()).isEqualTo(originalPolicy);
        assertThat(publications.active(principalA.tenantId(), rows.code())).contains(rows);
        assertThat(publicationMapper.snapshot(principalA.tenantId(), rows.storageCode(), 1)).isEqualTo(originalSnapshot);
        assertThat(publicationMapper.snapshot(principalA.tenantId(), major.storageCode(), 1)).isNotBlank().isNotEqualTo(originalSnapshot);
        publications.grant(principalA.installationPid(), major.code(), new OpenApiQueryPolicy(Set.of("pid", "displayName")));
        var update=new NamedQueryUpdateRequest();update.setActionCode("update");queries.update(rowsDefinition.getPid(),update);
        assertThatThrownBy(()->run(principalA,rows,"A")).isInstanceOf(AccessDeniedException.class).hasMessageContaining("source is unavailable");
        assertThatThrownBy(()->run(principalA,major,"A")).isInstanceOf(AccessDeniedException.class).hasMessageContaining("source is unavailable");
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
        var virtualSource = createQuery("virtual_timeout_" + suffix, "mt_" + root.modelCode() + " a",
                List.of(nqField("pid", "a.pid", "string"), nqField("name", "a.name || pg_sleep(0.2)::text", "string"),
                        nqField("zone", "a.zone", "string")));
        String modelCode = "virtual_timeout_" + suffix;
        models.saveDefinition(ModelDefinition.builder().code(modelCode).displayName("Virtual source deadline")
                .sourceType("namedQuery").sourceRef(virtualSource.getCode()).primaryKey("pid").status("published")
                .capabilities(ModelCapabilities.virtualReadOnly()).fields(List.of(
                        FieldDefinition.builder().code("name").dataType("STRING").build(),
                        FieldDefinition.builder().code("zone").dataType("STRING").filterable(true).build())).build());
        var virtualContract = new OpenApiResourceContract("virtual-timeout." + suffix, modelCode, 1, root.operations(), root.scopes(),
                DataPolicy.INSTALLATION_FILTER, root.fields(), 50, 8, 30, Set.of(), Map.of(root.modelCode(), root.code()));
        resources.saveDraft(virtualContract.code(), new OpenApiPublicationService.DraftRequest(0, virtualContract));
        resources.publish(virtualContract.code(), 1);
        resources.grant(principalA.installationPid(), virtualContract.code(), new OpenApiInstallationPolicy(virtualContract.operations(),
                virtualContract.fields().keySet(), Set.of(), List.of(new OpenApiInstallationPolicy.RowConstraint("zone", List.of("A")))));
        MetaContext.setContext(principalA.tenantId(), null, principalA.subjectPid(), "machine");
        // Keep the outer fixture transaction alive after a real PostgreSQL statement cancellation.
        try (var machine = MachineDataAccessContext.begin(principalA)) {
            assertThatThrownBy(() -> boundary.executeWithoutResult(status ->
                    publicResources.list(principalA, virtualContract.code(), 10, null, null, null, null)))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .hasMessageContaining("504").hasMessageContaining("open_api_query_timeout")
                    .hasMessageNotContaining(virtualSource.getCode()).hasMessageNotContaining(root.modelCode())
                    .hasMessageNotContaining(tokenA).hasMessageNotContaining("pg_sleep").hasMessageNotContaining("fromSql");
        } finally { applyTestMetaContext(); }
        assertThat(run(principalA,rows,"A").get("total")).isEqualTo(1L);
        String allowedPid = json.valueToTree(run(principalA, rows, "A")).get("items").get(0).get("pid").asText();
        var failingSource = createQuery("virtual_fault_" + suffix, "mt_" + root.modelCode() + " a",
                List.of(nqField("pid", "a.pid", "string"), nqField("name", "a.name || (1 / 0)::text", "string"),
                        nqField("zone", "a.zone", "string")));
        String failingModel = "virtual_fault_" + suffix;
        models.saveDefinition(ModelDefinition.builder().code(failingModel).displayName("Virtual source failure")
                .sourceType("namedQuery").sourceRef(failingSource.getCode()).primaryKey("pid").status("published")
                .capabilities(ModelCapabilities.virtualReadOnly()).fields(List.of(
                        FieldDefinition.builder().code("name").dataType("STRING").build(),
                        FieldDefinition.builder().code("zone").dataType("STRING").filterable(true).build())).build());
        var failingContract = new OpenApiResourceContract("virtual-fault." + suffix, failingModel, 1, root.operations(), root.scopes(),
                DataPolicy.INSTALLATION_FILTER, root.fields(), 50, 8, 5000, Set.of(), Map.of(root.modelCode(), root.code()));
        resources.saveDraft(failingContract.code(), new OpenApiPublicationService.DraftRequest(0, failingContract));
        resources.publish(failingContract.code(), 1);
        resources.grant(principalA.installationPid(), failingContract.code(), new OpenApiInstallationPolicy(failingContract.operations(),
                failingContract.fields().keySet(), Set.of(), List.of(new OpenApiInstallationPolicy.RowConstraint("zone", List.of("A")))));
        var failingQuery = new OpenApiQueryContract("fault-query." + suffix, failingSource.getCode(), 1,
                queries.describeExecution(failingSource.getCode()).fingerprint(), rows.scopes(), Map.of(root.modelCode(), root.code()),
                Map.of(), Map.of("pid", output("pid", Type.PID), "name", output("name", Type.STRING), "zone", output("zone", Type.STRING)),
                20, 100, 5000, 2);
        publications.saveDraft(failingQuery.code(), new OpenApiQueryPublicationService.DraftRequest(0, failingQuery));
        publications.publish(failingQuery.code(), 1);
        publications.grant(principalA.installationPid(), failingQuery.code(), new OpenApiQueryPolicy(failingQuery.fields().keySet()));
        MetaContext.setContext(principalA.tenantId(), null, principalA.subjectPid(), "machine");
        try (var machine = MachineDataAccessContext.begin(principalA)) {
            assertThatThrownBy(() -> boundary.executeWithoutResult(status -> publicQueries.execute(principalA, failingQuery.code(),
                    new OpenApiQueryService.ExecuteRequest(Map.of(), 10, null))))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .hasMessageContaining("503").hasMessageContaining("open_api_query_unavailable")
                    .hasMessageNotContaining("SELECT").hasMessageNotContaining("division by zero");
            for (boolean detail : List.of(false, true)) {
                assertThatThrownBy(() -> boundary.executeWithoutResult(status -> {
                    if (detail) publicResources.get(principalA, failingContract.code(), allowedPid);
                    else publicResources.list(principalA, failingContract.code(), 10, null, null, null, null);
                })).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                        .hasMessageContaining("503").hasMessageContaining("open_api_query_unavailable")
                        .hasMessageNotContaining(failingSource.getCode()).hasMessageNotContaining(root.modelCode())
                        .hasMessageNotContaining("SELECT").hasMessageNotContaining("division by zero");
            }
        } finally { applyTestMetaContext(); }
        assertThat(run(principalA,rows,"A").get("total")).isEqualTo(1L);
    }
    private void verifyAmbiguousVirtualGetAndSourceBudgetBoundary(String pid, String healthyPath) throws Exception {
        for (int maxRows : List.of(5000, 1)) {
            applyTestMetaContext();
            String suffix = UniqueIdGenerator.generate().toLowerCase();
            var source = createQuery("ambiguous_source_" + suffix,
                    "mt_" + root.modelCode() + " a JOIN mt_" + root.modelCode() + " b ON a.zone = b.zone",
                    List.of(nqField("pid", "a.pid", "string"), nqField("name", "a.name", "string"), nqField("zone", "a.zone", "string")));
            var policy = new com.auraboot.framework.meta.entity.NamedQueryPolicy(); policy.setMaxRows(maxRows);
            var update = new NamedQueryUpdateRequest(); update.setPolicy(policy); queries.update(source.getPid(), update);
            String modelCode = "ambiguous_model_" + suffix;
            models.saveDefinition(ModelDefinition.builder().code(modelCode).displayName("Ambiguous virtual source")
                    .sourceType("namedQuery").sourceRef(source.getCode()).primaryKey("pid").status("published")
                    .capabilities(ModelCapabilities.virtualReadOnly()).fields(List.of(
                            FieldDefinition.builder().code("name").dataType("STRING").displayName("Name").build(),
                            FieldDefinition.builder().code("zone").dataType("STRING").displayName("Zone").filterable(true).build())).build());
            var contract = new OpenApiResourceContract("ambiguous." + suffix, modelCode, 1, root.operations(), root.scopes(),
                    DataPolicy.INSTALLATION_FILTER, root.fields(), Math.min(50, maxRows), 8, 5000, Set.of(), Map.of(root.modelCode(), root.code()));
            if (maxRows == 1) {
                assertThatThrownBy(() -> resources.saveDraft(contract.code(), new OpenApiPublicationService.DraftRequest(0, contract)))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Public query exceeds source execution budget");
                assertThat(resources.active(principalA.tenantId(), contract.code())).isEmpty();
                assertThat(publicationMapper.snapshot(principalA.tenantId(), contract.code(), 1)).isNull();
                continue;
            }
            resources.saveDraft(contract.code(), new OpenApiPublicationService.DraftRequest(0, contract)); resources.publish(contract.code(), 1);
            resources.grant(principalA.installationPid(), contract.code(), new OpenApiInstallationPolicy(contract.operations(),
                    contract.fields().keySet(), Set.of(), List.of(new OpenApiInstallationPolicy.RowConstraint("zone", List.of("A")))));
            MetaContext.setContext(principalA.tenantId(), null, principalA.subjectPid(), "machine");
            try (var machine = MachineDataAccessContext.begin(principalA)) {
                assertThatThrownBy(() -> publicResources.get(principalA, contract.code(), pid))
                        .isInstanceOf(OpenApiQueryService.QueryExecutionUnavailableException.class)
                        .hasMessageContaining("503").hasMessageContaining("open_api_query_unavailable")
                        .hasCauseInstanceOf(IllegalStateException.class).hasMessageNotContaining(modelCode);
            } finally { applyTestMetaContext(); }
            for (int limit : List.of(1, 50)) {
                String listMarker = "ambiguous-list-" + limit + "-" + suffix;
                var denied = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/open/v1/resources/" + contract.code()).param("limit", Integer.toString(limit))
                        .header("Authorization", "Bearer " + tokenA).header("X-Request-Id", listMarker))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable())
                        .andReturn().getResponse();
                assertThat(json.readTree(denied.getContentAsString())).isEqualTo(json.valueToTree(Map.of(
                        "success", false, "code", "open_api_query_unavailable", "message", "Published query execution is unavailable")));
                applyTestMetaContext();
                var listAudits = management.listCallAudits(principalA.installationPid(), listMarker, null, 10);
                assertThat(listAudits).hasSize(1); assertThat(listAudits.getFirst().status()).isEqualTo(503);
                assertThat(listAudits.getFirst().operation()).isEqualTo("list");
                assertThat(listAudits.getFirst().resource()).isEqualTo(contract.code());
                assertThat(listAudits.getFirst().recordPid()).isNull();
            }
            String marker = "ambiguous-get-" + suffix;
            var response = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/api/open/v1/resources/" + contract.code() + "/" + pid)
                    .header("Authorization", "Bearer " + tokenA).header("X-Request-Id", marker))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().doesNotExist("ETag"))
                    .andReturn().getResponse();
            assertThat(json.readTree(response.getContentAsString())).isEqualTo(json.valueToTree(Map.of(
                    "success", false, "code", "open_api_query_unavailable", "message", "Published query execution is unavailable")));
            assertThat(response.getContentAsString()).doesNotContain(pid, modelCode, source.getCode(), "ambiguous", "fromSql");
            applyTestMetaContext();
            var audits = management.listCallAudits(principalA.installationPid(), marker, null, 10);
            assertThat(audits).hasSize(1); assertThat(audits.getFirst().status()).isEqualTo(503);
            assertThat(audits.getFirst().resource()).isEqualTo(contract.code()); assertThat(audits.getFirst().operation()).isEqualTo("get");
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(healthyPath).header("Authorization", "Bearer " + tokenA))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.pid").value(pid))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.name").value("Root A Renamed"));
        }
    }

    private void verifyNullVirtualIdentityBeforePagination(String validPid, String missingPid, String healthyPath) throws Exception {
        applyTestMetaContext();
        String suffix = UniqueIdGenerator.generate().toLowerCase();
        var source = createQuery("null_identity_source_" + suffix, "mt_" + root.modelCode() + " a", List.of(
                nqField("pid", "NULLIF(a.pid, '" + missingPid + "')", "string"),
                nqField("name", "a.name", "string"), nqField("zone", "a.zone", "string")));
        // Two valid identities sort before the null row, so limit=1 must fail before pagination.
        var counts = fixtureJdbc.queryForMap("SELECT COUNT(*) AS total, COUNT(NULLIF(pid, ?)) AS identified FROM mt_"
                + root.modelCode() + " WHERE tenant_id = ? AND zone = 'A'", missingPid, principalA.tenantId());
        assertThat(((Number) counts.get("total")).longValue()).isEqualTo(3L);
        assertThat(((Number) counts.get("identified")).longValue()).isEqualTo(2L);
        String modelCode = "null_identity_model_" + suffix;
        models.saveDefinition(ModelDefinition.builder().code(modelCode).displayName("Null virtual identity source")
                .sourceType("namedQuery").sourceRef(source.getCode()).primaryKey("pid").status("published")
                .capabilities(ModelCapabilities.virtualReadOnly()).fields(List.of(
                        FieldDefinition.builder().code("name").dataType("STRING").displayName("Name").build(),
                        FieldDefinition.builder().code("zone").dataType("STRING").displayName("Zone").filterable(true).build())).build());
        var contract = new OpenApiResourceContract("null-identity." + suffix, modelCode, 1, root.operations(), root.scopes(),
                DataPolicy.INSTALLATION_FILTER, root.fields(), 50, 8, 5000, Set.of(), Map.of(root.modelCode(), root.code()));
        resources.saveDraft(contract.code(), new OpenApiPublicationService.DraftRequest(0, contract)); resources.publish(contract.code(), 1);
        resources.grant(principalA.installationPid(), contract.code(), new OpenApiInstallationPolicy(contract.operations(),
                contract.fields().keySet(), Set.of(), List.of(new OpenApiInstallationPolicy.RowConstraint("zone", List.of("A")))));
        MetaContext.setContext(principalA.tenantId(), null, principalA.subjectPid(), "machine");
        try (var machine = MachineDataAccessContext.begin(principalA)) {
            assertThatThrownBy(() -> publicResources.list(principalA, contract.code(), 1, null, null, null, null))
                    .isInstanceOf(OpenApiQueryService.QueryExecutionUnavailableException.class)
                    .hasCauseInstanceOf(com.auraboot.framework.meta.exception.NamedQueryResourceIdentityException.class);
        } finally { applyTestMetaContext(); }
        String route = "/api/open/v1/resources/" + contract.code();
        for (int limit : List.of(1, 50)) {
            String marker = "null-identity-list-" + limit + "-" + suffix;
            var response = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(route)
                    .param("limit", Integer.toString(limit)).header("Authorization", "Bearer " + tokenA).header("X-Request-Id", marker))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().doesNotExist("ETag"))
                    .andReturn().getResponse();
            assertThat(json.readTree(response.getContentAsString())).isEqualTo(json.valueToTree(Map.of(
                    "success", false, "code", "open_api_query_unavailable", "message", "Published query execution is unavailable")));
            assertThat(response.getContentAsString()).doesNotContain(validPid, missingPid, source.getCode(), modelCode, "NULLIF", "Private");
            applyTestMetaContext();
            var audits = management.listCallAudits(principalA.installationPid(), marker, null, 10);
            assertThat(audits).hasSize(1); assertThat(audits.getFirst().status()).isEqualTo(503);
            assertThat(audits.getFirst().operation()).isEqualTo("list"); assertThat(audits.getFirst().resource()).isEqualTo(contract.code());
            assertThat(audits.getFirst().recordPid()).isNull();
        }
        for (boolean missing : List.of(false, true)) {
            String requestedPid = missing ? missingPid : validPid;
            String marker = "null-identity-get-" + missing + "-" + suffix;
            var response = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(route + "/" + requestedPid)
                    .header("Authorization", "Bearer " + tokenA).header("X-Request-Id", marker))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(missing ? 404 : 200))
                    .andReturn().getResponse();
            if (missing) assertThat(response.getHeader("ETag")).isNull();
            else {
                assertThat(response.getHeader("ETag")).startsWith("\"").endsWith("\"");
                assertThat(json.readTree(response.getContentAsString()).path("pid").asText()).isEqualTo(validPid);
                assertThat(json.readTree(response.getContentAsString()).path("name").asText()).isEqualTo("Root A Renamed");
            }
            assertThat(response.getContentAsString()).doesNotContain(source.getCode(), modelCode, "NULLIF", "Private");
            applyTestMetaContext();
            var audits = management.listCallAudits(principalA.installationPid(), marker, null, 10);
            assertThat(audits).hasSize(1); assertThat(audits.getFirst().status()).isEqualTo(missing ? 404 : 200);
            assertThat(audits.getFirst().operation()).isEqualTo("get"); assertThat(audits.getFirst().resource()).isEqualTo(contract.code());
            assertThat(audits.getFirst().recordPid()).isEqualTo(requestedPid);
        }
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(healthyPath)
                .header("Authorization", "Bearer " + tokenA))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.name").value("Root A Renamed"));
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
        String suffix = UniqueIdGenerator.generate().toLowerCase();
        var virtualQuery = createQuery("virtual_quota_" + suffix, "mt_" + root.modelCode() + " a",
                List.of(nqField("pid", "a.pid", "string"), nqField("name", "a.name", "string"), nqField("zone", "a.zone", "string")));
        String quotaMutation = System.getenv("AURA_IT_NEGATIVE_MUTATION");
        assertThat(quotaMutation).isIn(null, "virtual-quota-relaxed", "typed-policy-and-quota");
        var virtualPolicy = new com.auraboot.framework.meta.entity.NamedQueryPolicy();
        virtualPolicy.setRateLimitPerMinute(Set.of("virtual-quota-relaxed", "typed-policy-and-quota")
                .contains(quotaMutation == null ? "" : quotaMutation) ? 2 : 1);
        var virtualUpdate = new NamedQueryUpdateRequest(); virtualUpdate.setPolicy(virtualPolicy); queries.update(virtualQuery.getPid(), virtualUpdate);
        String modelCode = "virtual_quota_" + suffix;
        models.saveDefinition(ModelDefinition.builder().code(modelCode).displayName("Virtual source quota")
                .sourceType("namedQuery").sourceRef(virtualQuery.getCode()).primaryKey("pid").status("published")
                .capabilities(ModelCapabilities.virtualReadOnly()).fields(List.of(
                        FieldDefinition.builder().code("name").dataType("STRING").build(),
                        FieldDefinition.builder().code("zone").dataType("STRING").filterable(true).build())).build());
        var virtualContract = new OpenApiResourceContract("virtual-quota." + suffix, modelCode, 1, root.operations(), root.scopes(),
                DataPolicy.INSTALLATION_FILTER, root.fields(), 50, 8, 5000, Set.of(), Map.of(root.modelCode(), root.code()));
        resources.saveDraft(virtualContract.code(), new OpenApiPublicationService.DraftRequest(0, virtualContract));
        resources.publish(virtualContract.code(), 1);
        resources.grant(principalA.installationPid(), virtualContract.code(), new OpenApiInstallationPolicy(virtualContract.operations(),
                virtualContract.fields().keySet(), Set.of(), List.of(new OpenApiInstallationPolicy.RowConstraint("zone", List.of("A")))));
        String virtualRoute = "/api/open/v1/resources/" + virtualContract.code();
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(virtualRoute).header("Authorization", "Bearer " + tokenA))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[*].name").value(org.hamcrest.Matchers.contains("Root A")));
        String virtualMarker = "virtual-quota-" + suffix;
        var virtualDenied = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(virtualRoute)
                .header("Authorization", "Bearer " + tokenA).header("X-Request-Id", virtualMarker))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isTooManyRequests())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Retry-After", "60"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("open_api_query_rate_limited"))
                .andReturn().getResponse();
        assertThat(virtualDenied.getContentAsString()).doesNotContain(virtualQuery.getCode(), root.modelCode(), tokenA, "fromSql");
        applyTestMetaContext();
        var virtualAudits = management.listCallAudits(principalA.installationPid(), virtualMarker, null, 10);
        assertThat(virtualAudits).hasSize(1); assertThat(virtualAudits.getFirst().status()).isEqualTo(429);
        assertThat(virtualAudits.getFirst().resource()).isEqualTo(virtualContract.code());
        assertThat(virtualAudits.getFirst().operation()).isEqualTo("list");
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
