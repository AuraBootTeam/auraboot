package com.auraboot.framework.intent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.auraboot.framework.intent.dto.PluginDeployResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Deploys a generated plugin configuration by persisting models, fields,
 * bindings, commands, pages, menus, i18n, and permissions into the
 * platform's schema/definition tables.
 *
 * In the current dev phase, this stores configuration as JSON and
 * can be extended to call the full plugin import pipeline when available.
 */
@Service
public class PluginDeployService {

    private static final Logger log = LoggerFactory.getLogger(PluginDeployService.class);
    private final ObjectMapper objectMapper;

    public PluginDeployService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Deploy the plugin configs. Currently logs the deployment and returns
     * a summary. In production, this would invoke the plugin import API.
     */
    @SuppressWarnings("unchecked")
    public PluginDeployResult deploy(String pluginCode, String pluginName, Map<String, Object> configs) {
        if (configs == null || configs.isEmpty()) {
            throw new IllegalArgumentException("Plugin configs must not be empty");
        }

        log.info("Deploying plugin: code={}, name={}", pluginCode, pluginName);

        int modelsCreated = countItems(configs, "models.json");
        int fieldsCreated = countItems(configs, "fields.json");
        int commandsCreated = countItems(configs, "commands.json");
        int pagesCreated = countItems(configs, "pages.json");
        int menusCreated = countItems(configs, "menus.json");

        // Log each config for debugging
        configs.forEach((fileName, content) -> {
            try {
                String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(content);
                log.info("Plugin config [{}]:\n{}", fileName, json);
            } catch (Exception e) {
                log.warn("Failed to serialize config {}: {}", fileName, e.getMessage());
            }
        });

        // TODO: Integrate with platform's PluginImportService once available
        // For now, the configs are returned to the frontend for review.
        // The actual import would call: pluginImportService.importPlugin(pluginCode, configs)

        log.info("Plugin '{}' deployment prepared: {} models, {} fields, {} commands, {} pages, {} menus",
                pluginCode, modelsCreated, fieldsCreated, commandsCreated, pagesCreated, menusCreated);

        return PluginDeployResult.builder()
                .success(true)
                .pluginCode(pluginCode)
                .message("Plugin '%s' configuration prepared successfully. %d models, %d fields, %d commands, %d pages, %d menus."
                        .formatted(pluginName, modelsCreated, fieldsCreated, commandsCreated, pagesCreated, menusCreated))
                .modelsCreated(modelsCreated)
                .fieldsCreated(fieldsCreated)
                .commandsCreated(commandsCreated)
                .pagesCreated(pagesCreated)
                .menusCreated(menusCreated)
                .build();
    }

    @SuppressWarnings("unchecked")
    private int countItems(Map<String, Object> configs, String key) {
        Object val = configs.get(key);
        if (val instanceof List) {
            return ((List<?>) val).size();
        }
        return 0;
    }
}
