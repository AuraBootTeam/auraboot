package com.auraboot.framework.auth.service.impl;

import com.auraboot.framework.auth.dto.ChannelUpdateRequest;
import com.auraboot.framework.auth.entity.TenantLoginChannel;
import com.auraboot.framework.auth.mapper.TenantLoginChannelMapper;
import com.auraboot.framework.auth.service.TenantLoginChannelService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Implementation of {@link TenantLoginChannelService}.
 *
 * @since 7.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantLoginChannelServiceImpl implements TenantLoginChannelService {

    /** Default channels created for every new tenant */
    private static final List<String> DEFAULT_CHANNELS = List.of(
            "email_password", "sms", "email_code"
    );

    private final TenantLoginChannelMapper channelMapper;

    @Override
    public List<String> getEnabledChannels(Long tenantId) {
        if (tenantId == null) {
            // No tenant context (login page, pre-auth): return union of all
            // enabled channels across all tenants so the login page shows
            // every available login method. Table is in interceptor ignore list.
            QueryWrapper<TenantLoginChannel> qw = new QueryWrapper<>();
            qw.eq("enabled", true)
              .orderByAsc("sort_order");

            List<TenantLoginChannel> channels = channelMapper.selectList(qw);
            if (channels.isEmpty()) {
                return List.of("email_password");
            }
            return channels.stream()
                    .map(TenantLoginChannel::getChannel)
                    .distinct()
                    .collect(Collectors.toList());
        }

        QueryWrapper<TenantLoginChannel> qw = new QueryWrapper<>();
        qw.eq("tenant_id", tenantId)
          .eq("enabled", true)
          .orderByAsc("sort_order");

        List<TenantLoginChannel> channels = channelMapper.selectList(qw);

        if (channels.isEmpty()) {
            // Tenant has no channel config yet: return default
            return List.of("email_password");
        }

        return channels.stream()
                .map(TenantLoginChannel::getChannel)
                .collect(Collectors.toList());
    }

    @Override
    public List<TenantLoginChannel> listChannels(Long tenantId) {
        QueryWrapper<TenantLoginChannel> qw = new QueryWrapper<>();
        qw.eq("tenant_id", tenantId)
          .orderByAsc("sort_order");
        return channelMapper.selectList(qw);
    }

    @Override
    @Transactional
    public void updateChannels(Long tenantId, List<ChannelUpdateRequest> updates) {
        for (ChannelUpdateRequest update : updates) {
            QueryWrapper<TenantLoginChannel> qw = new QueryWrapper<>();
            qw.eq("tenant_id", tenantId)
              .eq("channel", update.getChannel());

            TenantLoginChannel existing = channelMapper.selectOne(qw);
            if (existing != null) {
                if (update.getEnabled() != null) {
                    existing.setEnabled(update.getEnabled());
                }
                if (update.getSortOrder() != null) {
                    existing.setSortOrder(update.getSortOrder());
                }
                channelMapper.updateById(existing);
            } else {
                // Create new channel record if it doesn't exist
                TenantLoginChannel channel = new TenantLoginChannel();
                channel.setTenantId(tenantId);
                channel.setChannel(update.getChannel());
                channel.setEnabled(update.getEnabled() != null ? update.getEnabled() : false);
                channel.setSortOrder(update.getSortOrder() != null ? update.getSortOrder() : 99);
                channelMapper.insert(channel);
            }
        }
        log.info("Updated {} login channels for tenant {}", updates.size(), tenantId);
    }

    @Override
    @Transactional
    public void initDefaultChannels(Long tenantId) {
        // Check if channels already exist for this tenant
        QueryWrapper<TenantLoginChannel> qw = new QueryWrapper<>();
        qw.eq("tenant_id", tenantId);
        Long count = channelMapper.selectCount(qw);

        if (count > 0) {
            log.info("Tenant {} already has {} login channels, skipping init", tenantId, count);
            return;
        }

        int sortOrder = 0;
        for (String channelCode : DEFAULT_CHANNELS) {
            TenantLoginChannel channel = new TenantLoginChannel();
            channel.setTenantId(tenantId);
            channel.setChannel(channelCode);
            // Only EMAIL_PASSWORD is enabled by default
            channel.setEnabled("email_password".equals(channelCode));
            channel.setSortOrder(sortOrder++);
            channelMapper.insert(channel);
        }

        log.info("Initialized default login channels for tenant {}", tenantId);
    }
}
