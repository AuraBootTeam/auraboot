package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.DataDomainService;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real mapper/transaction boundaries on an explicitly selected, owned development database. */
@Tag("integration")
@EnabledIfEnvironmentVariable(named="AURA_PROCUREMENT_RECORD_WRITER_IT", matches="1")
class RecordCommandWriterPostgresIT {
    private static final String TABLE="mt_procurement_guard_it";
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private DynamicDataMapper mapper;
    private DynamicScopedWriteSupport writer;
    private ModelDefinition model;

    @BeforeEach void setup() throws Exception {
        String url=System.getenv("TEST_DATABASE_URL");
        assertThat(url).matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):5432/enterprise_22(?:\\?.*)?");
        var source=new UnpooledDataSource("org.postgresql.Driver",url,
            System.getenv("TEST_DATABASE_USERNAME"),System.getenv("TEST_DATABASE_PASSWORD"));
        jdbc=new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT current_database()",String.class)).isEqualTo("enterprise_22");
        transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        var configuration=new org.apache.ibatis.session.Configuration();configuration.setMapUnderscoreToCamelCase(true);configuration.addMapper(DynamicDataMapper.class);
        factory.setConfiguration(configuration);
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(DynamicDataMapper.class);
        writer=new DynamicScopedWriteSupport(mapper,mock(DataPermissionEngine.class),mock(DataDomainService.class));
        model=ModelDefinition.builder().code("shared_bid").tableName(TABLE)
            .fields(List.of(FieldDefinition.builder().code("collaboration_managed").columnName("managed_flag").dataType("boolean").immutable(true).build()))
            .extension(Map.of("recordCommandWriters",Map.of("field","collaboration_managed","commands",
                Map.of("create",List.of("app:create"),"update",List.of("app:save"),"delete",List.of())))).build();
        MetaContext.setContext(1L,7L,null,"system");
    }
    @AfterEach void clear() { MetaContext.clear(); }

    private void fixture(Runnable assertions) {
        transaction.executeWithoutResult(status->{
            jdbc.execute("CREATE TEMP TABLE "+TABLE+" (id bigint PRIMARY KEY, pid text UNIQUE NOT NULL, tenant_id bigint NOT NULL, managed_flag boolean, order_pid text, amount integer NOT NULL, row_version bigint NOT NULL DEFAULT 0, deleted_flag boolean NOT NULL DEFAULT FALSE) ON COMMIT DROP");
            jdbc.update("INSERT INTO "+TABLE+" (id,pid,tenant_id,managed_flag,amount) VALUES (1,'owned',1,TRUE,10),(2,'ordinary',1,FALSE,20),(3,'foreign',2,FALSE,30)");
            assertions.run();
        });
    }
    private void exact(Runnable action) {
        MetaContext.runWithCommandPermitPlan("ALL",null,"shared_bid","owned",()->
            MetaContext.runWithAuthorizedCommandCode("app:save",action));
    }
    private int update(String pid,Map<String,Object> values) {
        return writer.executeScopedUpdate(model,"shared_bid","pid",pid,values,Set.of(),null);
    }

    @Test void ordinaryWriterCannotAlterManagedOrForeignRowsButCanUpdateItsOrdinaryRow() {
        fixture(()->{
            assertThat(update("owned",Map.of("amount",99))).isZero();
            assertThat(update("foreign",Map.of("amount",99))).isZero();
            assertThat(update("ordinary",Map.of("amount",21))).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT amount FROM "+TABLE+" WHERE pid='owned'",Integer.class)).isEqualTo(10);
            assertThat(jdbc.queryForObject("SELECT row_version FROM "+TABLE+" WHERE pid='owned'",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT amount FROM "+TABLE+" WHERE pid='foreign'",Integer.class)).isEqualTo(30);
            assertThat(jdbc.queryForObject("SELECT amount FROM "+TABLE+" WHERE pid='ordinary'",Integer.class)).isEqualTo(21);
        });
    }
    @Test void exactWriterCanEditAmountButCannotDemoteOrAcquireOwnership() {
        fixture(()->exact(()->{
            assertThat(update("owned",Map.of("amount",11))).isEqualTo(1);
            assertThat(update("owned",Map.of("managed_flag",false))).isZero();
            var clear=new HashMap<String,Object>();clear.put("managed_flag",null);
            assertThat(update("owned",clear)).isZero();
            assertThat(update("ordinary",Map.of("managed_flag",true))).isZero();
            assertThat(jdbc.queryForObject("SELECT managed_flag FROM "+TABLE+" WHERE pid='owned'",Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("SELECT managed_flag FROM "+TABLE+" WHERE pid='ordinary'",Boolean.class)).isFalse();
            assertThat(jdbc.queryForObject("SELECT row_version FROM "+TABLE+" WHERE pid='owned'",Long.class)).isEqualTo(1);
        }));
    }
    @Test void legacyPidLookupLocksAndResolvesOnlyAnOrdinaryTargetInItsTenant() {
        fixture(()->{
            assertThatThrownBy(()->RecordCommandWriterGuard.guardLegacyConditions(mapper,model,TABLE,Map.of("id","owned"),"update"))
                .hasMessageContaining("RECORD_WRITER_DENIED");
            assertThatThrownBy(()->RecordCommandWriterGuard.guardLegacyConditions(mapper,model,TABLE,Map.of("id","foreign"),"update"))
                .hasMessageContaining("missing or ambiguous");
            var conditions=RecordCommandWriterGuard.guardLegacyConditions(mapper,model,TABLE,Map.of("id","ordinary"),"update",Map.of("amount",22));
            assertThat(conditions).containsEntry("id",2L).containsEntry("tenant_id",1L);
            assertThat(mapper.update(TABLE,Map.of("amount",22),conditions)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT amount FROM "+TABLE+" WHERE pid='ordinary'",Integer.class)).isEqualTo(22);
        });
    }
    @Test void anUpdatePermitDoesNotPermitDeletionAndTheManagedRowIsRetained() {
        fixture(()->exact(()->{
            assertThat(writer.executeScopedDelete(model,"shared_bid","pid","owned",null)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM "+TABLE+" WHERE pid='owned'",Long.class)).isEqualTo(1);
        }));
    }
    @Test void junctionReplacementChecksActualLockedTargetsAndRetainsDeniedLinks() {
        fixture(()->{
            jdbc.execute("CREATE TEMP TABLE mt_procurement_guard_links_it (parent_pid text NOT NULL, bid_pid text NOT NULL, tenant_id bigint NOT NULL) ON COMMIT DROP");
            jdbc.update("INSERT INTO mt_procurement_guard_links_it VALUES ('parent-a','owned',1),('parent-b','ordinary',1),('parent-c','foreign',1)");
            assertThatThrownBy(()->RecordCommandWriterGuard.guardRelationReplacement(mapper,model,TABLE,
                "mt_procurement_guard_links_it","parent_pid","bid_pid","parent-a"))
                .hasMessageContaining("RECORD_WRITER_DENIED");
            assertThatThrownBy(()->RecordCommandWriterGuard.guardRelationReplacement(mapper,model,TABLE,
                "mt_procurement_guard_links_it","parent_pid","bid_pid","parent-c"))
                .hasMessageContaining("missing or ambiguous");
            RecordCommandWriterGuard.guardRelationReplacement(mapper,model,TABLE,
                "mt_procurement_guard_links_it","parent_pid","bid_pid","parent-b");
            assertThat(mapper.delete("mt_procurement_guard_links_it",Map.of("parent_pid","parent-b","tenant_id",1L))).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM mt_procurement_guard_links_it",Long.class)).isEqualTo(2);
        });
    }

    private void parentFixture(Runnable assertions) {
        fixture(() -> {
            jdbc.execute("CREATE TEMP TABLE mt_procurement_parent_it (id bigint PRIMARY KEY, pid text UNIQUE NOT NULL, tenant_id bigint NOT NULL, managed_flag boolean) ON COMMIT DROP");
            jdbc.update("INSERT INTO mt_procurement_parent_it VALUES (1,'parent-owned',1,TRUE),(2,'parent-ordinary',1,FALSE),(3,'parent-foreign',2,FALSE)");
            jdbc.update("UPDATE " + TABLE + " SET order_pid=CASE WHEN pid='ordinary' THEN 'parent-owned' ELSE 'parent-ordinary' END");
            var parent = RecordCommandWriterParentTest.parentModel("mt_procurement_parent_it");
            model = RecordCommandWriterParentTest.childModel(TABLE);
            var metadata = mock(com.auraboot.framework.meta.service.MetaModelService.class);
            when(metadata.getModelDefinition("order")).thenReturn(java.util.Optional.of(parent));
            writer = new DynamicScopedWriteSupport(mapper, mock(DataPermissionEngine.class), mock(DataDomainService.class), metadata);
            assertions.run();
        });
    }
    @Test void unmarkedChildOfManagedParentIsProtectedByActualUpdateAndDeleteSql() {
        parentFixture(() -> {
            assertThat(update("ordinary", Map.of("amount",99))).isZero();
            assertThat(writer.executeScopedDelete(model,"line","pid","ordinary",null)).isZero();
            assertThat(jdbc.queryForObject("SELECT amount FROM " + TABLE + " WHERE pid='ordinary'",Integer.class)).isEqualTo(20);
            assertThat(jdbc.queryForObject("SELECT row_version FROM " + TABLE + " WHERE pid='ordinary'",Long.class)).isZero();
            exact(() -> assertThat(update("ordinary",Map.of("amount",21))).isEqualTo(1));
        });
    }
    @Test void actualParentTransferRejectsManagedAndForeignButAllowsOrdinaryParent() {
        parentFixture(() -> {
            assertThatThrownBy(() -> update("ordinary",Map.of("order_pid","parent-owned"))).hasMessageContaining("RECORD_WRITER_DENIED");
            assertThatThrownBy(() -> update("ordinary",Map.of("order_pid","parent-foreign"))).hasMessageContaining("current tenant");
            exact(() -> assertThat(update("ordinary",Map.of("order_pid","parent-ordinary"))).isEqualTo(1));
            assertThat(update("ordinary",Map.of("amount",22))).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT order_pid FROM " + TABLE + " WHERE pid='ordinary'",String.class)).isEqualTo("parent-ordinary");
        });
    }
    @Test void legacyBulkDeleteLocksParentAndCannotRemoveItsUnmarkedChild() {
        parentFixture(() -> {
            var parent = RecordCommandWriterParentTest.parentModel("mt_procurement_parent_it");
            assertThatThrownBy(() -> RecordCommandWriterGuard.guardLegacyConditions(mapper,model,TABLE,
                Map.of("order_pid","parent-owned"),"delete",null,code -> parent)).hasMessageContaining("RECORD_WRITER_DENIED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLE + " WHERE pid='ordinary'",Long.class)).isEqualTo(1);
        });
    }

}
