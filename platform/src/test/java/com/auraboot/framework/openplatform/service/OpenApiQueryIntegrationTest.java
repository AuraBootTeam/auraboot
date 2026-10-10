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
}
