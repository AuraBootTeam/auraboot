package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.dashboard.controller.DashboardController;
import com.auraboot.framework.dashboard.dto.*;
import com.auraboot.framework.dashboard.mapper.*;
import com.auraboot.framework.dashboard.service.impl.DashboardServiceImpl;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.ddl.TableMetadataService;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.*;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.auraboot.framework.plugin.service.PluginResourceTracker;
import com.auraboot.framework.versioning.service.VersionHistoryService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Component IT: real migrated PostgreSQL, real mappers/importer/dashboard service/controller.
 * Unrelated version-history and model-resolution fixtures are outside this protection contract. */
class CustomizationProtectionPostgresIT {
    PGSimpleDataSource dataSource;
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    SqlSessionTemplate sessions;
    PluginResourceMapper resources;
    DashboardServiceImpl dashboards;
    PluginPageNavigationImporter importer;
    String plugin, code, pid, table;
    long tenant;

    @BeforeEach void setUp() throws Exception {
        assertThat(System.getenv("AURA_RUNTIME_NAME")).as("owned runtime must be explicit").isNotBlank();
        String expectedDb = Objects.requireNonNull(System.getenv("POSTGRES_DB"), "POSTGRES_DB required");
        assertThat(expectedDb).matches("auraboot_[0-9]+");
        String url = Objects.requireNonNull(System.getenv("DATABASE_URL"), "DATABASE_URL required; no skip");
        assertThat(url.split("\\?", 2)[0]).endsWith("/" + expectedDb);
        dataSource = new PGSimpleDataSource(); dataSource.setURL(url);
        dataSource.setUser(System.getenv("DATABASE_USERNAME")); dataSource.setPassword(System.getenv("DATABASE_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource); tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        assertThat(jdbc.queryForObject("SELECT current_database()",String.class)).isEqualTo(expectedDb);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_flyway_schema_history WHERE version='20261030113000' AND success",Integer.class)).isEqualTo(1);
        String run = UniqueIdGenerator.generate().toLowerCase();
        tenant = Math.abs(UUID.randomUUID().getMostSignificantBits() >>> 2);
        plugin=UniqueIdGenerator.generate(); code="protection_"+run;pid=UniqueIdGenerator.generate();table="mt_"+code;
        MetaContext.setContext(tenant,7L,"protection-user","Protection reviewer");
        jdbc.update("INSERT INTO ab_tenant(id,pid,name) VALUES (?,?,?)",tenant,UniqueIdGenerator.generate(),code);
        jdbc.update("INSERT INTO ab_plugin(id,pid,tenant_id,plugin_id,namespace,version,manifest) VALUES (?,?,?,?,?,'1.0.0','{}')",tenant,plugin,tenant,code,code);
        jdbc.update("INSERT INTO ab_environment(id,pid,tenant_id,code,name) VALUES (?,?,?,?,?)",tenant,UniqueIdGenerator.generate(),tenant,"test",code);
        var cfg = new MybatisConfiguration();cfg.setMapUnderscoreToCamelCase(true);
        cfg.addMapper(com.auraboot.framework.meta.mapper.MetaModelMapper.class);
        cfg.addMapper(com.auraboot.framework.meta.mapper.MetaFieldMapper.class);
        cfg.addMapper(com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper.class);
        cfg.addMapper(PluginResourceMapper.class);cfg.addMapper(DashboardMapper.class);cfg.addMapper(DynamicDataMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();factory.setDataSource(dataSource);factory.setConfiguration(cfg);
        sessions=new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));resources=sessions.getMapper(PluginResourceMapper.class);
        var json = new ObjectMapper().findAndRegisterModules();
        dashboards = new DashboardServiceImpl(sessions.getMapper(DashboardMapper.class),mock(DashboardModuleMapper.class),json,
                mock(VersionHistoryService.class),null,null,null,null,null);
        var support = new PluginImportSupport(jdbc,json,resources,LoggerFactory.getLogger(getClass()));
        importer = new PluginPageNavigationImporter(null,null,null,null,dashboards,null,null,json,null,null,new HashMap<>(),LoggerFactory.getLogger(getClass()),support);
    }
    @AfterEach void clear() { if(sessions!=null)sessions.clearCache();MetaContext.clear(); }

    void seedDashboard() {
        jdbc.update("INSERT INTO ab_dashboard(id,pid,tenant_id,code,title,scope,owner_id,created_by,widgets) VALUES (?,?,?,?,'Customer layout','personal','protection-user','protection-user','[]')",tenant,pid,tenant,code);
        jdbc.update("INSERT INTO ab_plugin_resource(id,pid,tenant_id,plugin_pid,resource_type,resource_code,resource_pid,user_modified,import_snapshot) VALUES (?,?,?,?,'dashboard',?,?,false,'{\"title\":\"Vendor original\"}'::jsonb)",tenant,UniqueIdGenerator.generate(),tenant,plugin,code,pid);
    }
    DashboardDefinitionDTO incoming() {
        var dto=new DashboardDefinitionDTO();dto.setCode(code);dto.setTitle("New vendor layout");dto.setStatus("draft");dto.setScope("personal");dto.setWidgets(List.of(Map.of("id","total","type","metric")));return dto;
    }
    void persist(com.auraboot.framework.plugin.entity.PluginResource resource) {
        var service=mock(PluginImportServiceImpl.class, CALLS_REAL_METHODS);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"pluginResourceMapper",resources);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"objectMapper",new ObjectMapper().findAndRegisterModules());
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"captureImportSnapshot",resource,incoming());
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"saveOrUpdatePluginResource",resource,tenant);
    }
    String title() { return jdbc.queryForObject("SELECT title FROM ab_dashboard WHERE pid=?",String.class,pid); }

    @Test void realCustomerSaveIsProtectedAndExplicitOverwriteStillWorks() {
        seedDashboard();
        tx.executeWithoutResult(status -> {
            var edit=new DashboardUpdateRequest();edit.setTitle("Customer customization");
            new DashboardController(dashboards,new PluginResourceTracker(resources)).update(pid,edit);
        });
        assertThat(jdbc.queryForObject("SELECT user_modified FROM ab_plugin_resource WHERE resource_pid=?",Boolean.class,pid)).isTrue();
        var result=tx.execute(status -> importer.importDashboard(incoming(),plugin,"test",tenant,ImportRequest.ConflictStrategy.OVERWRITE_SAFE));
        assertThat(result.getAction()).isEqualTo("skip");assertThat(title()).isEqualTo("Customer customization");
        tx.executeWithoutResult(status -> persist(result));
        assertThat(resources.findByTypeAndCode(tenant,"dashboard",code).getUserModified()).isTrue();
        var repeated=tx.execute(status -> importer.importDashboard(incoming(),plugin,"test-again",tenant,ImportRequest.ConflictStrategy.OVERWRITE_SAFE));
        assertThat(repeated.getAction()).isEqualTo("skip");assertThat(title()).isEqualTo("Customer customization");
        assertThat(resources.findByTypeAndCode(tenant,"dashboard",code).getImportSnapshot()).containsEntry("title","Vendor original");
        tx.executeWithoutResult(status -> persist(importer.importDashboard(incoming(),plugin,"test",tenant,ImportRequest.ConflictStrategy.OVERWRITE)));
        assertThat(title()).isEqualTo("New vendor layout");
        assertThat(resources.findByTypeAndCode(tenant,"dashboard",code).getUserModified()).isFalse();
        assertThat(resources.findByTypeAndCode(tenant,"dashboard",code).getImportSnapshot()).containsEntry("title","New vendor layout");
    }
    @Test void unmodifiedDashboardIsUpdatedAndSameNamedPageIsIndependent() {
        seedDashboard();
        jdbc.update("INSERT INTO ab_plugin_resource(id,pid,tenant_id,plugin_pid,resource_type,resource_code,user_modified) VALUES (?,?,?,?,'page',?,true)",tenant+1,UniqueIdGenerator.generate(),tenant,plugin,code);
        tx.execute(status -> importer.importDashboard(incoming(),plugin,"test",tenant,ImportRequest.ConflictStrategy.OVERWRITE_SAFE));
        assertThat(title()).isEqualTo("New vendor layout");
        assertThat(resources.findByTypeAndCode(tenant,"page",code).getUserModified()).isTrue();
    }
    @Test void protectionLookupFailureRollsBackEarlierChanges() {
        seedDashboard();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            // Fault injection is always rolled back, including when testing a broken guard.
            status.setRollbackOnly();
            jdbc.update("UPDATE ab_dashboard SET title='Must roll back' WHERE pid=?",pid);
            jdbc.execute("ALTER TABLE ab_plugin_resource RENAME COLUMN user_modified TO unavailable_marker");
            importer.importDashboard(incoming(),plugin,"test",tenant,ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        })).isInstanceOf(RuntimeException.class);
        assertThat(title()).isEqualTo("Customer layout");
        assertThat(resources.findByTypeAndCode(tenant,"dashboard",code).getUserModified()).isFalse();
    }
    FieldDefinition field() { return FieldDefinition.builder().code(code+"_value").columnName(code+"_value").primaryKey(false).build(); }
    ModelDefinition model() { return ModelDefinition.builder().code(code).tableName(table).build(); }
    SchemaOperationResult remove() { return remove(new FieldDeletionGuard(jdbc)); }
    SchemaOperationResult remove(FieldDeletionGuard guard) {
        var meta=mock(MetaModelService.class);when(meta.getModelDefinition(code)).thenReturn(Optional.of(model()));when(meta.getFieldDefinition(code,field().getCode())).thenReturn(field());
        var tables=mock(TableMetadataService.class);when(tables.tableExists(table)).thenReturn(true);
        when(tables.columnExists(eq(table),anyString())).thenReturn(true);
        var service=new SchemaManagementServiceImpl(meta,sessions.getMapper(DynamicDataMapper.class),null,tables,null,dataSource,null,guard);
        return tx.execute(status -> service.removeFieldFromModel(code,field().getCode()));
    }
    void emptyColumn() { jdbc.execute("CREATE TABLE "+table+" (pid text, "+field().getColumnName()+" text)"); }
    boolean columnExists() { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name=? AND column_name=?)",Boolean.class,table,field().getColumnName())); }
    @Test void emptyUnreferencedColumnCanBeDroppedThroughTheActualSchemaService() {
        emptyColumn();assertThat(remove().getSuccess()).isTrue();assertThat(columnExists()).isFalse();
    }
    @Test void storedDataSurvivesRejectedDeletion() {
        emptyColumn();jdbc.update("INSERT INTO "+table+" VALUES ('original',?)","must survive");
        assertThat(remove().getSuccess()).isFalse();assertThat(columnExists()).isTrue();
        assertThat(jdbc.queryForObject("SELECT "+field().getColumnName()+" FROM "+table,String.class)).isEqualTo("must survive");
    }
    @Test void realPageReferencePreventsDropWithoutDependingOnACachedIndex() {
        emptyColumn();
        jdbc.update("INSERT INTO ab_page_schema(id,pid,tenant_id,env_id,page_key,name,kind,blocks) VALUES (?,?,?,?,?,?,'form',?::jsonb)",tenant,UniqueIdGenerator.generate(),tenant,tenant,code,code,"[{\"blockType\":\"field\",\"fieldCode\":\""+field().getCode()+"\"}]");
        assertThat(remove().getSuccess()).isFalse();assertThat(columnExists()).isTrue();
    }
    @Test void probeFailureCannotBeReportedAsSafeDeletion() {
        emptyColumn();
        var missing=FieldDefinition.builder().code(code+"_missing").columnName(code+"_missing").build();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> new FieldDeletionGuard(jdbc).requireSafeDrop(model(),missing))).isInstanceOf(RuntimeException.class);
        assertThat(columnExists()).isTrue();
    }
    @Test void persistedConditionReferencePreventsDrop() {
        emptyColumn();
        jdbc.update("INSERT INTO ab_drt_condition_fragment(id,pid,tenant_id,fragment_code,fragment_name,condition_spec_json) VALUES (?,?,?,?,?,?::jsonb)",
                tenant,UniqueIdGenerator.generate(),tenant,code,code,"{\"fieldCode\":\""+field().getCode()+"\"}");
        assertThat(remove().getSuccess()).isFalse();assertThat(columnExists()).isTrue();
    }
    @Test void anotherTenantsPhysicalBindingPreventsDropEvenWithNoData() {
        emptyColumn();long other=tenant+2;
        jdbc.update("INSERT INTO ab_tenant(id,pid,name) VALUES (?,?,?)",other,UniqueIdGenerator.generate(),code+"_other");
        jdbc.update("INSERT INTO ab_meta_model(id,pid,tenant_id,code,version,table_name) VALUES (?,?,?,?,1,?)",other,UniqueIdGenerator.generate(),other,code,table);
        jdbc.update("INSERT INTO ab_meta_field(id,pid,tenant_id,code,version,data_type) VALUES (?,?,?,?,1,'string')",other,UniqueIdGenerator.generate(),other,field().getCode());
        jdbc.update("INSERT INTO ab_meta_model_field_binding(id,tenant_id,model_id,field_id) VALUES (?,?,?,?)",other,other,other,other);
        assertThat(remove().getSuccess()).isFalse();assertThat(columnExists()).isTrue();
    }
    @Test void legacyDashboardIdentityMigrationProtectsUnknownHistoricEdits() throws Exception {
        seedDashboard();
        jdbc.update("UPDATE ab_plugin_resource SET resource_type='page',user_modified=false WHERE resource_pid=?",pid);
        String migration=Files.readString(Path.of("src/main/resources/db/migration/core/V20261030113000__dashboard_customization_resource_identity.sql"));
        tx.executeWithoutResult(status -> jdbc.execute(migration));
        assertThat(resources.findByTypeAndCode(tenant,"page",code)).isNull();
        assertThat(resources.findByTypeAndCode(tenant,"dashboard",code).getUserModified()).isTrue();
        tx.executeWithoutResult(status -> jdbc.execute(migration));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_plugin_resource WHERE tenant_id=? AND resource_type='dashboard' AND resource_code=?",Integer.class,tenant,code)).isEqualTo(1);
    }

    @Test void concurrentWriteCannotSlipBetweenTheDataProbeAndDrop() throws Exception {
        emptyColumn();jdbc.update("INSERT INTO "+table+" (pid) VALUES ('original')");
        var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var guard=spy(new FieldDeletionGuard(jdbc));
        doAnswer(call -> { call.callRealMethod();held.countDown();
            if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("test writer did not finish");return null;
        }).when(guard).requireSafeDrop(any(),any());
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        var deletion=pool.submit(() -> {MetaContext.setContext(tenant,7L,"protection-user","Protection reviewer");try{return remove(guard);}finally{MetaContext.clear();}});
        try {
            assertThat(held.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try(var connection=dataSource.getConnection();var statement=connection.createStatement()) {
                connection.setAutoCommit(false);statement.execute("SET LOCAL lock_timeout='150ms'");
                assertThatThrownBy(() -> statement.executeUpdate("UPDATE "+table+" SET "+field().getColumnName()+"='late write'"))
                        .isInstanceOf(java.sql.SQLException.class).extracting(failure -> ((java.sql.SQLException)failure).getSQLState()).isEqualTo("55P03");
                connection.rollback();
            }
        } finally {release.countDown();pool.shutdown();}
        assertThat(deletion.get(5,java.util.concurrent.TimeUnit.SECONDS).getSuccess()).isTrue();
        assertThat(columnExists()).isFalse();
    }

    @Test void schemaRefusalAlsoRetainsTheActualPersistedBinding() {
        emptyColumn();jdbc.update("INSERT INTO "+table+" VALUES ('original','preserve')");
        jdbc.update("INSERT INTO ab_meta_model(id,pid,tenant_id,code,version,table_name,status) VALUES (?,?,?,?,1,?,'published')",tenant,UniqueIdGenerator.generate(),tenant,code,table);
        jdbc.update("INSERT INTO ab_meta_field(id,pid,tenant_id,code,version,data_type) VALUES (?,?,?,?,1,'string')",tenant,UniqueIdGenerator.generate(),tenant,field().getCode());
        jdbc.update("INSERT INTO ab_meta_model_field_binding(id,tenant_id,model_id,field_id) VALUES (?,?,?,?)",tenant,tenant,tenant,tenant);
        var definitions=mock(MetaModelService.class);when(definitions.getModelDefinition(code)).thenReturn(Optional.of(model()));when(definitions.getFieldDefinition(code,field().getCode())).thenReturn(field());
        var tables=mock(TableMetadataService.class);when(tables.tableExists(table)).thenReturn(true);when(tables.columnExists(eq(table),anyString())).thenReturn(true);
        var schema=new SchemaManagementServiceImpl(definitions,sessions.getMapper(DynamicDataMapper.class),null,tables,null,dataSource,null,new FieldDeletionGuard(jdbc));
        var service=mock(MetaModelServiceImpl.class,CALLS_REAL_METHODS);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"metaModelMapper",sessions.getMapper(com.auraboot.framework.meta.mapper.MetaModelMapper.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"metaFieldMapper",sessions.getMapper(com.auraboot.framework.meta.mapper.MetaFieldMapper.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"fieldBindingMapper",sessions.getMapper(com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"schemaManagementService",schema);
        assertThatThrownBy(() -> tx.execute(status -> service.unbindFieldFromModel(tenant,tenant))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_meta_model_field_binding WHERE model_id=? AND field_id=?",Integer.class,tenant,tenant)).isEqualTo(1);
        assertThat(columnExists()).isTrue();
        assertThat(jdbc.queryForObject("SELECT "+field().getColumnName()+" FROM "+table,String.class)).isEqualTo("preserve");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"query","query-field","command","binding-rule","decision-version","policy-version","automation","dashboard","report","template","reference-index","field-formula"})
    void retainedDefinitionFamiliesProtectTheirFieldReferences(String family) {
        emptyColumn();String ref="{\"fieldCode\":\""+field().getCode()+"\"}";
        switch(family) {
            case "query" -> jdbc.update("INSERT INTO ab_named_query(id,pid,tenant_id,code,from_sql) VALUES (?,?,?,?,?)",tenant,UniqueIdGenerator.generate(),tenant,code,table+"."+field().getCode());
            case "query-field" -> {
                jdbc.update("INSERT INTO ab_named_query(id,pid,tenant_id,code,from_sql) VALUES (?,?,?,?,?)",tenant,UniqueIdGenerator.generate(),tenant,code,table);
                jdbc.update("INSERT INTO ab_named_query_field(id,tenant_id,query_code,field_code,column_expr,data_type) VALUES (?,?,?,?,?,'string')",tenant,tenant,code,field().getCode(),field().getCode());
            }
            case "command", "binding-rule" -> {
                jdbc.update("INSERT INTO ab_command_definition(id,pid,tenant_id,code,model_code,input_schema) VALUES (?,?,?,?,?,?::jsonb)",tenant,UniqueIdGenerator.generate(),tenant,code,code,family.equals("command")?ref:"{}");
                if(family.equals("binding-rule"))jdbc.update("INSERT INTO ab_binding_rule(id,pid,tenant_id,command_id,rule_type,config) VALUES (?,?,?,?,'validation',?::jsonb)",tenant,UniqueIdGenerator.generate(),tenant,tenant,ref);
            }
            case "decision-version" -> {
                jdbc.update("INSERT INTO ab_drt_definition(id,pid,tenant_id,decision_code,decision_name) VALUES (?,?,?,?,?)",tenant,UniqueIdGenerator.generate(),tenant,code,code);
                jdbc.update("INSERT INTO ab_drt_version(id,pid,tenant_id,decision_code,version,kind,runtime_adapter,content_json) VALUES (?,?,?,?,1,'SIMPLE_CONDITION','AST_EVALUATOR',?::jsonb)",tenant,UniqueIdGenerator.generate(),tenant,code,ref);
            }
            case "policy-version" -> {
                jdbc.update("INSERT INTO ab_drt_policy_definition(id,pid,tenant_id,policy_code,policy_name,event_type) VALUES (?,?,?,?,?,'command.completed')",tenant,UniqueIdGenerator.generate(),tenant,code,code);
                jdbc.update("INSERT INTO ab_drt_policy_version(id,pid,tenant_id,policy_code,version,rules_json) VALUES (?,?,?,?,1,?::jsonb)",tenant,UniqueIdGenerator.generate(),tenant,code,"["+ref+"]");
            }
            case "automation" -> jdbc.update("INSERT INTO ab_automation(id,pid,tenant_id,name,trigger_type,trigger_config) VALUES (?,?,?,?,'on_field_change',?::jsonb)",tenant,UniqueIdGenerator.generate(),tenant,code,ref);
            case "dashboard" -> jdbc.update("INSERT INTO ab_dashboard(id,pid,tenant_id,code,title,widgets) VALUES (?,?,?,?,?,?::jsonb)",tenant,UniqueIdGenerator.generate(),tenant,code,code,"["+ref+"]");
            case "report" -> jdbc.update("INSERT INTO ab_report(id,pid,tenant_id,code,dsl) VALUES (?,?,?,?,?::jsonb)",tenant,UniqueIdGenerator.generate(),tenant,code,ref);
            case "template" -> jdbc.update("INSERT INTO ab_report_template(id,pid,tenant_id,code,name,data_source_config) VALUES (?,?,?,?,?,?::jsonb)",tenant,UniqueIdGenerator.generate(),tenant,code,code,ref);
            case "reference-index" -> jdbc.update("INSERT INTO ab_resource_reference(id,tenant_id,source_type,source_id,target_type,target_code) VALUES (?,?,'PAGE_SCHEMA',?,'FIELD',?)",tenant,tenant,pid,field().getCode());
            case "field-formula" -> jdbc.update("INSERT INTO ab_meta_field(id,pid,tenant_id,code,version,data_type,feature) VALUES (?,?,?,?,1,'computed',?::jsonb)",tenant,UniqueIdGenerator.generate(),tenant,code+"_derived",ref);
            default -> throw new IllegalArgumentException(family);
        }
        assertThat(remove().getSuccess()).as(family).isFalse();assertThat(columnExists()).isTrue();
    }
    @Test void aLongerFieldIdentifierIsNotAMatchForTheDeletedField() {
        emptyColumn();jdbc.update("INSERT INTO ab_drt_condition_fragment(id,pid,tenant_id,fragment_code,fragment_name,condition_spec_json) VALUES (?,?,?,?,?,?::jsonb)",
                tenant,UniqueIdGenerator.generate(),tenant,code,code,"{\"fieldCode\":\""+field().getCode()+"_extra\"}");
        assertThat(remove().getSuccess()).isTrue();assertThat(columnExists()).isFalse();
    }

    @Test void fieldBindingAliasReferencesAreProtected() {
        emptyColumn();String alias=code+"_alias";
        jdbc.update("INSERT INTO ab_meta_model(id,pid,tenant_id,code,version,table_name) VALUES (?,?,?,?,1,?)",tenant,UniqueIdGenerator.generate(),tenant,code,table);
        jdbc.update("INSERT INTO ab_meta_field(id,pid,tenant_id,code,version,data_type) VALUES (?,?,?,?,1,'string')",tenant,UniqueIdGenerator.generate(),tenant,field().getCode());
        jdbc.update("INSERT INTO ab_meta_model_field_binding(id,tenant_id,model_id,field_id,alias_code) VALUES (?,?,?,?,?)",tenant,tenant,tenant,tenant,alias);
        jdbc.update("INSERT INTO ab_drt_condition_fragment(id,pid,tenant_id,fragment_code,fragment_name,condition_spec_json) VALUES (?,?,?,?,?,?::jsonb)",
                tenant,UniqueIdGenerator.generate(),tenant,code,code,"{\"fieldCode\":\""+alias+"\"}");
        assertThat(remove().getSuccess()).isFalse();assertThat(columnExists()).isTrue();
    }

    @Test void legacyPageProtectionMigrationPreservesBothContentAndBaseline() throws Exception {
        seedDashboard();String page=UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_page_schema(id,pid,tenant_id,env_id,page_key,name,kind,blocks) VALUES (?,?,?,?,?,?,'form','[]')",tenant,page,tenant,tenant,code,code);
        jdbc.update("INSERT INTO ab_plugin_resource(id,pid,tenant_id,plugin_pid,resource_type,resource_code,resource_pid,user_modified,import_snapshot) VALUES (?,?,?,?,'page',?,?,false,'{}')",tenant+1,UniqueIdGenerator.generate(),tenant,plugin,code,page);
        String migration=Files.readString(Path.of("src/main/resources/db/migration/core/V20261030123000__retain_legacy_page_customizations.sql"));
        tx.executeWithoutResult(status -> jdbc.execute(migration));
        assertThat(resources.findByTypeAndCode(tenant,"page",code).getUserModified()).isTrue();
        assertThat(resources.findByTypeAndCode(tenant,"dashboard",code).getUserModified()).isTrue();
        assertThat(title()).isEqualTo("Customer layout");
        assertThat(jdbc.queryForObject("SELECT blocks::text FROM ab_page_schema WHERE pid=?",String.class,page)).isEqualTo("[]");
        assertThat(resources.findByTypeAndCode(tenant,"dashboard",code).getImportSnapshot()).containsEntry("title","Vendor original");
    }

}
