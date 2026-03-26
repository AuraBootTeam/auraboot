package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.CommandDefinitionDTO;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.dto.MetaModelFieldBindingDTO;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.ModelExportService;
import com.auraboot.framework.meta.service.ModelFieldBindingService;
import com.auraboot.framework.meta.service.PageSchemaService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Model Export Service Implementation
 *
 * @author AuraBoot Team
 * @since 3.1.0
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ModelExportServiceImpl implements ModelExportService {

    private final MetaModelService metaModelService;
    private final ModelFieldBindingService modelFieldBindingService;
    private final CommandService commandService;
    private final PageSchemaService pageSchemaService;
    private final ObjectMapper objectMapper;

    @Override
    public Map<String, Object> exportByModelCodes(List<String> modelCodes) {
        List<Map<String, Object>> models = new ArrayList<>();
        List<Map<String, Object>> fields = new ArrayList<>();
        List<Map<String, Object>> bindings = new ArrayList<>();
        List<Map<String, Object>> commands = new ArrayList<>();
        List<Map<String, Object>> pages = new ArrayList<>();

        for (String modelCode : modelCodes) {
            MetaModelDTO model = metaModelService.findByCode(modelCode);
            if (model == null) {
                log.warn("Model not found for code: {}", modelCode);
                continue;
            }

            models.add(exportModel(model));
            fields.addAll(exportFields(model.getPid()));
            bindings.addAll(exportBindings(model.getPid()));
            commands.addAll(exportCommands(modelCode));
            pages.addAll(exportPages(modelCode));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("models", models);
        result.put("fields", fields);
        result.put("bindings", bindings);
        result.put("commands", commands);
        result.put("pages", pages);
        return result;
    }

    private Map<String, Object> exportModel(MetaModelDTO model) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", model.getCode());
        map.put("displayName", model.getDisplayName());
        map.put("description", model.getDescription());
        map.put("modelType", model.getModelType());
        map.put("extension", model.getExtension());
        return map;
    }

    private List<Map<String, Object>> exportFields(String modelPid) {
        List<MetaFieldDTO> fieldDTOs = modelFieldBindingService.getModelFields(modelPid);
        List<Map<String, Object>> result = new ArrayList<>();
        for (MetaFieldDTO field : fieldDTOs) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("code", field.getCode());
            map.put("dataType", field.getDataType());
            map.put("extension", field.getExtension());
            result.add(map);
        }
        return result;
    }

    private List<Map<String, Object>> exportBindings(String modelPid) {
        List<MetaModelFieldBindingDTO> bindingDTOs = modelFieldBindingService.getModelBindings(modelPid);
        List<Map<String, Object>> result = new ArrayList<>();
        for (MetaModelFieldBindingDTO binding : bindingDTOs) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("modelCode", binding.getModelCode());
            map.put("fieldCode", binding.getCode());
            map.put("sequence", binding.getFieldOrder());
            map.put("required", binding.getRequired());
            map.put("visible", binding.getVisible());
            result.add(map);
        }
        return result;
    }

    private List<Map<String, Object>> exportCommands(String modelCode) {
        List<CommandDefinitionDTO> commandDTOs = commandService.listByModelCode(modelCode);
        List<Map<String, Object>> result = new ArrayList<>();
        for (CommandDefinitionDTO cmd : commandDTOs) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("code", cmd.getCode());
            map.put("displayName", cmd.getDisplayName());
            map.put("modelCode", cmd.getModelCode());

            // Parse executionConfig JSON to extract type, inputFields, autoSetFields
            Map<String, Object> execConfig = parseExecutionConfig(cmd.getExecutionConfig());
            Object type = execConfig.get("type");

            //todo remove Locale.ROOT toLowerCase
            map.put("type", type != null ? type.toString().toLowerCase(Locale.ROOT) : null);
            map.put("inputFields", execConfig.get("inputFields"));
            map.put("autoSetFields", execConfig.get("autoSetFields"));

            result.add(map);
        }
        return result;
    }

    private List<Map<String, Object>> exportPages(String modelCode) {
        List<PageSchemaDTO> pageDTOs = pageSchemaService.findByModelCode(modelCode);
        List<Map<String, Object>> result = new ArrayList<>();
        for (PageSchemaDTO page : pageDTOs) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("code", page.getPageKey());
            map.put("modelCode", page.getModelCode());
            map.put("pageType", page.getPageType());
            map.put("schema", page.getDslSchema());
            result.add(map);
        }
        return result;
    }

    private Map<String, Object> parseExecutionConfig(String executionConfigJson) {
        if (executionConfigJson == null || executionConfigJson.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(executionConfigJson,
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse executionConfig: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }
}
