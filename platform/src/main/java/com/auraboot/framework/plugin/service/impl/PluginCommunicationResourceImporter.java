package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.i18n.compiler.I18nCompiler;
import com.auraboot.framework.i18n.entity.I18nResource;
import com.auraboot.framework.i18n.service.I18nResourceService;
import com.auraboot.framework.i18n.service.I18nService;
import com.auraboot.framework.notification.entity.NotificationTemplate;
import com.auraboot.framework.notification.mapper.NotificationTemplateMapper;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.common.util.LogSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.util.*;

/** Owns localized import resources and notification template upserts. */
@Slf4j
@RequiredArgsConstructor
final class PluginCommunicationResourceImporter {
    private final I18nCompiler i18nCompiler;
    private final I18nResourceService i18nResourceService;
    private final I18nService i18nService;
    private final NotificationTemplateMapper notificationTemplateMapper;

    void generateMenuI18nRecords(List<MenuDefinitionDTO> menus, Long tenantId) {
        List<I18nResource> resources = new ArrayList<>();
        for (MenuDefinitionDTO menu : menus) {
            if (menu.getCode() == null || menu.getCode().isBlank()) continue;
            String i18nKey = "menu." + menu.getCode();

            for (Map.Entry<String, String> entry : menu.getAllLocalizedNames().entrySet()) {
                I18nResource res = new I18nResource();
                res.setI18nKey(i18nKey);
                res.setLang(entry.getKey());
                res.setValue(entry.getValue());
                res.setSource(I18nResource.SOURCE_IMPORT);
                res.setRefType("menu");
                res.setStatus(I18nResource.STATUS_APPROVED);
                resources.add(res);
            }
        }

        if (!resources.isEmpty()) {
            int count = i18nResourceService.batchUpsert(resources);
            i18nService.clearCache(null);
            log.info("Auto-generated {} menu i18n records from localized names", count);
        }
    }

    void generatePermissionI18nRecords(List<PermissionDefinitionDTO> permissions, Long tenantId) {
        List<I18nResource> resources = new ArrayList<>();
        for (PermissionDefinitionDTO permission : permissions) {
            if (permission.getCode() == null || permission.getCode().isBlank()) continue;
            String nameKey = "permission." + permission.getCode();
            for (Map.Entry<String, String> entry : permission.getAllLocalizedNames().entrySet()) {
                resources.add(buildImportI18nResource(nameKey, entry.getKey(), entry.getValue(), "permission"));
            }
            String descKey = "permission." + permission.getCode() + ".description";
            for (Map.Entry<String, String> entry : permission.getAllLocalizedDescriptions().entrySet()) {
                resources.add(buildImportI18nResource(descKey, entry.getKey(), entry.getValue(), "permission"));
            }
        }

        if (!resources.isEmpty()) {
            int count = i18nResourceService.batchUpsert(resources);
            i18nService.clearCache(null);
            log.info("Auto-generated {} permission i18n records from localized names/descriptions", count);
        }
    }

    I18nResource buildImportI18nResource(String i18nKey, String lang, String value, String refType) {
        I18nResource res = new I18nResource();
        res.setI18nKey(i18nKey);
        res.setLang(lang);
        res.setValue(value);
        res.setSource(I18nResource.SOURCE_IMPORT);
        res.setRefType(refType);
        res.setStatus(I18nResource.STATUS_APPROVED);
        return res;
    }

    void importI18nResources(PluginManifestExtended manifest, ImportExecuteResult result, Long tenantId) {
        if (manifest.getI18nResources() == null || manifest.getI18nResources().isEmpty()) return;

        List<I18nResource> resources = new ArrayList<>();
        for (I18nDefinitionDTO dto : manifest.getI18nResources()) {
            if (!dto.isValid()) continue;

            for (Map.Entry<String, String> entry : dto.getAllTranslations().entrySet()) {
                I18nResource resource = new I18nResource();
                resource.setI18nKey(dto.getKey());
                resource.setLang(entry.getKey());
                resource.setValue(entry.getValue());
                resource.setSource(dto.getSource() != null ? dto.getSource() : I18nResource.SOURCE_IMPORT);
                resource.setRefType(dto.getRefType());
                resource.setStatus(I18nResource.STATUS_APPROVED);
                resources.add(resource);
            }
        }

        if (!resources.isEmpty()) {
            int count = i18nResourceService.batchUpsert(resources);
            for (int i = 0; i < count; i++) {
                result.incrementResourceCount(ResourceType.I18N, ResourceAction.CREATE);
            }
            log.info("Imported {} i18n resources ({} translations)", manifest.getI18nResources().size(), count);

            // Auto-compile i18n JSON after import
            i18nCompiler.compileAll();
            log.info("i18n compilation completed after plugin import");
        }
    }

    void importNotificationTemplates(PluginManifestExtended manifest, ImportExecuteResult result, Long tenantId) {
        if (manifest.getNotificationTemplates() == null || manifest.getNotificationTemplates().isEmpty()) return;

        for (NotificationTemplateDefinitionDTO dto : manifest.getNotificationTemplates()) {
            if (!dto.isValid()) {
                log.warn("Skipping invalid notification template: {}", logSafe(dto.getCode()));
                continue;
            }

            NotificationTemplate existing = notificationTemplateMapper.findByCodeForUpsert(tenantId, dto.getCode());
            if (existing != null) {
                existing.setName(dto.getName());
                existing.setChannel(dto.getChannel());
                if (dto.getChannels() != null) existing.setChannels(dto.getChannels());
                if (dto.getCategory() != null) existing.setCategory(dto.getCategory());
                existing.setSubjectTemplate(dto.getSubjectTemplate());
                existing.setBodyTemplate(dto.getBodyTemplate());
                existing.setVariables(dto.getVariables());
                existing.setEnabled(dto.isEnabledOrDefault());
                notificationTemplateMapper.updateById(existing);
                result.incrementResourceCount(ResourceType.NOTIFICATION_TEMPLATE, ResourceAction.UPDATE);
                log.info("Updated notification template: {}", logSafe(dto.getCode()));
            } else {
                NotificationTemplate template = new NotificationTemplate();
                template.setPid(UlidGenerator.generate());
                template.setTenantId(tenantId);
                template.setCode(dto.getCode());
                template.setName(dto.getName());
                template.setChannel(dto.getChannel());
                if (dto.getChannels() != null) template.setChannels(dto.getChannels());
                if (dto.getCategory() != null) template.setCategory(dto.getCategory());
                template.setSubjectTemplate(dto.getSubjectTemplate());
                template.setBodyTemplate(dto.getBodyTemplate());
                template.setVariables(dto.getVariables());
                template.setEnabled(dto.isEnabledOrDefault());
                notificationTemplateMapper.insert(template);
                result.incrementResourceCount(ResourceType.NOTIFICATION_TEMPLATE, ResourceAction.CREATE);
                log.info("Created notification template: {}", logSafe(dto.getCode()));
            }
        }
    }

    private static String logSafe(Object value) {
        return LogSanitizer.safe(value);
    }
}
