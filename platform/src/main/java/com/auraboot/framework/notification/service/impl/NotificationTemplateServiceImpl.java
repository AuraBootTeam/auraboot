package com.auraboot.framework.notification.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.notification.dto.NotificationTemplateCreateRequest;
import com.auraboot.framework.notification.entity.NotificationTemplate;
import com.auraboot.framework.notification.mapper.NotificationTemplateMapper;
import com.auraboot.framework.notification.service.NotificationTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implementation of NotificationTemplateService.
 *
 * @since 5.1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationTemplateServiceImpl implements NotificationTemplateService {

    private final NotificationTemplateMapper templateMapper;

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{(\\w+)}");

    @Override
    @Transactional
    public NotificationTemplate create(NotificationTemplateCreateRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();

        NotificationTemplate entity = new NotificationTemplate();
        entity.setTenantId(tenantId);
        entity.setPid(UniqueIdGenerator.generate());
        entity.setCode(request.getCode());
        entity.setName(request.getName());
        entity.setChannel(request.getChannel());
        entity.setSubjectTemplate(request.getSubjectTemplate());
        entity.setBodyTemplate(request.getBodyTemplate());
        entity.setVariables(request.getVariables());
        entity.setEnabled(request.isEnabled());

        templateMapper.insert(entity);
        log.info("Created notification template: code={}, channel={}", request.getCode(), request.getChannel());
        return entity;
    }

    @Override
    public NotificationTemplate getByCode(String code) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return templateMapper.findByCode(tenantId, code);
    }

    @Override
    public List<NotificationTemplate> listByChannel(String channel) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return templateMapper.findByChannel(tenantId, channel);
    }

    @Override
    public List<NotificationTemplate> listAll() {
        Long tenantId = MetaContext.getCurrentTenantId();
        return templateMapper.findAll(tenantId);
    }

    @Override
    @Transactional
    public NotificationTemplate update(String pid, NotificationTemplateCreateRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        NotificationTemplate existing = templateMapper.findByPid(tenantId, pid);
        if (existing == null) {
            throw new IllegalArgumentException("Notification template not found: " + pid);
        }

        existing.setCode(request.getCode());
        existing.setName(request.getName());
        existing.setChannel(request.getChannel());
        existing.setSubjectTemplate(request.getSubjectTemplate());
        existing.setBodyTemplate(request.getBodyTemplate());
        existing.setVariables(request.getVariables());
        existing.setEnabled(request.isEnabled());

        templateMapper.updateById(existing);
        log.info("Updated notification template: pid={}", pid);
        return existing;
    }

    @Override
    @Transactional
    public void delete(String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        templateMapper.deleteByPid(tenantId, pid);
        log.info("Deleted notification template: pid={}", pid);
    }

    @Override
    public String renderPreview(String templateCode, Map<String, Object> variables) {
        NotificationTemplate template = getByCode(templateCode);
        if (template == null) {
            throw new IllegalArgumentException("Template not found: " + templateCode);
        }
        return renderTemplate(template.getBodyTemplate(), variables);
    }

    /**
     * Simple variable substitution: replaces ${varName} with values.
     */
    String renderTemplate(String template, Map<String, Object> variables) {
        if (template == null || variables == null || variables.isEmpty()) {
            return template;
        }
        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(1);
            Object value = variables.get(varName);
            matcher.appendReplacement(sb, value != null ? Matcher.quoteReplacement(value.toString()) : "");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
