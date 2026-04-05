package com.auraboot.framework.integration;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.permission.service.CommandActionDeriver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommandActionDeriverIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private CommandActionDeriver commandActionDeriver;

    @Autowired
    private CommandDefinitionMapper commandDefinitionMapper;

    private String testModelCode;

    @BeforeEach
    void setupCommands() {
        testModelCode = "test_deriver_" + System.currentTimeMillis();

        insertCommand("test:create_" + testModelCode, testModelCode, "{\"type\": \"create\"}");
        insertCommand("test:update_" + testModelCode, testModelCode, "{\"type\": \"update\"}");
        insertCommand("test:delete_" + testModelCode, testModelCode, "{\"type\": \"delete\"}");
        insertCommand("test:list_" + testModelCode, testModelCode, "{\"type\": \"query\"}");
        insertCommand("test:qualify_" + testModelCode, testModelCode,
                "{\"type\": \"state_transition\", \"fromStates\": [\"new\"], \"toState\": \"qualified\"}");
        insertCommand("test:convert_" + testModelCode, testModelCode,
                "{\"type\": \"state_transition\", \"fromStates\": [\"qualified\"], \"toState\": \"converted\"}");
    }

    @Test
    void deriveActions_withCrudModel_returnsStandardActions() {
        List<String> actions = commandActionDeriver.deriveActions(testModelCode);

        // "read" is always present
        assertThat(actions).contains("read");
        // Standard CRUD actions from exec_type
        assertThat(actions).contains("create", "update", "delete");
        // query exec_type is skipped (covered by "read")
        assertThat(actions).doesNotContain("list");
        // State transition verbs extracted from command code
        assertThat(actions).contains("qualify", "convert");
    }

    @Test
    void deriveActions_withNoCommands_returnsReadOnly() {
        List<String> actions = commandActionDeriver.deriveActions("nonexistent_model_xyz_" + System.currentTimeMillis());
        assertThat(actions).containsExactly("read");
    }

    @Test
    void deriveActions_readAlwaysFirst() {
        List<String> actions = commandActionDeriver.deriveActions(testModelCode);
        assertThat(actions.get(0)).isEqualTo("read");
    }

    @Test
    void deriveActions_noDuplicates() {
        List<String> actions = commandActionDeriver.deriveActions(testModelCode);
        assertThat(actions).doesNotHaveDuplicates();
    }

    private void insertCommand(String code, String modelCode, String executionConfig) {
        CommandDefinition cmd = new CommandDefinition();
        cmd.setPid(UniqueIdGenerator.generate());
        cmd.setTenantId(getTestTenant().getId());
        cmd.setCode(code);
        cmd.setDisplayName(code);
        cmd.setModelCode(modelCode);
        cmd.setInputSchema("{}");
        cmd.setTargetModels("[]");
        cmd.setExecutionConfig(executionConfig);
        cmd.setExtension(new ExtensionBean());
        cmd.setVersion(1);
        cmd.setSemver("1.0.0");
        cmd.setIsCurrent(true);
        cmd.setRowVersion(1);
        cmd.setStatus("published");
        cmd.setDeletedFlag(false);
        cmd.setCreatedAt(Instant.now());
        cmd.setUpdatedAt(Instant.now());
        commandDefinitionMapper.insertIdempotent(cmd);
    }
}
