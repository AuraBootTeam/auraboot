package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CommandDefinitionMapperPluginPidTest extends BaseIntegrationTest {

    @Autowired
    private CommandDefinitionMapper commandDefinitionMapper;

    @Test
    void insertIdempotent_preservesPluginPid() {
        String pluginPid = UniqueIdGenerator.generate();
        CommandDefinition command = createCommand(pluginPid);

        int inserted = commandDefinitionMapper.insertIdempotent(command);
        CommandDefinition found = commandDefinitionMapper.findByPid(command.getPid());

        assertThat(inserted).isEqualTo(1);
        assertThat(found).isNotNull();
        assertThat(found.getPluginPid()).isEqualTo(pluginPid);
    }

    private CommandDefinition createCommand(String pluginPid) {
        String suffix = UniqueIdGenerator.generate();
        CommandDefinition command = new CommandDefinition();
        command.setPid(suffix);
        command.setTenantId(getTestTenant().getId());
        command.setCode("test:plugin_pid_" + suffix);
        command.setDisplayName("Plugin PID mapper test");
        command.setModelCode("test_plugin_pid_model");
        command.setInputSchema("{}");
        command.setTargetModels("[]");
        command.setExecutionConfig("{\"type\":\"create\"}");
        command.setExtension(new ExtensionBean());
        command.setPluginPid(pluginPid);
        command.setVersion(1);
        command.setSemver("1.0.0");
        command.setIsCurrent(true);
        command.setRowVersion(1);
        command.setStatus("published");
        command.setDeletedFlag(false);
        command.setCreatedAt(Instant.now());
        command.setUpdatedAt(Instant.now());
        return command;
    }
}
