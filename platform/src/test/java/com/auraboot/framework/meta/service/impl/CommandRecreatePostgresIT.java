package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

/** Real annotated insert and generated-key behavior on transaction-local PostgreSQL fixtures. */
@Tag("integration")
@EnabledIfEnvironmentVariable(named="AURA_PROCUREMENT_COMMAND_RECREATE_IT",matches="1")
class CommandRecreatePostgresIT {
    private JdbcTemplate jdbc;private TransactionTemplate transaction;private CommandDefinitionMapper mapper;
    @BeforeEach void setup() throws Exception {
        String url=System.getenv("TEST_DATABASE_URL");assertThat(url).matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):5432/enterprise_22(?:\\?.*)?");
        var source=new UnpooledDataSource("org.postgresql.Driver",url,System.getenv("TEST_DATABASE_USERNAME"),System.getenv("TEST_DATABASE_PASSWORD"));
        jdbc=new JdbcTemplate(source);assertThat(jdbc.queryForObject("SELECT current_database()",String.class)).isEqualTo("enterprise_22");
        transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        var configuration=new org.apache.ibatis.session.Configuration();configuration.setMapUnderscoreToCamelCase(true);configuration.addMapper(CommandDefinitionMapper.class);factory.setConfiguration(configuration);
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(CommandDefinitionMapper.class);MetaContext.setContext(1L,7L,null,"system");
    }
    @AfterEach void clear(){MetaContext.clear();}
    private void fixture(Runnable assertions){transaction.executeWithoutResult(status->{
        jdbc.execute("""
            CREATE TEMP TABLE ab_command_definition (
              id bigserial PRIMARY KEY,pid text UNIQUE NOT NULL,tenant_id bigint NOT NULL,
              code text NOT NULL,display_name text,description text,model_code text,
              input_schema jsonb,target_models jsonb,execution_config jsonb,extension jsonb,
              cmd_risk_level text,plugin_pid text,version integer NOT NULL,semver text,
              is_current boolean,row_version integer,status text,deleted_flag boolean,
              created_at timestamptz,updated_at timestamptz,UNIQUE(tenant_id,code,version)
            ) ON COMMIT DROP
            """);assertions.run();
    });}
    private CommandDefinition candidate(long tenant,String pid){var row=new CommandDefinition();row.setPid(pid);row.setTenantId(tenant);row.setCode("app:create");row.setVersion(1);row.setIsCurrent(true);row.setDeletedFlag(false);row.setStatus("DRAFT");row.setRowVersion(1);row.setExecutionConfig("{\"type\":\"create\"}");row.setCreatedAt(Instant.now());row.setUpdatedAt(Instant.now());return row;}
    @Test void deletedCommandsRetainHistoryAndRecreateAtTheNextVersionWithARealPid(){fixture(()->{
        jdbc.update("INSERT INTO ab_command_definition(pid,tenant_id,code,version,is_current,deleted_flag) VALUES('old',1,'app:create',1,TRUE,TRUE),('foreign-old',2,'app:create',99,TRUE,TRUE)");
        var row=candidate(1,"new");assertThat(mapper.insertIdempotent(row)).isEqualTo(1);assertThat(row.getVersion()).isEqualTo(2);assertThat(row.getId()).isNotNull();
        assertThat(mapper.findByPid("new").getVersion()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT deleted_flag FROM ab_command_definition WHERE pid='old'",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_command_definition",Long.class)).isEqualTo(3);
    });}
    @Test void anExistingCurrentRowBlocksInsertWhileAnotherTenantCanCreateItsOwnFirstVersion(){fixture(()->{
        var first=candidate(1,"first");assertThat(mapper.insertIdempotent(first)).isEqualTo(1);assertThat(first.getVersion()).isEqualTo(1);
        var duplicate=candidate(1,"duplicate");assertThat(mapper.insertIdempotent(duplicate)).isZero();assertThat(mapper.findByPid("duplicate")).isNull();
        var foreign=candidate(2,"foreign");assertThat(mapper.insertIdempotent(foreign)).isEqualTo(1);assertThat(foreign.getVersion()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_command_definition",Long.class)).isEqualTo(2);
    });}
}
