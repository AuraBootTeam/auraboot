package com.auraboot.framework.agent.integration;

import com.auraboot.framework.agent.entity.AgentDefinition;
import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.agent.mapper.AgentEvalCaseMapper;
import com.auraboot.framework.agent.integration.plugin.AgentDefinitionResourceAdapter;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.service.AgentActivationService;
import com.auraboot.framework.agent.service.AgentReleaseDeploymentService;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.plugin.dto.imports.AgentDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ImportRequest.ConflictStrategy;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.auraboot.framework.plugin.service.PluginResourceTracker;
import com.auraboot.framework.test.util.OwnedTestDatabase;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL import/tracking/release writes; only provider discovery and fault injection are controlled. */
class AgentActivationPostgresIT {
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    AgentActivationService activation;
    AgentReleaseDeploymentService releases;
    AgentDefinitionResourceAdapter importer;
    PluginResourceMapper ledger;
    LlmProviderFactory providers;
    PluginResourceTracker tracker;
    final ObjectMapper json = new ObjectMapper();
    long tenant;
    final long actor = 33L;
    String pid, code;
    final String pluginPid = UlidGenerator.generate();

    @BeforeEach void setup() throws Exception {
        String url=System.getenv("TEST_DATABASE_URL");
        OwnedTestDatabase.require(url,OwnedTestDatabase.AURABOOT_SLOT_PATTERN,"An owned migrated slot is required");
        var ds=new DriverManagerDataSource(url,System.getenv("TEST_DATABASE_USERNAME"),System.getenv("TEST_DATABASE_PASSWORD"));
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);
        factory.setTransactionFactory(new SpringManagedTransactionFactory());var sessions=factory.getObject();
        for(var mapper:List.of(AgentDefinitionMapper.class,AgentEvalCaseMapper.class,PluginResourceMapper.class)) sessions.getConfiguration().addMapper(mapper);
        var sql=new SqlSessionTemplate(sessions);ledger=sql.getMapper(PluginResourceMapper.class);
        importer=new AgentDefinitionResourceAdapter(sql.getMapper(AgentDefinitionMapper.class),sql.getMapper(AgentEvalCaseMapper.class),ledger,jdbc,json);
        tracker=new PluginResourceTracker(ledger);releases=new AgentReleaseDeploymentService(jdbc,json);
        providers=mock(LlmProviderFactory.class);
        tenant=ThreadLocalRandom.current().nextLong(100_000_000L,900_000_000L);
        MetaContext.setContext(tenant,actor,"activation-reviewer","activation-reviewer");
        when(providers.listConfiguredProviders(tenant)).thenReturn(List.of(LlmProviderFactory.ProviderInfo.builder().providerCode("test-provider").configured(true).build()));
        activation=new AgentActivationService(jdbc,json,providers,tracker,releases);
        code="activation_"+UlidGenerator.generate();
        tx.executeWithoutResult(status->{
            jdbc.update("INSERT INTO ab_plugin(pid,tenant_id,plugin_id,namespace,version,display_name,status,deleted_flag,manifest) VALUES (?,?,?,?,?,?,?,FALSE,'{}'::jsonb)",pluginPid,tenant,"com.test.activation","activation","1.0.0","Activation fixture","installed");
            var resource=importer.importAgentDefinition(AgentDefinitionDTO.builder().agentCode(code).name("Disabled template").systemPrompt("Preserved prompt").status("disabled").tools(List.of()).build(),pluginPid,"activation-fixture",tenant,ConflictStrategy.OVERWRITE);
            ledger.insert(resource);pid=resource.getResourcePid();
        });
    }
    @AfterEach void clear(){MetaContext.clear();}
    String state(){return jdbc.queryForObject("SELECT to_jsonb(d)::text FROM ab_agent_definition d WHERE tenant_id=? AND pid=?",String.class,tenant,pid);}
    String tracking(){return jdbc.queryForObject("SELECT to_jsonb(r)::text FROM ab_plugin_resource r WHERE tenant_id=? AND resource_code=?",String.class,tenant,code);}
    long releases(){return jdbc.queryForObject("SELECT count(*) FROM ab_agent_release WHERE tenant_id=? AND agent_code=?",Long.class,tenant,code);}

    @Test void firstActivationPinsProviderProtectsCustomizationAndIsIdempotent(){
        assertThat(releases()).isZero();
        var first=tx.execute(status->activation.activate(tenant,pid,"test-provider",actor));
        assertThat(first.created()).isTrue();assertThat(releases()).isEqualTo(1);
        var runtime=releases.requireActive(tenant,code);
        assertThat(runtime.releaseSpec().get("system_prompt")).isEqualTo("Preserved prompt");
        assertThat(String.valueOf(runtime.releaseSpec().get("guardrails"))).contains("test-provider");
        assertThat(jdbc.queryForObject("SELECT employee_id FROM ab_agent_definition WHERE tenant_id=? AND pid=?",Long.class,tenant,pid)).isNull();
        assertThat(jdbc.queryForObject("SELECT user_modified FROM ab_plugin_resource WHERE tenant_id=? AND resource_code=?",Boolean.class,tenant,code)).isTrue();
        jdbc.update("UPDATE ab_agent_definition SET system_prompt='Unpublished edit' WHERE tenant_id=? AND pid=?",tenant,pid);
        var second=tx.execute(status->activation.activate(tenant,pid,"test-provider",actor));
        assertThat(second.releasePid()).isEqualTo(first.releasePid());assertThat(second.created()).isFalse();assertThat(releases()).isEqualTo(1);
        assertThat(releases.requireActive(tenant,code).releaseSpec().get("system_prompt")).isEqualTo("Preserved prompt");
    }
    @Test void missingProviderAndForeignIdentityCannotChangeTemplate(){
        String before=state(),protection=tracking();
        assertThatThrownBy(()->tx.execute(status->activation.activate(tenant,pid,"missing",actor))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->tx.execute(status->activation.activate(tenant,"foreign-pid","test-provider",actor))).isInstanceOf(BusinessException.class);
        assertThat(state()).isEqualTo(before);assertThat(tracking()).isEqualTo(protection);assertThat(releases()).isZero();
    }
    @Test void failureAfterActualPublishRollsBackDefinitionTrackingReleaseAndDeployment(){
        String before=state(),protection=tracking();var failing=spy(releases);
        doAnswer(call->{call.callRealMethod();throw new BusinessException("Injected failure after publishing");}).when(failing).publish(tenant,pid,actor);
        var service=new AgentActivationService(jdbc,json,providers,tracker,failing);
        assertThatThrownBy(()->tx.execute(status->service.activate(tenant,pid,"test-provider",actor))).isInstanceOf(BusinessException.class);
        assertThat(state()).isEqualTo(before);assertThat(tracking()).isEqualTo(protection);assertThat(releases()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_agent_deployment WHERE tenant_id=? AND agent_code=?",Long.class,tenant,code)).isZero();
    }

    @Test void releaseViewComparesBlueprintRatherThanLifecycleTimestampAndScopesChoices(){
        tx.execute(status->activation.activate(tenant,pid,"test-provider",actor));
        var original=releases.listReleases(tenant,pid).getFirst();
        assertThat(original.get("matches_draft")).isEqualTo(true);
        jdbc.update("UPDATE ab_agent_definition SET status='suspended',updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND pid=?",tenant,pid);
        releases.setDeploymentStatus(tenant,code,"active","suspended",actor);
        var paused=releases.listReleases(tenant,pid).getFirst();
        assertThat(paused.get("matches_draft")).isEqualTo(true);
        assertThat(paused.get("deployed")).isEqualTo(true);
        assertThat(paused.get("deployment_status")).isEqualTo("suspended");
        jdbc.update("INSERT INTO ab_tenant(id,pid,name) VALUES (?,?,?)",tenant,UlidGenerator.generate(),"Release view tenant");
        jdbc.update("INSERT INTO ab_tenant(id,pid,name) VALUES (?,?,?)",tenant+1,UlidGenerator.generate(),"Foreign release view tenant");
        long roleId=10_000_000_000_000_000L+tenant;
        jdbc.update("INSERT INTO ab_role(id,pid,tenant_id,name,code,status,deleted_flag) VALUES (?,?,?,?,?,'active',FALSE)", roleId,UlidGenerator.generate(),tenant,"Allowed review team","review_"+code);
        jdbc.update("INSERT INTO ab_role(id,pid,tenant_id,name,code,status,deleted_flag) VALUES (?,?,?,?,?,'active',FALSE)", roleId+1,UlidGenerator.generate(),tenant+1,"Foreign review team","foreign_"+code);
        jdbc.update("UPDATE ab_agent_deployment SET channel_policy=?::jsonb WHERE tenant_id=? AND agent_code=?", "{\"allowedRoleIds\":["+roleId+"]}",tenant,code);
        var policy=releases.getDeploymentPolicy(tenant,pid).channelPolicy();
        assertThat(policy.get("allowedRoleIds")).isEqualTo(List.of(String.valueOf(roleId)));
        assertThat(String.valueOf(policy.get("subjectChoices"))).contains("Allowed review team").doesNotContain("Foreign review team");
        jdbc.update("UPDATE ab_agent_definition SET status='active',updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND pid=?",tenant,pid);
        releases.setDeploymentStatus(tenant,code,"suspended","active",actor);
        assertThat(releases.listReleases(tenant,pid).getFirst().get("matches_draft")).isEqualTo(true);
        jdbc.update("UPDATE ab_agent_definition SET system_prompt='Real unpublished change' WHERE tenant_id=? AND pid=?",tenant,pid);
        assertThat(releases.listReleases(tenant,pid).getFirst().get("matches_draft")).isEqualTo(false);
        assertThat(releases.listReleases(tenant+1,pid)).isEmpty();
        assertThatThrownBy(()->releases.getDeploymentPolicy(tenant+1,pid)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void concurrentActivationCreatesOneRelease() throws Exception {
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<AgentReleaseDeploymentService.PublishedRelease> call=()->{
                MetaContext.setContext(tenant,actor,"activation-reviewer","activation-reviewer");
                try {start.await();return tx.execute(status->activation.activate(tenant,pid,"test-provider",actor));}
                finally {MetaContext.clear();}
            };
            var first=pool.submit(call);var second=pool.submit(call);start.countDown();
            var a=first.get(15,java.util.concurrent.TimeUnit.SECONDS);var b=second.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(a.releasePid()).isEqualTo(b.releasePid());assertThat(a.created()).isNotEqualTo(b.created());assertThat(releases()).isEqualTo(1);
        }
    }
}
