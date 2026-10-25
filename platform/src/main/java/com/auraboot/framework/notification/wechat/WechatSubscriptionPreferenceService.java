package com.auraboot.framework.notification.wechat;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.mapper.AuthIdentityMapper;
import com.auraboot.framework.auth.dao.entity.AuthIdentity;
import com.auraboot.framework.auth.wechat.WechatMiniProperties;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.notification.dto.WechatSubscriptionObservationRequest;
import com.auraboot.framework.notification.mapper.NotificationMapper;
import com.auraboot.framework.notification.mapper.WechatSubscriptionObservationMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Self-scoped client preference observations. This service cannot create provider consent or delivery intents. */
@Service
public class WechatSubscriptionPreferenceService {
    private final List<Template> templates;
    private final Set<String> templateIds;
    private final String appId;
    private final AuthIdentityMapper identities;
    private final NotificationMapper recipients;
    private final WechatSubscriptionObservationMapper observations;
    private final ObjectMapper json = new ObjectMapper();

    public WechatSubscriptionPreferenceService(WechatSubscriptionPreferenceProperties settings,
            WechatMiniProperties mini, AuthIdentityMapper identities, NotificationMapper recipients,
            WechatSubscriptionObservationMapper observations) {
        this.identities = identities;
        this.recipients = recipients;
        this.observations = observations;
        this.appId = mini.getAppId();
        Map<String, String> configured = settings.isEnabled() ? settings.getTemplates() : Map.of();
        if (configured == null || configured.size() > 20 || configured.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || !entry.getKey().matches("[A-Za-z][A-Za-z0-9_-]{0,63}")
                || entry.getValue() == null || !entry.getValue().matches("[A-Za-z0-9_-]{1,128}"))
                || configured.values().stream().distinct().count() != configured.size()
                || !configured.isEmpty() && (appId == null || !appId.matches("wx[0-9a-f]{16}"))) {
            throw new IllegalArgumentException("SUBSCRIPTION_PREFERENCE_CONFIGURATION_INVALID");
        }
        this.templates = configured.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> new Template(entry.getKey(), entry.getValue(), "once")).toList();
        this.templateIds = Set.copyOf(configured.values());
    }

    @Transactional(readOnly = true)
    public Catalog catalog() {
        if (!templates.isEmpty()) currentIdentity();
        return new Catalog(templates);
    }

    @Transactional
    public Recorded observe(WechatSubscriptionObservationRequest request) {
        if (templates.isEmpty()) throw new RootUnCheckedException(ResponseCode.BadParam);
        validate(request);
        Principal principal = currentIdentity();
        String canonical;
        try {
            canonical = json.writeValueAsString(request.results().stream()
                    .sorted(Comparator.comparing(WechatSubscriptionObservationRequest.Choice::templateId)).toList());
        } catch (JsonProcessingException invalid) { throw new IllegalStateException("SUBSCRIPTION_OBSERVATION_ENCODING_FAILED"); }
        String hash = digest(canonical);
        int inserted = observations.append(principal.tenant(), principal.user(), appId, principal.identityPid(),
                request.idempotencyKey(), hash, canonical);
        if (inserted != 0 && inserted != 1) throw new IllegalStateException("SUBSCRIPTION_OBSERVATION_INSERT_FAILED");
        String stored = observations.storedHash(principal.tenant(), principal.user(), appId,
                principal.identityPid(), request.idempotencyKey());
        if (!hash.equals(stored)) throw new RootUnCheckedException(ResponseCode.RequestIntentConflict);
        return new Recorded(true, false);
    }

    private Principal currentIdentity() {
        Long tenant = MetaContext.getCurrentTenantId(), user = MetaContext.getCurrentUserId();
        String userPid = MetaContext.getCurrentUserPid();
        if (!MetaContext.exists() || tenant == null || tenant <= 0 || user == null || user <= 0
                || userPid == null || !user.equals(recipients.findActiveRecipient(tenant, userPid))) {
            throw new RootUnCheckedException(ResponseCode.PermissionDenied);
        }
        List<AuthIdentity> matching = identities.selectList(new QueryWrapper<AuthIdentity>()
                .eq("user_id", user).eq("provider", "wechat_mini").eq("app_id", appId));
        if (matching.size() != 1) throw new RootUnCheckedException(ResponseCode.BadParam);
        AuthIdentity identity = matching.getFirst();
        if (!user.equals(identity.getUserId()) || !"wechat_mini".equals(identity.getProvider())
                || !appId.equals(identity.getAppId()) || identity.getPid() == null || identity.getPid().length() != 26
                || identity.getOpenid() == null || identity.getOpenid().isBlank()) {
            throw new RootUnCheckedException(ResponseCode.BadParam);
        }
        return new Principal(tenant, user, identity.getPid());
    }

    private void validate(WechatSubscriptionObservationRequest request) {
        if (request == null || request.idempotencyKey() == null
                || !request.idempotencyKey().matches("[A-Za-z0-9._:-]{1,120}")
                || request.results() == null || request.results().isEmpty() || request.results().size() > 20
                || request.results().stream().anyMatch(choice -> choice == null || choice.templateId() == null
                    || choice.status() == null || !templateIds.contains(choice.templateId())
                    || !Set.of("accept", "reject", "ban", "filter").contains(choice.status()))
                || request.results().stream().map(WechatSubscriptionObservationRequest.Choice::templateId).distinct().count()
                    != request.results().size()) throw new RootUnCheckedException(ResponseCode.BadParam);
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable", unavailable); }
    }
    private record Principal(long tenant, long user, String identityPid) {
        @Override public String toString() { return "SubscriptionObserver[redacted]"; }
    }
    public record Template(String kind, String templateId, String type) { }
    public record Catalog(List<Template> records) { public Catalog { records = List.copyOf(records); } }
    public record Recorded(boolean recorded, boolean delivered) { }
}
