package com.auraboot.framework.bpm.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.BpmNotifyRecord;
import com.auraboot.framework.bpm.mapper.BpmNotifyRecordMapper;
import com.auraboot.framework.common.util.UlidGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BpmNotifyService {

    private final BpmNotifyRecordMapper notifyRecordMapper;

    @Transactional
    public void sendCarbonCopy(String taskId, String processInstanceId, Long senderUserId, List<Long> recipientUserIds, String content) {
        Long tenantId = MetaContext.getCurrentTenantId();
        for (Long recipientId : recipientUserIds) {
            BpmNotifyRecord record = BpmNotifyRecord.builder()
                    .pid(UlidGenerator.generate())
                    .tenantId(tenantId)
                    .processInstanceId(processInstanceId)
                    .taskId(taskId)
                    .notifyType("CC")
                    .senderUserId(senderUserId)
                    .recipientUserId(recipientId)
                    .content(content)
                    .isRead(false)
                    .createdAt(Instant.now())
                    .build();
            notifyRecordMapper.insert(record);
            log.info("Carbon copy sent: taskId={}, recipient={}", taskId, recipientId);
        }
    }

    /**
     * Send an urge notification with explicit tenant ID.
     * Use this overload when calling from scheduled tasks or system contexts
     * where MetaContext is not available.
     */
    @Transactional
    public void sendUrge(String taskId, String processInstanceId, Long senderUserId, Long assigneeUserId, String content, Long tenantId) {
        BpmNotifyRecord record = BpmNotifyRecord.builder()
                .pid(UlidGenerator.generate())
                .tenantId(tenantId)
                .processInstanceId(processInstanceId)
                .taskId(taskId)
                .notifyType("urge")
                .senderUserId(senderUserId)
                .recipientUserId(assigneeUserId)
                .content(content)
                .isRead(false)
                .createdAt(Instant.now())
                .build();
        notifyRecordMapper.insert(record);
        log.info("Urge sent: taskId={}, assignee={}", taskId, assigneeUserId);
    }

    /**
     * Send an urge notification using MetaContext for tenant ID.
     */
    @Transactional
    public void sendUrge(String taskId, String processInstanceId, Long senderUserId, Long assigneeUserId, String content) {
        sendUrge(taskId, processInstanceId, senderUserId, assigneeUserId, content, MetaContext.getCurrentTenantId());
    }

    public List<BpmNotifyRecord> getReceivedNotifications(Long userId, String notifyType) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return notifyRecordMapper.findByRecipient(tenantId, userId, notifyType);
    }

    @Transactional
    public void markAsRead(String notifyPid) {
        BpmNotifyRecord record = notifyRecordMapper.findByPid(notifyPid);
        if (record != null) {
            record.setIsRead(true);
            record.setReadAt(Instant.now());
            notifyRecordMapper.updateById(record);
        }
    }
}
