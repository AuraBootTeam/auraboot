package com.auraboot.framework.notification.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.notification.channel.NotificationChannel;
import com.auraboot.framework.notification.channel.NotificationMessage;
import com.auraboot.framework.notification.channel.NotificationResult;
import com.auraboot.framework.notification.dto.NotificationRecipient;
import com.auraboot.framework.notification.dto.NotificationSendRequest;
import com.auraboot.framework.notification.entity.NotificationSendLog;
import com.auraboot.framework.notification.entity.NotificationTemplate;
import com.auraboot.framework.notification.mapper.NotificationSendLogMapper;
import com.auraboot.framework.notification.service.NotificationService;
import com.auraboot.framework.notification.service.NotificationTemplateService;
import com.auraboot.framework.notification.service.EmailSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Implementation of NotificationService.
 * Routes notifications to the appropriate channel via the {@link NotificationChannel} SPI.
 *
 * @since 5.1.0
 */
@Slf4j
@Service
public class NotificationServiceImpl implements NotificationService {

    private final NotificationTemplateService templateService;
    private final NotificationSendLogMapper sendLogMapper;
    private final EmailSender emailSender;
    private final Map<String, NotificationChannel> channelMap;

    public NotificationServiceImpl(
            NotificationTemplateService templateService,
            NotificationSendLogMapper sendLogMapper,
            EmailSender emailSender,
            List<NotificationChannel> channels) {
        this.templateService = templateService;
        this.sendLogMapper = sendLogMapper;
        this.emailSender = emailSender;
        this.channelMap = channels.stream()
                .collect(Collectors.toMap(NotificationChannel::getChannelCode, c -> c));
        log.info("NotificationService initialized with {} channels: {}",
                channelMap.size(), channelMap.keySet());
    }

    @Override
    @Transactional
    public void send(NotificationSendRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        NotificationTemplate template = templateService.getByCode(request.getTemplateCode());
        if (template == null) {
            log.warn("Notification template not found: {}, skipping", request.getTemplateCode());
            return;
        }

        String renderedSubject = renderTemplate(template.getSubjectTemplate(), request.getVariables());
        String renderedBody = renderTemplate(template.getBodyTemplate(), request.getVariables());

        String channelCode = template.getChannel();
        if ("email".equals(channelCode)) {
            handleEmailSend(tenantId, request, renderedSubject, renderedBody);
            return;
        }

        NotificationChannel channel = channelMap.get(channelCode);

        if (channel == null) {
            log.warn("No channel implementation for: {}", channelCode);
            logSend(tenantId, request.getTemplateCode(), channelCode,
                    request.getRecipientId(), renderedSubject, renderedBody,
                    "failed", "No channel implementation available");
            return;
        }
        if (!channel.isAvailable()) {
            log.warn("Channel {} is not available (not configured)", channelCode);
            logSend(tenantId, request.getTemplateCode(), channelCode,
                    request.getRecipientId(), renderedSubject, renderedBody,
                    "failed", "Channel is not available");
            return;
        }

        // recipientId may be a numeric userId (for IN_APP) or an email (for EMAIL)
        List<Long> recipientUserIds;
        try {
            recipientUserIds = List.of(Long.parseLong(request.getRecipientId()));
        } catch (NumberFormatException e) {
            recipientUserIds = List.of();
        }

        NotificationMessage message = NotificationMessage.builder()
                .tenantId(tenantId)
                .recipientUserIds(recipientUserIds)
                .templateCode(request.getTemplateCode())
                .subject(renderedSubject)
                .body(renderedBody)
                .category("system")
                .sourceType(request.getSourceType())
                .sourceId(request.getSourceId())
                .extras(Map.of("email", request.getRecipientId()))
                .build();

        NotificationResult result = channel.send(message);

        logSend(tenantId, request.getTemplateCode(), channelCode,
                request.getRecipientId(), renderedSubject, renderedBody,
                result.isSuccess() ? "sent" : "failed",
                result.getErrorMessage());
    }

    @Override
    @Transactional
    public void sendBatch(String templateCode, List<NotificationRecipient> recipients,
                          Map<String, Object> variables) {
        Long tenantId = MetaContext.getCurrentTenantId();
        NotificationTemplate template = templateService.getByCode(templateCode);
        if (template == null) {
            log.warn("Notification template not found: {}, skipping batch send", templateCode);
            return;
        }

        String renderedSubject = renderTemplate(template.getSubjectTemplate(), variables);
        String renderedBody = renderTemplate(template.getBodyTemplate(), variables);

        String channelCode = template.getChannel();
        if ("email".equals(channelCode)) {
            for (NotificationRecipient recipient : recipients) {
                String email = recipient.getEmail();
                if (email == null || email.isBlank()) {
                    logSend(tenantId, templateCode, "email", email,
                            renderedSubject, renderedBody, "failed", "No email address available");
                    continue;
                }
                emailSender.send(email, renderedSubject, renderedBody);
                logSend(tenantId, templateCode, "email", email,
                        renderedSubject, renderedBody, "sent", null);
            }
            return;
        }

        NotificationChannel channel = channelMap.get(channelCode);

        if (channel == null) {
            log.warn("No channel implementation for: {}", channelCode);
            return;
        }
        if (!channel.isAvailable()) {
            log.warn("Channel {} is not available (not configured)", channelCode);
            return;
        }

        for (NotificationRecipient recipient : recipients) {
            switch (channelCode) {
                case "in_app":
                    if (recipient.getUserId() != null) {
                        NotificationMessage inAppMsg = NotificationMessage.builder()
                                .tenantId(tenantId)
                                .recipientUserIds(List.of(recipient.getUserId()))
                                .templateCode(templateCode)
                                .subject(renderedSubject)
                                .body(renderedBody)
                                .category("system")
                                .build();
                        channel.send(inAppMsg);
                    }
                    break;
                case "email":
                    if (recipient.getEmail() != null) {
                        NotificationMessage emailMsg = NotificationMessage.builder()
                                .tenantId(tenantId)
                                .recipientUserIds(recipient.getUserId() != null
                                        ? List.of(recipient.getUserId()) : List.of())
                                .templateCode(templateCode)
                                .subject(renderedSubject)
                                .body(renderedBody)
                                .extras(Map.of("email", recipient.getEmail()))
                                .build();
                        channel.send(emailMsg);
                    }
                    break;
                default:
                    // Webhook-based channels: send once per recipient
                    NotificationMessage genericMsg = NotificationMessage.builder()
                            .tenantId(tenantId)
                            .recipientUserIds(recipient.getUserId() != null
                                    ? List.of(recipient.getUserId()) : List.of())
                            .templateCode(templateCode)
                            .subject(renderedSubject)
                            .body(renderedBody)
                            .build();
                    channel.send(genericMsg);
                    break;
            }
        }
    }

    private void handleEmailSend(Long tenantId, NotificationSendRequest request,
                                 String renderedSubject, String renderedBody) {
        String recipient = request.getRecipientId();
        if (recipient == null || recipient.isBlank()) {
            logSend(tenantId, request.getTemplateCode(), "email", recipient,
                    renderedSubject, renderedBody, "failed", "No email address available");
            return;
        }

        emailSender.send(recipient, renderedSubject, renderedBody);
        logSend(tenantId, request.getTemplateCode(), "email", recipient,
                renderedSubject, renderedBody, "sent", null);
    }

    @Override
    @Transactional
    public void sendInApp(Long userId, String title, String content,
                          String category, String sourceType, String sourceId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        NotificationChannel inApp = channelMap.get("in_app");
        if (inApp == null) {
            log.error("InAppChannel not registered");
            return;
        }

        inApp.send(NotificationMessage.builder()
                .tenantId(tenantId)
                .recipientUserIds(List.of(userId))
                .subject(title)
                .body(content)
                .category(category)
                .sourceType(sourceType)
                .sourceId(sourceId)
                .build());
    }

    private void logSend(Long tenantId, String templateCode, String channel, String recipient,
                         String subject, String content, String status, String errorMessage) {
        NotificationSendLog logEntry = new NotificationSendLog();
        logEntry.setTenantId(tenantId);
        logEntry.setTemplateCode(templateCode);
        logEntry.setChannel(channel);
        logEntry.setRecipient(recipient);
        logEntry.setSubject(subject);
        logEntry.setContent(content);
        logEntry.setStatus(status);
        logEntry.setErrorMessage(errorMessage);
        if ("sent".equals(status)) {
            logEntry.setSentAt(Instant.now());
        }
        sendLogMapper.insert(logEntry);
    }

    private String renderTemplate(String template, Map<String, Object> variables) {
        if (template == null || variables == null) return template;
        String result = template;
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            String value = entry.getValue() != null ? escapeHtml(entry.getValue().toString()) : "";
            result = result.replace("${" + entry.getKey() + "}", value);
        }
        return result;
    }

    private static String escapeHtml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
