package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.BindingRuleMapper;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.meta.service.impl.CommandMetadataCacheService;
import com.auraboot.framework.meta.service.impl.CommandServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CommandService#resolveCrudCommands} — the
 * convention-over-configuration CRUD command resolver. The operation type is
 * extracted from each command's {@code execution_config.type} (via the real
 * {@code toDTO}/JSON parse path), so these tests exercise the same code that
 * the page-schema endpoint relies on.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CommandService.resolveCrudCommands (convention CRUD resolution)")
class CommandServiceResolveCrudCommandsTest {

    @Mock
    private CommandDefinitionMapper commandDefinitionMapper;

    @Mock
    private BindingRuleMapper bindingRuleMapper;

    @Mock
    private CommandMetadataCacheService commandMetadataCache;

    @InjectMocks
    private CommandServiceImpl commandService;

    private static CommandDefinition cmd(String code, String modelCode, String type) {
        CommandDefinition c = new CommandDefinition();
        c.setCode(code);
        c.setModelCode(modelCode);
        c.setExecutionConfig(type == null ? null : "{\"type\":\"" + type + "\"}");
        return c;
    }

    @Test
    @DisplayName("maps create/update/delete by execution_config.type, ignoring query and state_transition")
    void resolvesCrudByType() {
        when(commandDefinitionMapper.findByModelCode("showcase_all_fields")).thenReturn(List.of(
                cmd("sc:list_showcase", "showcase_all_fields", "query"),
                cmd("sc:create_showcase", "showcase_all_fields", "create"),
                cmd("sc:activate_showcase", "showcase_all_fields", "state_transition"),
                cmd("sc:update_showcase", "showcase_all_fields", "update"),
                cmd("sc:delete_showcase", "showcase_all_fields", "delete"),
                cmd("sc:detail_showcase", "showcase_all_fields", "query")));

        Map<String, String> crud = commandService.resolveCrudCommands("showcase_all_fields");

        assertEquals("sc:create_showcase", crud.get("create"));
        assertEquals("sc:update_showcase", crud.get("update"));
        assertEquals("sc:delete_showcase", crud.get("delete"));
        assertFalse(crud.containsKey("query"), "query commands must not be in the CRUD map");
        assertFalse(crud.containsKey("state_transition"), "state_transition must not be in the CRUD map");
        assertEquals(3, crud.size());
    }

    @Test
    @DisplayName("returns an empty map for a blank model code without hitting the mapper")
    void blankModelCodeReturnsEmpty() {
        assertTrue(commandService.resolveCrudCommands("").isEmpty());
        assertTrue(commandService.resolveCrudCommands(null).isEmpty());
    }

    @Test
    @DisplayName("returns an empty map for a pure-CRUD model with no business commands")
    void pureCrudModelReturnsEmpty() {
        when(commandDefinitionMapper.findByModelCode("plain_model")).thenReturn(List.of());
        assertTrue(commandService.resolveCrudCommands("plain_model").isEmpty());
    }

    @Test
    @DisplayName("first command matching each type wins when duplicates exist")
    void firstMatchPerTypeWins() {
        when(commandDefinitionMapper.findByModelCode("m")).thenReturn(List.of(
                cmd("m:create_primary", "m", "create"),
                cmd("m:create_draft", "m", "create")));

        Map<String, String> crud = commandService.resolveCrudCommands("m");

        assertEquals("m:create_primary", crud.get("create"));
        assertEquals(1, crud.size());
    }

    @Test
    @DisplayName("commands with an unparseable/absent type are skipped")
    void absentTypeSkipped() {
        when(commandDefinitionMapper.findByModelCode("m")).thenReturn(List.of(
                cmd("m:mystery", "m", null),
                cmd("m:create_it", "m", "create")));

        Map<String, String> crud = commandService.resolveCrudCommands("m");

        assertEquals("m:create_it", crud.get("create"));
        assertEquals(1, crud.size());
    }
}
