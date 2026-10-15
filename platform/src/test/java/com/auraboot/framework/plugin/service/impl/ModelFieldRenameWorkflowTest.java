package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.ModelFieldRenameColumnPolicy;
import com.auraboot.framework.plugin.dto.imports.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Hermetic orchestration tests. PostgreSQL DDL/rollback is a separate component IT gate. */
class ModelFieldRenameWorkflowTest {
    @TempDir Path directory;
    JdbcTemplate jdbc;
    MetaModelService models;
    ModelFieldRenameMigrator service;
    PluginManifestExtended manifest;
    String path;
    final Map<String,Object> model = Map.of("id",11L,"pid","model-pid","table_name","mt_contact","plugin_pid","plugin","source_type","physical");
    Map<String,Object> source, target;
    AtomicBoolean imported;
    AtomicReference<Map<String,Object>> prior;
    AtomicReference<List<Map<String,Object>>> businessRows;
    boolean fresh;
    @BeforeEach void setup() throws Exception {
        MetaContext.setContext(7L,8L,"importer","importer");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
        models = mock(MetaModelService.class);
        service = new ModelFieldRenameMigrator(jdbc,new ObjectMapper(),new ModelFieldRenameColumnPolicy(jdbc),models);
        source = field("old_name",12L); target = field("new_name",13L);
        imported = new AtomicBoolean(false); prior = new AtomicReference<>(); businessRows = new AtomicReference<>(List.of());
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(call -> {
            String sql=call.getArgument(0); Object[] args=Arrays.copyOfRange(call.getArguments(),1,call.getArguments().length);
            if(sql.startsWith("SELECT model_code,from_field")) return List.of();
            if(sql.contains("FROM information_schema.columns")) return source==null && prior.get()==null ? List.of() : List.of(Map.of("data_type","character varying","is_nullable","NO"));
            if(sql.contains("FROM ab_meta_model WHERE")) return fresh && !imported.get() ? List.of() : List.of(model);
            if(sql.contains("f.code<>?")) return List.of();
            if(sql.contains("FROM pg_index")) return List.of();
            if(sql.contains("FROM ab_meta_model_field_binding")) return "old_name".equals(args[2]) ? source==null ? List.of():List.of(source) : List.of(target);
            if(sql.contains("FROM ab_plugin_field_rename WHERE")) return prior.get()==null ? List.of():List.of(prior.get());
            if(sql.contains("FROM \"mt_contact\"")) { assertEquals(7L,args[0]); return businessRows.get(); }
            throw new AssertionError("Unexpected query: "+sql);
        });
        when(jdbc.queryForObject(anyString(), any(Class.class),any(Object[].class))).thenAnswer(call -> {
            String sql=call.getArgument(0);
            if(sql.contains("INSERT INTO ab_plugin_field_rename")) return 99L;
            if(sql.contains("count(*)")) return 0L;
            if(sql.contains("ab_plugin_resource")) return true;
            if(sql.contains("pg_advisory_xact_lock")) return null;
            if(sql.contains("EXISTS")) return false;
            throw new AssertionError("Unexpected scalar query: "+sql);
        });
        when(models.getModelDefinitionFromDb("contact")).thenReturn(Optional.of(ModelDefinition.builder().code("contact").tableName("mt_contact").fields(List.of(FieldDefinition.builder().code("new_name").columnName("new_name").dataType("string").maxLength(50).required(true).build())).build()));
        manifest = new PluginManifestExtended();
        manifest.setModels(List.of(ModelDefinitionDTO.builder().code("contact").build()));
        manifest.setFields(List.of(com.auraboot.framework.plugin.dto.imports.FieldDefinitionDTO.builder().code("new_name").dataType("STRING").build()));
        manifest.setModelFieldBindings(List.of(ModelFieldBindingDTO.builder().modelCode("contact").fieldCode("new_name").required(true).build()));
        path=directory.resolve("mapping.json").toString();
        Files.writeString(Path.of(path),"{\"schemaVersion\":2,\"modelFieldRenames\":[{\"modelCode\":\"contact\",\"fromField\":\"old_name\",\"toField\":\"new_name\"}]}");
    }
    @AfterEach void cleanup() {
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(0));
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        MetaContext.clear();
    }
    Map<String,Object> field(String code,long id) {
        var f=new HashMap<String,Object>(); f.put("code",code); f.put("field_id",id); f.put("binding_pid",code+"-binding"); f.put("field_pid",code+"-pid"); f.put("data_type","STRING"); f.put("required",true); f.put("visible",true); f.put("editable",true); f.put("extension","{}"); return f;
    }
    @Test void freshModelHasNoSourceDependencyAndKeepsRequiredBinding() {
        fresh=true;
        try(var session=service.prepare(path,manifest,7L,"plugin","import")) {
            imported.set(true);
            var receipt=session.complete().getFirst();
            assertTrue(receipt.freshInstall()); assertEquals(0,receipt.copiedRows());
        }
        assertTrue(manifest.getModelFieldBindings().getFirst().getRequired());
        verify(jdbc,never()).update(contains("SET deleted_flag=true"),any(Object[].class));
    }
    @Test void freshTenantDoesNotInferOrTouchOtherTenantLegacyRows() {
        fresh=true;
        try(var session=service.prepare(path,manifest,7L,"plugin","import")) {
            imported.set(true); session.complete();
        }
        verify(jdbc).queryForList(contains("FROM \"mt_contact\" WHERE tenant_id=? AND (?::text IS NULL OR pid>?) ORDER BY pid LIMIT 500 FOR UPDATE"),eq(7L),isNull(),isNull());
        verify(jdbc,never()).update(contains("UPDATE \"mt_contact\""),any(Object[].class));
    }
    @Test void copyUpdatesOnlyTargetCellAndRetiresOnlyScopedBinding() {
        var row=new HashMap<String,Object>(); row.put("pid","row-pid");row.put("source_value","😀中文");row.put("target_value",null);businessRows.set(List.of(row));
        try(var session=service.prepare(path,manifest,7L,"plugin","import")) { assertEquals(1,session.complete().getFirst().copiedRows()); }
        verify(jdbc).update("UPDATE \"mt_contact\" SET \"new_name\"=? WHERE tenant_id=? AND pid=?","😀中文",7L,"row-pid");
        verify(jdbc).update(contains("SET deleted_flag=true"),eq(11L),eq(7L),eq(12L));
        verify(jdbc).execute("LOCK TABLE \"mt_contact\" IN SHARE ROW EXCLUSIVE MODE");
        verify(models).clearAllCache();
    }
    @Test void replayNeverRecopiesAChangedOrClearedTarget() {
        source.put("required",false);target.put("required",false);
        manifest.getModelFieldBindings().getFirst().setRequired(false);
        when(models.getModelDefinitionFromDb("contact")).thenReturn(Optional.of(ModelDefinition.builder().code("contact").tableName("mt_contact").fields(List.of(FieldDefinition.builder().code("new_name").columnName("new_name").dataType("string").maxLength(50).required(false).build())).build()));
        String targetHash;
        try(var session=service.prepare(path,manifest,7L,"plugin","first")) { targetHash=session.complete().getFirst().targetDefinitionSha256(); }
        source=null;
        prior.set(Map.of("id",99L,"plugin_pid","plugin","physical_table","mt_contact","source_definition_sha256","a".repeat(64),"target_definition_sha256",targetHash));
        for(Object edited : Arrays.asList("Legitimately edited",null)) {
            var row=new HashMap<String,Object>(); row.put("pid","row-pid");row.put("source_value","original");row.put("target_value",edited);businessRows.set(List.of(row));
            try(var session=service.prepare(path,manifest,7L,"plugin","again")) { assertEquals(1,session.complete().getFirst().retainedRows()); }
        }
        verify(jdbc,never()).update(contains("UPDATE \"mt_contact\""),any(Object[].class));
    }
    @Test void bindingFromAnotherModelAndWrongOwnerAreRejectedBeforeImport() {
        manifest.setModelFieldBindings(List.of(ModelFieldBindingDTO.builder().modelCode("category").fieldCode("new_name").build()));
        assertThrows(IllegalArgumentException.class,()->service.prepare(path,manifest,7L,"plugin","import"));
        manifest.setModelFieldBindings(List.of(ModelFieldBindingDTO.builder().modelCode("contact").fieldCode("new_name").build()));
        assertThrows(IllegalArgumentException.class,()->service.prepare(path,manifest,7L,"foreign-plugin","import"));
    }
    @Test void retiredBindingAndCommandCannotReturnThroughUnmappedOrLegacyImports() throws Exception {
        doReturn(List.of(Map.of("model_code","contact","from_field","old_name"))).when(jdbc).queryForList(startsWith("SELECT model_code,from_field"),eq(7L));
        var clean=manifest.getModelFieldBindings();
        manifest.setModelFieldBindings(List.of(ModelFieldBindingDTO.builder().modelCode("contact").fieldCode("old_name").build()));
        assertThrows(IllegalArgumentException.class,()->service.prepare(null,manifest,7L,"plugin","import"));
        Files.writeString(Path.of(path),"{\"identifiers\":[{\"from\":\"legacy\",\"to\":\"renamed\"}]}");
        assertThrows(IllegalArgumentException.class,()->service.prepare(path,manifest,7L,"plugin","import"));
        manifest.setModelFieldBindings(clean);
        manifest.setCommands(List.of(CommandDefinitionDTO.builder().code("contact:update").modelCode("contact").type("update").inputFields(List.of("old_name")).build()));
        assertThrows(IllegalArgumentException.class,()->service.prepare(null,manifest,7L,"plugin","import"));
        manifest.setCommands(null);
        assertDoesNotThrow(()->service.prepare(null,manifest,7L,"plugin","import").close());
    }
    @Test void independentMasksAccessPoliciesAndFieldPermissionsCannotDisappear() {
        source.put("extension","{\"extension\":{\"fieldPermission\":{\"view\":[\"private_role\"]}}}");
        assertThrows(IllegalArgumentException.class,()->service.prepare(path,manifest,7L,"plugin","import"));
        source.put("extension","{}");
        when(jdbc.queryForObject(contains("FROM ab_field_mask_config"),eq(Long.class),any(Object[].class))).thenReturn(1L);
        assertThrows(IllegalArgumentException.class,()->service.prepare(path,manifest,7L,"plugin","import"));
        when(jdbc.queryForObject(contains("FROM ab_field_mask_config"),eq(Long.class),any(Object[].class))).thenReturn(0L);
        when(jdbc.queryForObject(contains("FROM ab_data_permission_policy"),eq(Long.class),any(Object[].class))).thenReturn(1L);
        assertThrows(IllegalArgumentException.class,()->service.prepare(path,manifest,7L,"plugin","import"));
    }
    @Test void sourceCapabilitiesAndPhysicalConstraintsCannotBeDropped() {
        for(String feature : List.of("{\"validation\":{\"pattern\":\"private\"}}", "{\"unique\":true}","{\"readonly\":true}","{\"computeDependencies\":[\"other\"]}")) {
            source.put("feature",feature);
            assertThrows(IllegalArgumentException.class,()->service.prepare(path,manifest,7L,"plugin","import"));
        }
        source.remove("feature");target.put("editable",false);
        try(var session=service.prepare(path,manifest,7L,"plugin","import")) {assertThrows(IllegalArgumentException.class,session::complete);}
        target.put("editable",true);
        when(jdbc.queryForObject(contains("FROM pg_constraint"),eq(Long.class),any(Object[].class))).thenReturn(1L);
        try(var session=service.prepare(path,manifest,7L,"plugin","import")) {assertThrows(IllegalArgumentException.class,session::complete);}
        verify(jdbc,never()).update(contains("UPDATE \"mt_contact\""),any(Object[].class));
    }
    @Test void virtualSourceAndTargetConflictFailClosed() {
        source.put("extension","{\"jsonbPath\":\"name\"}");
        assertThrows(IllegalArgumentException.class,()->service.prepare(path,manifest,7L,"plugin","import"));
        source.put("extension","{}");
        businessRows.set(List.of(Map.of("pid","row-pid","source_value","original","target_value","conflict")));
        try(var session=service.prepare(path,manifest,7L,"plugin","import")) { assertThrows(IllegalArgumentException.class,session::complete); }
        verify(jdbc,never()).update(contains("UPDATE \"mt_contact\""),any(Object[].class));
    }
}
