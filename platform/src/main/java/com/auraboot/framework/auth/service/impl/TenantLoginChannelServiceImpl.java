package com.auraboot.framework.auth.service.impl;

import com.auraboot.framework.auth.dto.ChannelUpdateRequest;
import com.auraboot.framework.auth.dto.LoginChannelOption;
import com.auraboot.framework.auth.entity.TenantLoginChannel;
import com.auraboot.framework.auth.mapper.TenantLoginChannelMapper;
import com.auraboot.framework.auth.mapper.LoginApplicationChannelMapper;
import com.auraboot.framework.auth.service.TenantLoginChannelService;
import com.auraboot.framework.saas.config.service.SystemModeService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * Built-in methods still controlled by the tenant login-channel settings screen.
     * Federated methods are owned by the application/IdP registry instead.
     */
    private static final Set<String> LEGACY_MANAGED_LOCAL_METHODS = Set.of(
            "email_password", "password", "sms", "email_code"
    );

    private final TenantLoginChannelMapper channelMapper;

    @Autowired(required = false)
    private LoginApplicationChannelMapper applicationChannelMapper;

    @Autowired(required = false)
    private SystemModeService systemModeService;

    @Override
    public List<String> getEnabledChannels(Long tenantId, String applicationCode, String channelCode) {
        Long effectiveTenantId = resolvePreAuthTenantId(tenantId);
        if (applicationChannelMapper != null) {
            List<String> methods = applicationChannelMapper.findEnabledAuthMethods(
                    applicationCode == null || applicationCode.isBlank() ? "business-web" : applicationCode,
                    channelCode == null || channelCode.isBlank() ? "default-business-web" : channelCode,
                    effectiveTenantId);
            if (methods != null && !methods.isEmpty()) {
                return mergeRegistryMethodsWithLegacyLocalToggles(
                        methods, getEnabledChannels(effectiveTenantId));
            }
        }
        return getEnabledChannels(effectiveTenantId);
    }

    @Override
    public List<LoginChannelOption> getEnabledChannelOptions(
            Long tenantId, String applicationCode, String channelCode) {
        Long effectiveTenantId = resolvePreAuthTenantId(tenantId);
        if (applicationChannelMapper != null) {
            List<LoginChannelOption> options = applicationChannelMapper.findEnabledAuthOptions(
                    applicationCode == null || applicationCode.isBlank() ? "business-web" : applicationCode,
                    channelCode == null || channelCode.isBlank() ? "default-business-web" : channelCode,
                    effectiveTenantId);
            if (options != null && !options.isEmpty()) {
                return mergeRegistryOptionsWithLegacyLocalToggles(
                        options, getEnabledChannels(effectiveTenantId));
            }
        }
        return TenantLoginChannelService.super.getEnabledChannelOptions(
                effectiveTenantId, applicationCode, channelCode);
    }

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

    private Long resolvePreAuthTenantId(Long requestedTenantId) {
        if (systemModeService != null && systemModeService.isSingleTenant()) {
            return systemModeService.getDefaultTenantId();
        }
        return requestedTenantId;
    }

    private List<String> mergeRegistryMethodsWithLegacyLocalToggles(
            List<String> registryMethods, List<String> enabledLegacyMethods) {
        Map<String, String> merged = new LinkedHashMap<>();
        enabledLegacyMethods.stream()
                .filter(this::isLegacyManagedLocalMethod)
                .forEach(method -> merged.put(normalizeMethod(method), method));
        registryMethods.stream()
                .filter(method -> !isLegacyManagedLocalMethod(method))
                .forEach(method -> merged.putIfAbsent(normalizeMethod(method), method));
        return List.copyOf(merged.values());
    }

    private List<LoginChannelOption> mergeRegistryOptionsWithLegacyLocalToggles(
            List<LoginChannelOption> registryOptions, List<String> enabledLegacyMethods) {
        Map<String, LoginChannelOption> merged = new LinkedHashMap<>();
        enabledLegacyMethods.stream()
                .filter(this::isLegacyManagedLocalMethod)
                .map(this::legacyOption)
                .forEach(option -> merged.put(normalizeMethod(option.getCode()), option));
        registryOptions.stream()
                .filter(option -> !isLegacyManagedLocalMethod(option.getCode()))
                .forEach(option -> merged.putIfAbsent(normalizeMethod(option.getCode()), option));
        return List.copyOf(merged.values());
    }

    private LoginChannelOption legacyOption(String rawCode) {
        String code = normalizeMethod(rawCode);
        String kind = switch (code) {
            case "email_password", "password" -> "password";
            case "sms", "email_code" -> "otp";
            default -> "oauth";
        };
        return new LoginChannelOption(code, kind, rawCode, null);
    }

    private boolean isLegacyManagedLocalMethod(String method) {
        return LEGACY_MANAGED_LOCAL_METHODS.contains(normalizeMethod(method));
    }

    private String normalizeMethod(String method) {
        return method == null ? "" : method.toLowerCase();
    }
}
