package com.auraboot.framework.agent.integration;

import com.auraboot.framework.agent.integration.im.AgentImDirectory;
import com.auraboot.framework.agent.integration.plugin.AgentDefinitionResourceAdapter;
import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.agent.mapper.AgentEvalCaseMapper;
import com.auraboot.framework.agent.mapper.AgentImMemberInfoMapper;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.plugin.dto.imports.AgentDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.CapabilityEvalCase;
import com.auraboot.framework.plugin.dto.imports.ImportRequest.ConflictStrategy;
import com.auraboot.framework.plugin.dto.imports.ResourceAction;
import com.auraboot.framework.plugin.exception.PluginException;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.auraboot.framework.plugin.service.AgentDefinitionResourceLifecycle;
import com.auraboot.framework.test.util.OwnedTestDatabase;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;

/** Real mapper/JDBC transactions against the task-owned migrated database; no persistence mocks. */
class AgentConsumerBoundaryPostgresIT {
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private AgentDefinitionResourceLifecycle resources;
    private PluginResourceMapper ledger;
    private AgentImDirectory directory;
    private com.auraboot.framework.im.service.impl.ImConversationServiceImpl conversations;
    private com.auraboot.framework.im.mapper.ImConversationMemberMapper membership;
    private SqlSessionTemplate sql;
    private long tenant;

    @BeforeEach
    void connectOwnedDatabase() throws Exception {
        String url = System.getenv("TEST_DATABASE_URL");
        OwnedTestDatabase.require(url, OwnedTestDatabase.AURABOOT_SLOT_PATTERN, "An owned migrated slot is required");
        var source = new DriverManagerDataSource(url, System.getenv("TEST_DATABASE_USERNAME"), System.getenv("TEST_DATABASE_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(config);
        factory.setTransactionFactory(new SpringManagedTransactionFactory());
        var sessions = factory.getObject();
        for (var mapper : List.of(AgentDefinitionMapper.class, AgentEvalCaseMapper.class,
                PluginResourceMapper.class, AgentImMemberInfoMapper.class, com.auraboot.framework.im.mapper.ImConversationMemberMapper.class)) {
            sessions.getConfiguration().addMapper(mapper);
        }
        var template = new SqlSessionTemplate(sessions);
        sql = template;
        ledger = template.getMapper(PluginResourceMapper.class);
        var agents = template.getMapper(AgentDefinitionMapper.class);
        resources = new AgentDefinitionResourceAdapter(agents, template.getMapper(AgentEvalCaseMapper.class),
                ledger, jdbc, new ObjectMapper());
        directory = new AgentImDirectory(agents, template.getMapper(AgentImMemberInfoMapper.class));
        membership = template.getMapper(com.auraboot.framework.im.mapper.ImConversationMemberMapper.class);
        conversations = new com.auraboot.framework.im.service.impl.ImConversationServiceImpl(null, membership, null, null, directory);
        tenant = ThreadLocalRandom.current().nextLong(100_000_000L, 900_000_000L);
    }

    @Test
    void importsOverwriteEvalCasesAndPreserveConflictArchiveRestoreAndTenantIsolation() {
        transactions.executeWithoutResult(tx -> {
            var definition = definition("boundary_" + UlidGenerator.generate(), "Original", "case_a");
            var created = resources.importAgentDefinition(definition, "boundary-plugin", "first", tenant, ConflictStrategy.OVERWRITE);
            assertThat(created.getActionEnum()).isEqualTo(ResourceAction.CREATE);
            ledger.insert(created);
            var other = resources.importAgentDefinition(definition, "other-plugin", "other", tenant + 1, ConflictStrategy.OVERWRITE);
            assertThat(directory.findById(created.getResourceId()).name()).isEqualTo("Original");
            assertThat(caseIds(definition.getAgentCode())).containsExactly("case_a");
            definition.setName("Updated");
            definition.setEvalCases(List.of(eval("case_b")));
            assertThatThrownBy(() -> resources.importAgentDefinition(definition, "boundary-plugin", "error", tenant, ConflictStrategy.ERROR))
                    .isInstanceOf(PluginException.class).hasMessageContaining("already exists");
            assertThat(resources.importAgentDefinition(definition, "boundary-plugin", "skip", tenant, ConflictStrategy.SKIP).getActionEnum())
                    .isEqualTo(ResourceAction.SKIP);
            assertThat(directory.findById(created.getResourceId()).name()).isEqualTo("Original");
            assertThat(caseIds(definition.getAgentCode())).containsExactly("case_a");
            jdbc.update("UPDATE ab_plugin_resource SET user_modified=TRUE WHERE pid=?", created.getPid());
            assertThat(resources.importAgentDefinition(definition, "boundary-plugin", "safe", tenant, ConflictStrategy.OVERWRITE_SAFE).getActionEnum())
                    .isEqualTo(ResourceAction.SKIP);
            var updated = resources.importAgentDefinition(definition, "boundary-plugin", "update", tenant, ConflictStrategy.OVERWRITE);
            assertThat(updated.getActionEnum()).isEqualTo(ResourceAction.UPDATE);
            assertThat(updated.getResourceId()).isEqualTo(created.getResourceId());
            assertThat(directory.findById(created.getResourceId()).name()).isEqualTo("Updated");
            assertThat(caseIds(definition.getAgentCode())).containsExactly("case_b");
            assertThat(jdbc.queryForObject("SELECT allowed_operations::text FROM ab_agent_definition WHERE id=?", String.class,
                    created.getResourceId())).contains("transition");
            resources.rollback(updated);
            sql.clearCache(); // Read the new DB state as a subsequent request would, after direct lifecycle SQL.
            assertThat(resources.exists(tenant, definition.getAgentCode())).isFalse();
            assertThat(caseIds(definition.getAgentCode())).isEmpty();
            resources.restore(updated);
            sql.clearCache();
            assertThat(resources.exists(tenant, definition.getAgentCode())).isTrue();
            assertThat(caseIds(definition.getAgentCode())).containsExactly("case_b");
            assertThat(directory.findById(other.getResourceId()).name()).isEqualTo("Original");
            tx.setRollbackOnly();
        });
    }

    @Test
    void invalidEvalRollsBackThePrecedingDefinitionWriteInTheCallerTransaction() {
        var definition = definition("invalid_" + UlidGenerator.generate(), "Never committed", "valid_case");
        definition.setEvalCases(List.of(CapabilityEvalCase.builder().caseId("").build()));
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx ->
                resources.importAgentDefinition(definition, "boundary-plugin", "invalid", tenant, ConflictStrategy.OVERWRITE)))
                .isInstanceOf(PluginException.class).hasMessageContaining("Invalid eval cases");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_agent_definition WHERE tenant_id=? AND agent_code=?",
                Long.class, tenant, definition.getAgentCode())).isZero();
        assertThat(caseIds(definition.getAgentCode())).isEmpty();
    }

    @Test
    void memberProjectionKeepsOrderingHumanNamesAgentDisplayTenantAndTombstoneRules() {
        transactions.executeWithoutResult(tx -> {
            var created = resources.importAgentDefinition(definition("member_" + UlidGenerator.generate(), "Agent colleague", "member_case"),
                    "boundary-plugin", "member", tenant, ConflictStrategy.OVERWRITE);
            long human = tenant + 2;
            long conversation = tenant + 3;
            jdbc.update("INSERT INTO ab_user(id,pid,user_name,nick_name) VALUES (?,?,?,?)", human, UlidGenerator.generate(), "login", "Human colleague");
            jdbc.update("INSERT INTO ab_im_conversation_member(conversation_id,member_type,member_id,tenant_id,role,joined_at) VALUES (?,'human',?,?,'owner',NOW()-INTERVAL '1 minute')",
                    conversation, human, tenant);
            jdbc.update("INSERT INTO ab_im_conversation_member(conversation_id,member_type,member_id,tenant_id) VALUES (?,'agent',?,?)",
                    conversation, created.getResourceId(), tenant);
            var members = conversations.getMembers(conversation, tenant);
            assertThat(members).extracting(m -> m.getDisplayName()).containsExactly("Human colleague", "Agent colleague");
            assertThat(members.get(1).getAgentCode()).isEqualTo(created.getResourceCode());
            assertThat(conversations.getMembers(conversation, tenant + 1)).isEmpty();
            resources.rollback(created);
            sql.clearCache();
            var archived = conversations.getMembers(conversation, tenant);
            assertThat(archived).hasSize(2);
            assertThat(archived.get(0).getDisplayName()).isEqualTo("Human colleague");
            assertThat(archived.get(1).getDisplayName()).isNull();
            resources.restore(created);
            sql.clearCache();
            assertThat(conversations.getMembers(conversation, tenant).get(1).getDisplayName()).isEqualTo("Agent colleague");
            tx.setRollbackOnly();
        });
    }

    @Test
    void coreMembershipWorksInSchemaWithNoAgentTableOrAdapter() {
        transactions.executeWithoutResult(tx -> {
            jdbc.execute("CREATE TEMP TABLE ab_user (LIKE public.ab_user INCLUDING DEFAULTS) ON COMMIT DROP");
            jdbc.execute("CREATE TEMP TABLE ab_im_conversation_member (LIKE public.ab_im_conversation_member INCLUDING DEFAULTS) ON COMMIT DROP");
            jdbc.execute("SET LOCAL search_path TO pg_temp");
            assertThat(jdbc.queryForObject("SELECT to_regclass('ab_agent_definition') IS NULL", Boolean.class)).isTrue();
            jdbc.update("INSERT INTO ab_user(id,pid,user_name,nick_name) VALUES (?,?,?,?)",
                    tenant + 1, UlidGenerator.generate(), "login", "Human without AI");
            jdbc.update("INSERT INTO ab_im_conversation_member(conversation_id,member_type,member_id,tenant_id,role,joined_at) VALUES (?,'human',?,?,'owner',NOW()-INTERVAL '1 minute')",
                    tenant + 2, tenant + 1, tenant);
            jdbc.update("INSERT INTO ab_im_conversation_member(conversation_id,member_type,member_id,tenant_id) VALUES (?,'agent',?,?)",
                    tenant + 2, tenant + 3, tenant);
            var base = new com.auraboot.framework.im.service.impl.ImConversationServiceImpl(null, membership, null, null, null);
            var members = base.getMembers(tenant + 2, tenant);
            assertThat(members).extracting(m -> m.getMemberId()).containsExactly(tenant + 1, tenant + 3);
            assertThat(members.getFirst().getDisplayName()).isEqualTo("Human without AI");
            assertThat(members.getFirst().getUserId()).isEqualTo(tenant + 1);
            assertThat(members.getFirst().getRole()).isEqualTo("owner");
            assertThat(members.getFirst().getAgentAvailable()).isNull();
            assertThat(members.get(1).getMemberType()).isEqualTo("agent");
            assertThat(members.get(1).getAgentAvailable()).isFalse();
            assertThat(base.getMembers(tenant + 2, tenant + 99)).isEmpty();
            tx.setRollbackOnly();
        });
    }

    private List<String> caseIds(String code) {
        return jdbc.queryForList("SELECT case_id FROM ab_agent_eval_case WHERE tenant_id=? AND agent_code=? AND deleted_flag=FALSE ORDER BY case_id",
                String.class, tenant, code);
    }

    private static AgentDefinitionDTO definition(String code, String name, String caseId) {
        return AgentDefinitionDTO.builder().agentCode(code).name(name).systemPrompt("Preserve this prompt")
                .tools(List.of("tool:query")).evalCases(List.of(eval(caseId))).build();
    }

    private static CapabilityEvalCase eval(String caseId) {
        return CapabilityEvalCase.builder().caseId(caseId).category("query").taskDescription("Query the boundary test record")
                .expectedToolCodes(List.of("tool:query")).forbiddenToolCodes(List.of("tool:delete"))
                .expectedInputKeys(Map.of("recordId", "fixture")).expectedRiskLevel("low").expectsConfirmation(false).build();
    }
}
