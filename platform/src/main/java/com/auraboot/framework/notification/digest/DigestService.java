package com.auraboot.framework.notification.digest;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.notification.channel.NotificationChannel;
import com.auraboot.framework.notification.channel.NotificationMessage;
import com.auraboot.framework.notification.mapper.DigestEntryMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Digest service that aggregates same-type notifications within a time window.
 * <p>
 * For non-IN_APP channels (primarily EMAIL), notifications are accumulated into
 * digest entries instead of being sent immediately. When the count reaches
 * the threshold or the time window expires, a single digest notification is sent.
 * <p>
 * This reduces notification fatigue for high-frequency events.
 *
 * @since 6.0.0
 */
@Slf4j
@Service
public class DigestService {

    private final DigestEntryMapper digestMapper;
    private final Map<String, NotificationChannel> channelMap;

    static final int DIGEST_THRESHOLD = 3;
    static final Duration DIGEST_WINDOW = Duration.ofMinutes(5);

    public DigestService(DigestEntryMapper digestMapper, List<NotificationChannel> channels) {
        this.digestMapper = digestMapper;
        this.channelMap = channels.stream()
                .collect(Collectors.toMap(NotificationChannel::getChannelCode, c -> c));
    }

    /**
     * Accumulate a notification into the digest.
     * Finds an existing unflushed entry for this user+channel+template within the window,
     * or creates a new one.
     */
    @Transactional
    public void accumulate(Long tenantId, Long userId, String channel,
                           String templateCode, String category) {
        Instant windowStart = Instant.now().minus(DIGEST_WINDOW);
        DigestEntry existing = findActiveEntry(tenantId, userId, channel, templateCode, windowStart);

        if (existing != null) {
            existing.setCount(existing.getCount() + 1);
            digestMapper.updateById(existing);
        } else {
            DigestEntry entry = new DigestEntry();
            entry.setTenantId(tenantId);
            entry.setUserId(userId);
            entry.setChannel(channel);
            entry.setTemplateCode(templateCode);
            entry.setCategory(category);
            entry.setCount(1);
            entry.setWindowStart(Instant.now());
            entry.setFlushed(false);
            digestMapper.insert(entry);
        }
    }

    /**
     * Scheduled flush: finds entries that are unflushed AND (count >= threshold OR window expired),
     * sends a digest notification via the channel, and marks them as flushed.
     *
     * Uses SELECT FOR UPDATE SKIP LOCKED to ensure only one instance processes each entry
     * in multi-node deployments.
     */
    @Scheduled(fixedRate = 60000)
    @Transactional
    public void flushDigests() {
        Instant cutoff = Instant.now().minus(DIGEST_WINDOW);
        // Row-level lock prevents duplicate processing across nodes
        List<DigestEntry> entries = digestMapper.findFlushableEntriesForUpdate(DIGEST_THRESHOLD, cutoff);

        for (DigestEntry entry : entries) {
            try {
                MetaContext.setContext(entry.getTenantId(), 0L, null, "system");

                // Mark as flushed BEFORE sending to prevent duplicate sends on retry
                entry.setFlushed(true);
                entry.setWindowEnd(Instant.now());
                digestMapper.updateById(entry);

                NotificationChannel channel = channelMap.get(entry.getChannel());
                if (channel != null && channel.isAvailable()) {
                    String subject = String.format("您有 %d 条%s通知待查看",
                            entry.getCount(), getCategoryLabel(entry.getCategory()));
                    String body = String.format(
                            "您在过去 %d 分钟内收到 %d 条%s相关通知，请前往通知中心查看。",
                            DIGEST_WINDOW.toMinutes(), entry.getCount(),
                            getCategoryLabel(entry.getCategory()));

                    channel.send(NotificationMessage.builder()
                            .tenantId(entry.getTenantId())
                            .recipientUserIds(List.of(entry.getUserId()))
                            .subject(subject)
                            .body(body)
                            .category(entry.getCategory())
                            .templateCode(entry.getTemplateCode())
                            .build());
                }
            } catch (Exception e) {
                log.error("Failed to flush digest {}: {}", entry.getId(), e.getMessage());
            } finally {
                MetaContext.clear();
            }
        }
    }

    /**
     * Find an active (unflushed) entry for this user+channel+template within the window.
     * Note: tenant_id is manually added because this table is in the interceptor ignore list
     * (required for scheduled flush without tenant context).
     */
    DigestEntry findActiveEntry(Long tenantId, Long userId, String channel,
                                String templateCode, Instant windowStart) {
        QueryWrapper<DigestEntry> qw = new QueryWrapper<>();
        qw.eq("tenant_id", tenantId)
                .eq("user_id", userId)
                .eq("channel", channel)
                .eq("template_code", templateCode)
                .eq("flushed", false)
                .ge("window_start", windowStart)
                .last("LIMIT 1");
        return digestMapper.selectOne(qw);
    }

    // findFlushableEntries moved to DigestEntryMapper.findFlushableEntriesForUpdate()
    // with SELECT FOR UPDATE SKIP LOCKED for multi-node safety

    String getCategoryLabel(String category) {
        return switch (category.toLowerCase()) {
            case "business" -> "业务";
            case "approval" -> "审批";
            case "system" -> "系统";
            case "alert" -> "告警";
            default -> category;
        };
    }
}
