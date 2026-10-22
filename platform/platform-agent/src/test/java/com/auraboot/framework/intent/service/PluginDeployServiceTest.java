package com.auraboot.framework.intent.service;

import com.auraboot.framework.intent.dto.PluginDeployResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PluginDeployService} — the service is currently a
 * dev-phase stub that logs configs and returns a summary, so the tests focus
 * on the count-and-summary contract callers depend on.
 */
class PluginDeployServiceTest {

    private PluginDeployService service;

    @BeforeEach
    void setUp() {
        service = new PluginDeployService(new ObjectMapper());
    }

    @Test
    void deploy_emptyConfigs_throws() {
        assertThrows(IllegalArgumentException.class, () -> service.deploy("p1", "P1", null));
        assertThrows(IllegalArgumentException.class, () -> service.deploy("p1", "P1", Map.of()));
    }

    @Test
    void deploy_countsItemsByConfigKey() {
        Map<String, Object> configs = new LinkedHashMap<>();
        configs.put("models.json", List.of(Map.of("code", "m1"), Map.of("code", "m2")));
        configs.put("fields.json", List.of(Map.of("code", "f1")));
        configs.put("commands.json", List.of(Map.of("code", "c1"), Map.of("code", "c2"), Map.of("code", "c3")));
        configs.put("pages.json", List.of());
        configs.put("menus.json", List.of(Map.of("code", "menu1")));

        PluginDeployResult result = service.deploy("demo", "Demo Plugin", configs);

        assertTrue(result.isSuccess());
        assertEquals("demo", result.getPluginCode());
        assertEquals(2, result.getModelsCreated());
        assertEquals(1, result.getFieldsCreated());
        assertEquals(3, result.getCommandsCreated());
        assertEquals(0, result.getPagesCreated());
        assertEquals(1, result.getMenusCreated());
        assertNotNull(result.getMessage());
        assertTrue(result.getMessage().contains("Demo Plugin"));
    }

    @Test
    void deploy_nonListValueIsIgnoredForCounts() {
        Map<String, Object> configs = new LinkedHashMap<>();
        configs.put("models.json", "not a list");
        configs.put("fields.json", List.of(Map.of("code", "f1")));

        PluginDeployResult result = service.deploy("p2", "P2", configs);

        assertEquals(0, result.getModelsCreated());
        assertEquals(1, result.getFieldsCreated());
    }

    @Test
    void deploy_serializationFailureIsLoggedAndContinues() {
        // An object that ObjectMapper cannot serialize — e.g. a self-referencing one.
        // We use a Map with a value that triggers JsonProcessingException via a custom
        // self-referencing structure.
        Map<String, Object> configs = new LinkedHashMap<>();
        Map<String, Object> selfRef = new LinkedHashMap<>();
        selfRef.put("self", selfRef);
        configs.put("loop.json", selfRef);
        configs.put("menus.json", List.of(Map.of("code", "m")));

        PluginDeployResult result = service.deploy("p3", "P3", configs);

        // Even when one entry fails to serialize the deploy still succeeds.
        assertTrue(result.isSuccess());
        assertEquals(1, result.getMenusCreated());
    }
}
