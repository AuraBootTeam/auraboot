package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.service.*;
import com.auraboot.framework.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.*;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL + formal import transactions; no test-level transaction or cleanup. */
@SpringBootTest(classes=ModelFieldRenameImportIT.Application.class,properties={
    "spring.datasource.url=${TEST_DATABASE_URL}","spring.data.redis.database=${TEST_REDIS_DATABASE}",
    "spring.kafka.listener.auto-startup=false","spring.main.allow-bean-definition-overriding=false"})
@ActiveProfiles("integration-test")
@EnabledIfEnvironmentVariable(named="FIELD_RENAME_IMPORT_IT",matches="true")
class ModelFieldRenameImportIT {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ComponentScan(basePackages={"com.auraboot.framework","com.auraboot.module"},excludeFilters={
        @ComponentScan.Filter(type=FilterType.CUSTOM,classes={TypeExcludeFilter.class,ProductionOnly.class})})
    @MapperScan({"com.auraboot.framework.**.mapper","com.auraboot.framework.*.dao",
        "com.auraboot.framework.application.release","com.auraboot.module.*.mapper","com.auraboot.module.meta.excel.mapper"})
    @org.springframework.transaction.annotation.EnableTransactionManagement
    @Import(NoBackgroundWork.class)
    static class Application {}
    static class ProductionOnly implements TypeFilter {
        @Override public boolean match(MetadataReader reader,MetadataReaderFactory factory) throws IOException {
            String url=reader.getResource().getURL().toString();
            String testRoot=ModelFieldRenameImportIT.class.getProtectionDomain().getCodeSource().getLocation().toString();
            var annotation=reader.getAnnotationMetadata();
            return url.startsWith(testRoot) || annotation.hasAnnotation(SpringBootConfiguration.class.getName())
                || annotation.hasMetaAnnotation(SpringBootConfiguration.class.getName());
        }
    }
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class NoBackgroundWork {
        @Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor stopPollers() {
            return factory -> {
                var registry=(org.springframework.beans.factory.support.BeanDefinitionRegistry)factory;
                String scheduled=org.springframework.scheduling.config.TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME;
                if(registry.containsBeanDefinition(scheduled)) registry.removeBeanDefinition(scheduled);
                var type=com.auraboot.framework.scheduler.service.SchedulerEngine.class;
                for(String name:factory.getBeanNamesForType(type,false,false)) {
                    registry.removeBeanDefinition(name);
                    var definition=new org.springframework.beans.factory.support.RootBeanDefinition(type);
                    definition.setInstanceSupplier(()->org.mockito.Mockito.mock(type));registry.registerBeanDefinition(name,definition);
                }
            };
        }
    }
    @Autowired PluginImportService imports;
    @Autowired UserService users;
    @Autowired TenantService tenants;
    @Autowired TenantMemberService members;
    @Autowired TenantBootstrapService bootstrap;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired CommandExecutor commands;
    @Autowired MetaModelService models;
    @Autowired com.auraboot.framework.meta.service.DataPermissionPolicyService policies;
    @Autowired com.auraboot.framework.meta.service.FieldMaskService masks;
    @Autowired com.auraboot.framework.meta.service.SchemaManagementService schemas;
    @Autowired PlatformTransactionManager transactions;
    final String nonce=System.getenv().getOrDefault("FIELD_RENAME_IT_NAMESPACE","fr"+UUID.randomUUID().toString().replace("-","").substring(0,10));
    Path evidence;
    record Actor(long tenant,long user,String userPid,long member) {}
    Actor first,second;
    final List<Map<String,Object>> steps=new ArrayList<>();

    @Test void committedImportPreservesTenantDataAndReplayEditsWithAtomicFailure() throws Exception {
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
        String expectedDatabase=Objects.requireNonNull(System.getenv("FIELD_RENAME_IT_DATABASE"),"Explicit owned database is required");
        assertTrue(expectedDatabase.matches("enterprise_[0-9]+"),"Owned Enterprise runtime database is required");
        try(var connection=jdbc.getDataSource().getConnection()) {
            assertEquals(expectedDatabase,connection.getCatalog(),"Refuse a different database before fixture writes");
        }
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ab_plugin_field_rename WHERE model_code LIKE ?",Integer.class,nonce+"%"));
        evidence=Path.of(Objects.requireNonNull(System.getenv("FIELD_RENAME_IT_EVIDENCE_DIR"))).toAbsolutePath().resolve(nonce);
        Files.createDirectories(evidence);
        try {
            String actorsPath=System.getenv("FIELD_RENAME_IT_ACTORS_PATH");
            if (actorsPath == null) {first=actor("first");second=actor("second");}
            else {
                var retained=json.readTree(Files.readAllBytes(Path.of(actorsPath)));
                assertTrue(retained.isArray() && retained.size()==2);
                first=retainedActor(retained.get(0));second=retainedActor(retained.get(1));
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ab_plugin_import_history WHERE plugin_id LIKE ? AND status='success'",Integer.class,"com.test."+nonce+".%"),"Cannot reuse actors after an earlier committed scenario");
            }
            seal("actors",List.of(Map.of("tenantId",first.tenant(),"userPid",first.userPid()),Map.of("tenantId",second.tenant(),"userPid",second.userPid())));
            String contact=nonce+"_contact",category=nonce+"_category",old=nonce+"_old",target=nonce+"_new",ordinary=nonce+"_required";
            var legacy=manifest("main",List.of(contact,category),old,null,ordinary,false,false);
            use(first);importManifest("main-old-a",legacy,null);String row=create(contact,Map.of(old,"😀姓名",ordinary,"required"));String categoryRow=create(category,Map.of(old,"Category untouched",ordinary,"required"));
            use(second);importManifest("main-old-b",legacy,null);String foreign=create(contact,Map.of(old,"Other tenant legacy",ordinary,"required"));
            var foreignBefore=rows(contact,second.tenant());var categoryBefore=rows(category,first.tenant());
            use(first);var before=rows(contact,first.tenant());
            assertTrue(models.getModelDefinition(contact).orElseThrow().getFields().stream().anyMatch(f->old.equals(f.getCode())));
            var renamed=manifest("main",List.of(contact,category),old,target,ordinary,false,false);
            var receipt=importManifest("main-new-a",renamed,mapping(contact,old,target));
            assertEquals(1,receipt.getFieldMigrations().getFirst().copiedRows());
            var after=rows(contact,first.tenant());assertIdentityPreserved(before,after,target);
            assertEquals("😀姓名",after.getFirst().get(target));assertEquals(before.getFirst().get(old),after.getFirst().get(old));
            assertOnlyAddedNullColumn(foreignBefore,rows(contact,second.tenant()),target);assertEquals(categoryBefore,rows(category,first.tenant()));
            assertTrue(fieldRequired(contact,target));assertFalse(fieldPresent(contact,old));
            assertTrue(columnNullable(contact,old));assertTrue(columnNullable(contact,target));assertFalse(columnNullable(contact,ordinary));
            assertTrue(columnExists(contact,old));
            update(contact,row,Map.of(target,"Legitimate edit"));String created=create(contact,Map.of(target,"Target only",ordinary,"required"));
            var replayBefore=rows(contact,first.tenant());importManifest("main-repeat-a",renamed,mapping(contact,old,target));
            assertEquals(replayBefore,rows(contact,first.tenant()));assertNotEquals(row,created);
            use(second);assertThrows(RuntimeException.class,()->create(contact,Map.of(ordinary,"missing legacy must fail")));
            importManifest("main-repeat-b",legacy,null);assertTrue(fieldRequired(contact,old));assertOnlyAddedNullColumn(foreignBefore,rows(contact,second.tenant()),target);assertTrue(columnNullable(contact,old));
            use(first);
            var beforeDowngrade=state(contact);
            assertThrows(com.auraboot.framework.plugin.exception.PluginException.class,()->importManifest("main-old-denied",legacy,null));
            assertEquals(beforeDowngrade,state(contact));
            assertFalse(columnNullable(category,old));
            steps.add(Map.of("stage","shared-table-upgrade-replay","verdict","pass","sourceRowPid",row,"otherTenantRowPid",foreign,"newTargetOnlyRowPid",created));

            use(first);
            String fresh=nonce+"_fresh";var freshManifest=manifest("fresh",List.of(fresh),old,target,ordinary,false,false);
            importManifest("fresh-table",freshManifest,mapping(fresh,old,target));assertTrue(columnExists(fresh,target));assertTrue(fieldRequired(fresh,target));
            use(second);importManifest("fresh-tenant-shared-table",renamed,mapping(contact,old,target));
            // Existing second-tenant source is a migration, not the fresh case. A new
            // current-tenant model over an existing table is exercised below with no rows.
            String tenantFresh=nonce+"_tenant_fresh";
            use(first);var tenantLegacy=manifest("tenantfresh",List.of(tenantFresh),old,null,ordinary,false,false);importManifest("tenantfresh-old-a",tenantLegacy,null);create(tenantFresh,Map.of(old,"Existing foreign row",ordinary,"required"));var tenantForeign=rows(tenantFresh,first.tenant());
            use(second);var tenantNew=manifest("tenantfresh",List.of(tenantFresh),old,target,ordinary,false,false);var freshReceipt=importManifest("tenantfresh-new-b",tenantNew,mapping(tenantFresh,old,target));assertTrue(freshReceipt.getFieldMigrations().getFirst().freshInstall());assertOnlyAddedNullColumn(tenantForeign,rows(tenantFresh,first.tenant()),target);create(tenantFresh,Map.of(target,"Own new row",ordinary,"required"));
            steps.add(Map.of("stage","fresh-table-and-fresh-tenant-shared-table","verdict","pass"));

            use(first);String optional=nonce+"_optional";var optionalOld=manifest("optional",List.of(optional),old,null,ordinary,false,true);importManifest("optional-old",optionalOld,null);String optionalRow=create(optional,Map.of(old,"Initial optional",ordinary,"required"));
            var optionalNew=manifest("optional",List.of(optional),old,target,ordinary,false,true);importManifest("optional-new",optionalNew,mapping(optional,old,target));var clear=new HashMap<String,Object>();clear.put(target,null);update(optional,optionalRow,clear);var cleared=rows(optional,first.tenant());importManifest("optional-repeat",optionalNew,mapping(optional,old,target));assertEquals(cleared,rows(optional,first.tenant()));
            steps.add(Map.of("stage","nullable-target-clear-replay","verdict","pass"));

            String conflict=nonce+"_conflict";var conflictOld=manifest("conflict",List.of(conflict),old,target,ordinary,true,false);importManifest("conflict-old",conflictOld,null);create(conflict,Map.of(old,"Original",target,"Conflicting target",ordinary,"required"));var conflictBefore=state(conflict);var conflictNew=manifest("conflict",List.of(conflict),old,target,ordinary,false,false);assertThrows(com.auraboot.framework.plugin.exception.PluginException.class,()->importManifest("conflict-new",conflictNew,mapping(conflict,old,target)));assertEquals(conflictBefore,state(conflict));
            String length=nonce+"_length";var lengthOld=manifest("length",List.of(length),old,null,ordinary,false,false);importManifest("length-old",lengthOld,null);create(length,Map.of(old,"x".repeat(51),ordinary,"required"));var lengthBefore=state(length);var lengthNew=manifest("length",List.of(length),old,target,ordinary,false,false);assertThrows(com.auraboot.framework.plugin.exception.PluginException.class,()->importManifest("length-new",lengthNew,mapping(length,old,target)));assertEquals(lengthBefore,state(length));assertFalse(columnExists(length,target));
            steps.add(Map.of("stage","conflict-and-length-atomic-rollback","verdict","pass"));
            for(String protection:List.of("mask","column")) {
                String protectedModel=nonce+"_"+protection;
                var protectedOld=manifest(protection,List.of(protectedModel),old,null,ordinary,false,false);
                importManifest(protection+"-old",protectedOld,null);create(protectedModel,Map.of(old,"Sensitive name",ordinary,"required"));
                if(protection.equals("mask")) {
                    var config=new com.auraboot.framework.meta.entity.FieldMaskConfig();config.setModelCode(protectedModel);config.setFieldCode(old);config.setMaskType("FULL");config.setReplacementChar("*");config.setEnabled(true);config.setApplyToList(true);config.setApplyToDetail(true);config.setApplyToExport(true);masks.saveConfig(config);
                } else {
                    var policy=new com.auraboot.framework.meta.dto.DataPermissionPolicyCreateRequest();policy.setName(nonce+"_column");policy.setModelCode(protectedModel);policy.setPolicyType("COLUMN");policy.setFieldCode(old);policy.setMaskType("HIDE");policies.create(policy);
                }
                var protectedBefore=state(protectedModel);
                var protectedNew=manifest(protection,List.of(protectedModel),old,target,ordinary,false,false);
                assertThrows(com.auraboot.framework.plugin.exception.PluginException.class,()->importManifest(protection+"-new-denied",protectedNew,mapping(protectedModel,old,target)));
                assertEquals(protectedBefore,state(protectedModel));
            }
            steps.add(Map.of("stage","independent-mask-and-column-policy-denied","verdict","pass"));
            // One bounded scenario exercises metadata and physical dependency denial.
            for (String guard : List.of("readonly", "uniqueindex")) {
                String guarded=nonce+"_"+guard;
                var original=manifest(guard,List.of(guarded),old,null,ordinary,false,false);
                if (guard.equals("readonly")) {
                    var fields=(List<Map<String,Object>>)original.get("fields");
                    var restricted=new LinkedHashMap<String,Object>(fields.getFirst());restricted.put("feature",Map.of("readonly",true));fields.set(0,restricted);
                }
                importManifest(guard+"-old",original,null);
                if(guard.equals("uniqueindex")) assertTrue(schemas.createFieldIndex(guarded,old,com.auraboot.framework.meta.dto.IndexType.UNIQUE).isSuccess());
                var guardedBefore=state(guarded);
                var forward=manifest(guard,List.of(guarded),old,target,ordinary,false,false);
                assertThrows(com.auraboot.framework.plugin.exception.PluginException.class,()->importManifest(guard+"-new-denied",forward,mapping(guarded,old,target)));
                assertEquals(guardedBefore,state(guarded));
            }
            steps.add(Map.of("stage","metadata-and-physical-dependencies-denied","verdict","pass"));
            seal("completed",Map.of("stages",steps,"database",expectedDatabase,"namespace",nonce,"actualBusinessImportCommits",true,"testLevelTransaction",false));
        } finally {seal("steps-retained",steps);MetaContext.clear();}
    }

    Actor retainedActor(com.fasterxml.jackson.databind.JsonNode retained) {
        var user=users.findByPid(retained.path("userPid").asText());assertNotNull(user);
        long tenant=retained.path("tenantId").longValue();var member=members.findByTenantIdAndUserId(tenant,user.getId());assertNotNull(member);
        return new Actor(tenant,user.getId(),user.getPid(),member.getId());
    }
    Actor actor(String suffix) {
        MetaContext.clear();String identity=nonce+suffix;var user=users.signUp(identity+"@example.invalid","Test2026x",identity,identity);
        var tenant=new Tenant();tenant.setPid(UniqueIdGenerator.generate());tenant.setName(identity);tenant.setDisplayName(identity);tenant.setStatus("active");tenant=tenants.createTenant(tenant);
        var member=members.addMember(user.getId(),tenant.getId(),"active");
        var actor=new Actor(tenant.getId(),user.getId(),user.getPid(),member.getId());use(actor);assertTrue(bootstrap.bootstrapTenant(actor.tenant(),actor.user()).isSuccess());use(actor);return actor;
    }
    void use(Actor actor) {MetaContext.setContext(actor.tenant(),actor.user(),actor.userPid(),nonce);MetaContext.setMemberId(actor.member());}
    Map<String,Object> manifest(String suffix,List<String> modelCodes,String old,String target,String ordinary,boolean both,boolean optional) {
        var fields=new ArrayList<Map<String,Object>>();fields.add(Map.of("code",old,"dataType","string","constraints",Map.of("maxLength",100)));fields.add(Map.of("code",ordinary,"dataType","string","constraints",Map.of("maxLength",100)));
        if(target!=null)fields.add(Map.of("code",target,"dataType","string","constraints",Map.of("maxLength",50)));
        var modelList=new ArrayList<Map<String,Object>>();var bindings=new ArrayList<Map<String,Object>>();var commandList=new ArrayList<Map<String,Object>>();
        for(String model:modelCodes) {
            boolean category=model.endsWith("_category");String active=target==null||category||both?old:target;
            var modelDeclaration=new LinkedHashMap<String,Object>(Map.of("code",model,"displayName",model,"modelType","entity","extension",Map.of("titleField",active)));
            if(!model.endsWith("_contact")) modelDeclaration.put("tableName","mt_"+model);
            modelList.add(modelDeclaration);
            bindings.add(Map.of("modelCode",model,"fieldCode",active,"required",!optional,"editable",true,"visible",true,"displayConfig",Map.of("searchable",true,"sortable",true)));bindings.add(Map.of("modelCode",model,"fieldCode",ordinary,"required",true,"editable",true));
            var inputs=new ArrayList<String>(List.of(active,ordinary));
            if(both && target!=null) {bindings.add(Map.of("modelCode",model,"fieldCode",target,"required",false,"editable",true));inputs.add(target);}
            for(String operation:List.of("create","update"))commandList.add(Map.of("code",model+":"+operation,"modelCode",model,"displayName",operation,"type",operation,"inputFields",inputs));
        }
        return Map.of("pluginId","com.test."+nonce+"."+suffix,"namespace",nonce+suffix,"version","1.0.0","displayName",nonce,"models",modelList,"fields",fields,"modelFieldBindings",bindings,"commands",commandList);
    }
    Path mapping(String model,String from,String to) throws Exception {
        Path mappings=evidence.resolve("migrations");Files.createDirectories(mappings);
        Path path=mappings.resolve(model+"-mapping.json");json.writeValue(path.toFile(),Map.of("schemaVersion",2,"modelFieldRenames",List.of(Map.of("modelCode",model,"fromField",from,"toField",to))));return path;
    }
    ImportExecuteResult importManifest(String label,Map<String,Object> manifest,Path mapping) throws Exception {
        Path directory=evidence.resolve(label);Files.createDirectories(directory);json.writeValue(directory.resolve("plugin.json").toFile(),manifest);
        var preview=imports.parseDirectory(directory.toString(),false);assertTrue(preview.isValid(),()->"Preview: "+preview.getErrors());
        try {var result=imports.execute(preview.getImportId(),ImportRequest.builder().importId(preview.getImportId()).conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE).identifierMappingPath(mapping==null?null:mapping.toString()).build());seal(label+"-result",result);assertTrue(result.isSuccess(),result.getErrorMessage());return result;}
        catch(RuntimeException failure){seal(label+"-failure",Map.of("importId",preview.getImportId(),"failureClass",failure.getClass().getName(),"message",Objects.toString(failure.getMessage(),"")));throw failure;}
    }
    String create(String model,Map<String,Object> values) {var request=new CommandExecuteRequest();request.setPayload(values);request.setClientRequestId(UUID.randomUUID().toString());var result=commands.execute(model+":create",request);assertEquals("completed",result.getPhaseReached());return result.getData().get("recordPid").toString();}
    void update(String model,String pid,Map<String,Object> values) {var request=new CommandExecuteRequest();request.setPayload(values);request.setTargetRecordPid(pid);request.setClientRequestId(UUID.randomUUID().toString());request.setExpectedVersion(((Number)rows(model,MetaContext.getCurrentTenantId()).stream().filter(r->pid.equals(r.get("pid"))).findFirst().orElseThrow().get("row_version")).intValue());assertEquals("completed",commands.execute(model+":update",request).getPhaseReached());}
    String table(String model) {assertTrue(model.startsWith(nonce+"_"));String table=models.getModelDefinitionFromDb(model).orElseThrow().getTableName();assertTrue(table.matches("[a-z][a-z0-9_]{0,62}"));return table;}
    List<Map<String,Object>> rows(String model,long tenant) {return new TransactionTemplate(transactions).execute(status->jdbc.queryForList("SELECT * FROM \""+table(model)+"\" WHERE tenant_id=? ORDER BY pid",tenant));}
    boolean columnExists(String model,String field) {return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema() AND table_name=? AND column_name=?)",Boolean.class,table(model),field);}
    boolean columnNullable(String model,String field) {return jdbc.queryForObject("SELECT is_nullable='YES' FROM information_schema.columns WHERE table_schema=current_schema() AND table_name=? AND column_name=?",Boolean.class,table(model),field);}
    boolean fieldPresent(String model,String field) {return models.getModelDefinition(model).orElseThrow().getFields().stream().anyMatch(f->field.equals(f.getCode()));}
    boolean fieldRequired(String model,String field) {return models.getModelDefinition(model).orElseThrow().getFields().stream().filter(f->field.equals(f.getCode())).findFirst().orElseThrow().isRequired();}
    void assertIdentityPreserved(List<Map<String,Object>> before,List<Map<String,Object>> after,String target) {var normalized=new ArrayList<Map<String,Object>>();for(var row:after){var copy=new LinkedHashMap<>(row);copy.remove(target);normalized.add(copy);}assertEquals(before,normalized);}
    void assertOnlyAddedNullColumn(List<Map<String,Object>> before,List<Map<String,Object>> after,String target) {
        assertEquals(before.size(),after.size());
        for(int i=0;i<after.size();i++) {
            var expected=new LinkedHashMap<>(before.get(i));
            if(!expected.containsKey(target)) expected.put(target,null);
            assertEquals(expected,after.get(i));
        }
    }
    Map<String,Object> state(String model) {
        return new TransactionTemplate(transactions).execute(status -> {
            var result=new LinkedHashMap<String,Object>();long tenant=MetaContext.getCurrentTenantId();
            var modelRow=jdbc.queryForMap("SELECT id,pid,plugin_pid FROM ab_meta_model WHERE tenant_id=? AND code=? AND is_current=true AND deleted_flag=false",tenant,model);
            Object plugin=modelRow.get("plugin_pid");String table=table(model);
            result.put("business",rows(model,tenant));
            result.put("model",jdbc.queryForList("SELECT row_to_json(m)::text AS data FROM ab_meta_model m WHERE tenant_id=? AND code=? ORDER BY id",tenant,model));
            result.put("binding",jdbc.queryForList("SELECT row_to_json(b)::text AS data FROM ab_meta_model_field_binding b JOIN ab_meta_model m ON m.id=b.model_id WHERE m.tenant_id=? AND m.code=? ORDER BY b.id",tenant,model));
            result.put("ledger",jdbc.queryForList("SELECT row_to_json(r)::text AS data FROM ab_plugin_field_rename r WHERE tenant_id=? AND model_code=? ORDER BY id",tenant,model));
            result.put("rowLedger",jdbc.queryForList("SELECT row_to_json(r)::text AS data FROM ab_plugin_field_rename_row r JOIN ab_plugin_field_rename m ON m.id=r.migration_id WHERE m.tenant_id=? AND m.model_code=? ORDER BY r.migration_id,r.record_pid",tenant,model));
            result.put("resources",jdbc.queryForList("SELECT row_to_json(r)::text AS data FROM ab_plugin_resource r WHERE tenant_id=? AND plugin_pid=? ORDER BY id",tenant,plugin));
            result.put("successfulHistory",jdbc.queryForList("SELECT row_to_json(h)::text AS data FROM ab_plugin_import_history h WHERE tenant_id=? AND plugin_pid=? AND status='success' ORDER BY id",tenant,plugin));
            result.put("maskPolicies",jdbc.queryForList("SELECT row_to_json(p)::text AS data FROM ab_field_mask_config p WHERE tenant_id=? AND model_code=? ORDER BY id",tenant,model));
            result.put("accessPolicies",jdbc.queryForList("SELECT row_to_json(p)::text AS data FROM ab_data_permission_policy p WHERE tenant_id=? AND model_code=? ORDER BY id",tenant,model));
            result.put("fieldMetadata",jdbc.queryForList("SELECT row_to_json(f)::text AS data FROM ab_meta_field f WHERE tenant_id IN (?,?) AND code LIKE ? ORDER BY id",first.tenant(),second.tenant(),nonce+"%"));
            result.put("columns",jdbc.queryForList("SELECT * FROM information_schema.columns WHERE table_schema=current_schema() AND table_name=? ORDER BY ordinal_position",table));
            result.put("constraints",jdbc.queryForList("SELECT c.conname,pg_get_constraintdef(c.oid) AS definition FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname=current_schema() AND t.relname=? ORDER BY c.conname",table));
            result.put("indexes",jdbc.queryForList("SELECT indexname,indexdef FROM pg_indexes WHERE schemaname=current_schema() AND tablename=? ORDER BY indexname",table));
            result.put("cachedDefinition",json.valueToTree(models.getModelDefinition(model).orElseThrow()));
            return result;
        });
    }
    void seal(String name,Object value) throws IOException {json.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve(name+".json").toFile(),value);}
}
