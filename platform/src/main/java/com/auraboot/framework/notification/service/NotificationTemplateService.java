package com.auraboot.framework.notification.service;

import com.auraboot.framework.notification.dto.NotificationTemplateCreateRequest;
import com.auraboot.framework.notification.entity.NotificationTemplate;

import java.util.List;
import java.util.Map;

/**
 * Service for managing notification templates.
 *
 * @since 5.1.0
 */
public interface NotificationTemplateService {

    NotificationTemplate create(NotificationTemplateCreateRequest request);

    NotificationTemplate getByCode(String code);

    List<NotificationTemplate> listByChannel(String channel);

    List<NotificationTemplate> listAll();

    NotificationTemplate update(String pid, NotificationTemplateCreateRequest request);

    void delete(String pid);

    /**
     * Render a template with given variables for preview.
     */
    String renderPreview(String templateCode, Map<String, Object> variables);
}
