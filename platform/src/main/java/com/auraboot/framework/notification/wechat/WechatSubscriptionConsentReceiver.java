package com.auraboot.framework.notification.wechat;

import com.auraboot.framework.auth.wechat.WechatMiniProperties;
import com.auraboot.framework.notification.mapper.WechatSubscriptionConsentMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Set;

/** Commits authenticated provider facts before HTTP acknowledgement. Does not reserve quota or send messages. */
@Service
@ConditionalOnProperty(prefix = "aura.notification.wechat-subscription.callback", name = "enabled", havingValue = "true")
public class WechatSubscriptionConsentReceiver {
    private final WechatSubscriptionConsentMapper mapper;
    private final WechatSubscriptionCallbackVerifier verifier;
    private final WechatSubscriptionCallbackVerifier.Format format;
    private final Set<String> templates;
    private final Duration validity;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Autowired
    public WechatSubscriptionConsentReceiver(WechatSubscriptionConsentMapper mapper,
            WechatSubscriptionCallbackProperties properties, WechatMiniProperties mini) {
        this(mapper, properties, mini, Clock.systemUTC());
    }

    WechatSubscriptionConsentReceiver(WechatSubscriptionConsentMapper mapper,
            WechatSubscriptionCallbackProperties properties, WechatMiniProperties mini, Clock clock) {
        if (!properties.isEnabled() || properties.getFormat() == null || properties.getConsentValidity() == null
                || properties.getConsentValidity().isNegative() || properties.getConsentValidity().isZero()
                || properties.getConsentValidity().compareTo(Duration.ofDays(365)) > 0
                || properties.getOnceTemplateIds() == null || properties.getOnceTemplateIds().size() > 100
                || properties.getOnceTemplateIds().stream().anyMatch(id -> id == null || !id.matches("[A-Za-z0-9_-]{1,128}"))) {
            throw WechatSubscriptionCallbackVerifier.rejected("CALLBACK_CONFIGURATION_INVALID");
        }
        this.mapper = mapper;
        this.clock = clock;
        this.format = properties.getFormat();
        this.validity = properties.getConsentValidity();
        this.templates = Set.copyOf(properties.getOnceTemplateIds());
        this.verifier = new WechatSubscriptionCallbackVerifier(mini.getAppId(), properties.getOriginalAccountId(),
                properties.getToken(), properties.getEncodingAesKey(), clock, properties.getRequestWindow());
    }

    public String verifyUrl(String signature, String timestamp, String nonce, String echo) {
        return verifier.verifyUrl(signature, timestamp, nonce, echo);
    }

    @Transactional
    public Receipt receive(String encryptType, String signature, String timestamp, String nonce, byte[] body) {
        var verified = verifier.verify(encryptType, signature, timestamp, nonce, body, format);
        // The official developer-server diagnostic is authenticated but never constitutes subscription consent.
        if ("event".equals(verified.parsed().text("MsgType"))
                && "debug_demo".equals(verified.parsed().text("Event"))) return new Receipt(Status.VERIFIED_DIAGNOSTIC, 0);
        var event = WechatSubscriptionCallbackEvents.decode(verified);
        if (event.createdAt().isAfter(clock.instant().plusSeconds(300))) {
            throw WechatSubscriptionCallbackVerifier.rejected("CALLBACK_EVENT_TIME_INVALID");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("WECHAT_CONSENT_TRANSACTION_REQUIRED");
        }
        var items = event.items().stream().sorted(Comparator.comparing(WechatSubscriptionCallbackEvents.Item::templateId)).toList();
        String canonical = encode(items);
        String key = digest(encode(new EventIdentity(event.appId(), event.openid(), event.createdAt(), event.kind(), canonical)));
        mapper.lockPrincipal(digest(event.appId() + "\n" + event.openid()));
        int recorded = mapper.insertEvent(key, event.appId(), event.openid(), event.createdAt(), event.kind().name(), canonical);
        if (recorded == 0) {
            return new Receipt(Status.REPLAYED, 0);
        }
        if (recorded != 1) throw new IllegalStateException("WECHAT_CONSENT_INSERT_FAILED");
        int grants = 0;
        for (var item : items) {
            // Delivery results are receipts only. They never grant or replenish authorization.
            if (event.kind() == WechatSubscriptionCallbackEvents.Kind.SENT) continue;
            if ("reject".equals(item.status())) {
                mapper.revokeThrough(event.appId(), event.openid(), item.templateId(), event.createdAt());
            } else if (templates.contains(item.templateId())) {
                Instant revoked = mapper.revokedThrough(event.appId(), event.openid(), item.templateId());
                Instant expires = event.createdAt().plus(validity);
                if ((revoked == null || event.createdAt().isAfter(revoked)) && expires.isAfter(clock.instant())) {
                    int inserted = mapper.insertGrant(digest(key + "\n" + item.templateId()), key, event.appId(), event.openid(),
                            item.templateId(), event.createdAt(), expires);
                    if (inserted != 1) throw new IllegalStateException("WECHAT_CONSENT_INSERT_FAILED");
                    grants++;
                }
            }
        }
        return new Receipt(Status.RECORDED, grants);
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("WECHAT_CONSENT_ENCODING_FAILED"); }
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable", unavailable); }
    }
    private record EventIdentity(String appId, String openid, Instant createdAt, WechatSubscriptionCallbackEvents.Kind kind, String items) {
        @Override public String toString() { return "WechatSubscriptionEventIdentity[redacted]"; }
    }
    public enum Status { RECORDED, REPLAYED, VERIFIED_DIAGNOSTIC }
    public record Receipt(Status status, int grantsCreated) { }
}
