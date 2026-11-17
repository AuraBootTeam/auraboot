package com.auraboot.framework.notification.wechat;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dao.entity.AuthIdentity;
import com.auraboot.framework.auth.mapper.AuthIdentityMapper;
import com.auraboot.framework.auth.wechat.WechatMiniProperties;
import com.auraboot.framework.auth.wechat.WechatMiniStableTokenClient;
import com.auraboot.framework.notification.mapper.NotificationMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.*;

/** One transport attempt, not a reliable-event consumer. Production dispatch still requires a durable attempt journal. */
@Service
public class WechatSubscriptionMessageSender {
    public enum Status { BLOCKED, NOT_ATTEMPTED, PROVIDER_ACCEPTED, PROVIDER_REJECTED, OUTCOME_UNKNOWN }
    public enum Reason { DISABLED, CONFIGURATION_INCOMPLETE, TENANT_MISMATCH, GRANT_UNAVAILABLE,
        GRANT_INVALID, RECIPIENT_UNAVAILABLE, IDENTITY_UNAVAILABLE, FIELDS_INVALID, TOKEN_FAILED, SEND_UNKNOWN }
    public record Request(long tenantId, String resolutionReference) {}
    public record Result(Status status, Reason reason, Long providerCode) {}
    private final WechatSubscriptionProperties configuration;
    private final WechatMiniProperties mini;
    private final WechatMiniStableTokenClient tokens;
    private final List<WechatSubscriptionGrantResolver> grants;
    private final NotificationMapper recipients;
    private final AuthIdentityMapper identities;
    private final Clock clock;
    private final RestClient http;
    @Autowired
    public WechatSubscriptionMessageSender(WechatSubscriptionProperties configuration, WechatMiniProperties mini,
            WechatMiniStableTokenClient tokens, List<WechatSubscriptionGrantResolver> grants,
            NotificationMapper recipients, AuthIdentityMapper identities) {
        this(configuration,mini,tokens,grants,recipients,identities,Clock.systemUTC());
    }
    WechatSubscriptionMessageSender(WechatSubscriptionProperties configuration, WechatMiniProperties mini,
            WechatMiniStableTokenClient tokens, List<WechatSubscriptionGrantResolver> grants,
            NotificationMapper recipients, AuthIdentityMapper identities, Clock clock) {
        this.configuration=configuration; this.mini=mini; this.tokens=tokens; this.grants=List.copyOf(grants);
        this.recipients=recipients; this.identities=identities; this.clock=clock;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(Math.max(1,configuration.getConnectTimeoutMs()))).build());
        factory.setReadTimeout(Duration.ofMillis(Math.max(1,configuration.getReadTimeoutMs())));
        http = RestClient.builder().requestFactory(factory).build();
    }
    record Prepared(WechatSubscriptionGrantResolver.Grant grant, String configurationHash, String miniprogramState, String apiBase) {}
    record Preparation(Result result, Prepared prepared) {}
    // Package-local transport compatibility for the existing loopback contract. No SDK/product may call this directly.
    Result sendOnce(Request request) {
        Preparation preparation=prepare(request);
        return preparation.prepared()==null?preparation.result():sendPrepared(preparation.prepared());
    }
    Preparation prepare(Request request) {
        if (!configuration.isEnabled()) return preparationBlocked(Reason.DISABLED);
        URI base = configuredBase();
        if (base == null || !configuredTemplate()) return preparationBlocked(Reason.CONFIGURATION_INCOMPLETE);
        if (request == null || request.tenantId() <= 0 || blank(request.resolutionReference())
                || !Objects.equals(MetaContext.getCurrentTenantId(),request.tenantId())) return preparationBlocked(Reason.TENANT_MISMATCH);
        if (grants.size()!=1 || grants.getFirst().getClass().getClassLoader()!=getClass().getClassLoader())
            return preparationBlocked(Reason.GRANT_UNAVAILABLE);
        var resolved = grants.getFirst().claim(request.tenantId(),request.resolutionReference());
        if (resolved.isEmpty()) return preparationBlocked(Reason.GRANT_UNAVAILABLE);
        var grant=resolved.get();
        if (grant.tenantId()!=request.tenantId() || !request.resolutionReference().equals(grant.resolutionReference())
                || !mini.getAppId().equals(grant.appId()) || !configuration.templateContracts().containsKey(grant.templateId())
                || blank(grant.userPid()) || blank(grant.identityPid()) || blank(grant.openid())
                || blank(grant.grantReference()) || blank(grant.reservationReference())
                || grant.expiresAt()==null || !grant.expiresAt().isAfter(clock.instant())) return preparationBlocked(Reason.GRANT_INVALID);
        var fields = configuration.templateContracts().get(grant.templateId());
        if (!grant.values().keySet().equals(fields.keySet())
                || grant.values().entrySet().stream().anyMatch(e -> blank(e.getValue())
                    || e.getValue().codePointCount(0,e.getValue().length())>fields.get(e.getKey())))
            return preparationBlocked(Reason.FIELDS_INVALID);
        Long userId=recipients.findActiveRecipient(request.tenantId(),grant.userPid());
        if (userId==null) return preparationBlocked(Reason.RECIPIENT_UNAVAILABLE);
        List<AuthIdentity> bound=identities.selectList(new QueryWrapper<AuthIdentity>().eq("user_id",userId)
                .eq("provider","wechat_mini").eq("app_id",mini.getAppId()));
        if (bound.size()!=1 || !grant.identityPid().equals(bound.getFirst().getPid())
                || !grant.openid().equals(bound.getFirst().getOpenid()) || !Objects.equals(userId,bound.getFirst().getUserId())
                || !"wechat_mini".equals(bound.getFirst().getProvider()) || !mini.getAppId().equals(bound.getFirst().getAppId()))
            return preparationBlocked(Reason.IDENTITY_UNAVAILABLE);
        return new Preparation(null,new Prepared(grant,configurationHash(grant.templateId()),configuration.getMiniprogramState(),base.toString()));
    }
    Result verifyPrepared(Prepared prepared) {
        if (!readyForDispatch() || !configuration.templateContracts().containsKey(prepared.grant().templateId()))
            return blocked(Reason.CONFIGURATION_INCOMPLETE);
        var fields = configuration.templateContracts().get(prepared.grant().templateId());
        var expected=grants.getFirst().verifyReserved(prepared.grant().tenantId(),prepared.grant().resolutionReference(),
                prepared.grant().reservationReference());
        if (expected.isEmpty()) return blocked(Reason.GRANT_UNAVAILABLE);
        if (!new Prepared(expected.get(),configurationHash(prepared.grant().templateId()),configuration.getMiniprogramState(),configuredBase().toString()).equals(prepared))
            return blocked(Reason.GRANT_INVALID);
        if (!Objects.equals(MetaContext.getCurrentTenantId(),prepared.grant().tenantId())
                || !prepared.grant().expiresAt().isAfter(clock.instant())) return blocked(Reason.GRANT_INVALID);
        if(!prepared.grant().appId().equals(mini.getAppId()) || !configuration.templateContracts().containsKey(prepared.grant().templateId())
                || !prepared.grant().values().keySet().equals(fields.keySet())
                || prepared.grant().values().entrySet().stream().anyMatch(e->blank(e.getValue())
                    || e.getValue().codePointCount(0,e.getValue().length())>fields.get(e.getKey())))
            return blocked(Reason.GRANT_INVALID);
        Long userId=recipients.findActiveRecipient(prepared.grant().tenantId(),prepared.grant().userPid());
        if(userId==null)return blocked(Reason.RECIPIENT_UNAVAILABLE);
        var bound=identities.selectList(new QueryWrapper<AuthIdentity>().eq("user_id",userId)
                .eq("provider","wechat_mini").eq("app_id",mini.getAppId()));
        if(bound.size()!=1 || !prepared.grant().identityPid().equals(bound.getFirst().getPid())
                || !prepared.grant().openid().equals(bound.getFirst().getOpenid()))return blocked(Reason.IDENTITY_UNAVAILABLE);
        return null;
    }
    boolean readyForDispatch() {
        return configuration.isEnabled() && configuredBase()!=null && configuredTemplate() && grants.size()==1
                && grants.getFirst().getClass().getClassLoader()==getClass().getClassLoader();
    }
    private String configurationHash(String templateId) {
        try {
            String value=new ObjectMapper().writeValueAsString(new TreeMap<>(Map.of("appId",mini.getAppId(),
                    "base",configuredBase().toString(),"templateId",templateId,
                    "state",configuration.getMiniprogramState(),"fields",new TreeMap<>(configuration.templateContracts().get(templateId)))));
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch(java.security.NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("WECHAT_CONFIGURATION_HASH_FAILED");
        }
    }
    Result sendPrepared(Prepared prepared) {
        // Journal is the only production caller; it commits a persistent attempt fence before entering here.
        if (!readyForDispatch() || !configuration.templateContracts().containsKey(prepared.grant().templateId())
                || !prepared.configurationHash().equals(configurationHash(prepared.grant().templateId()))
                || !prepared.apiBase().equals(configuredBase().toString())
                || !prepared.miniprogramState().equals(configuration.getMiniprogramState()))
            return blocked(Reason.CONFIGURATION_INCOMPLETE);
        var grant=prepared.grant(); URI base=URI.create(prepared.apiBase());
        String token;
        try { token=tokens.acquire().accessToken(); }
        catch (WechatMiniStableTokenClient.TokenFailure failure) { return new Result(Status.NOT_ATTEMPTED,Reason.TOKEN_FAILED,null); }
        Map<String,Object> data=new TreeMap<>();grant.values().forEach((key,value)->data.put(key,Map.of("value",value)));
        Map<String,Object> payload=Map.of("touser",grant.openid(),"template_id",grant.templateId(),
                "miniprogram_state",prepared.miniprogramState(),"data",data);
        try {
            // One HTTP invocation. Token refresh, retry and raw provider diagnostics are deliberately absent.
            return http.post().uri(base+"/cgi-bin/message/subscribe/send?access_token="
                    +URLEncoder.encode(token,StandardCharsets.UTF_8)).contentType(MediaType.APPLICATION_JSON).body(payload)
                    .exchange((sent,response)->{
                        if (!response.getStatusCode().is2xxSuccessful()) return unknown();
                        byte[] body=response.getBody().readNBytes(65_537);
                        if (body.length>65_536) return unknown();
                        JsonNode parsed=new ObjectMapper().readTree(body);
                        if (parsed==null || !parsed.isObject() || !parsed.path("errcode").isIntegralNumber()
                                || !parsed.path("errcode").canConvertToLong()) return unknown();
                        long code=parsed.path("errcode").longValue();
                        return new Result(code==0?Status.PROVIDER_ACCEPTED:Status.PROVIDER_REJECTED,null,code);
                    });
        } catch (org.springframework.web.client.RestClientException failure) {
            // After send began, acceptance is unknown. Never propagate a token URI or private response as cause/message.
            return unknown();
        }
    }
    private boolean configuredTemplate() {
        return !blank(mini.getAppId()) && !blank(mini.getAppSecret()) && !configuration.templateContracts().isEmpty()
                && Set.of("developer","trial","formal").contains(configuration.getMiniprogramState()==null?"":configuration.getMiniprogramState())
                && configuration.getConnectTimeoutMs()>0 && configuration.getReadTimeoutMs()>0
                && configuration.getConnectTimeoutMs()<=60_000 && configuration.getReadTimeoutMs()<=60_000;
    }
    private URI configuredBase() {
        try {
            URI base=WechatMiniStableTokenClient.validatedApiBase(mini.getApiBaseUrl());
            boolean official="https".equals(base.getScheme()) && "api.weixin.qq.com".equals(base.getHost())
                    && (base.getPort()==-1 || base.getPort()==443);
            boolean loopback=Set.of("localhost","127.0.0.1","[::1]").contains(base.getHost());
            return official || (configuration.isAllowLoopback() && loopback) ? base : null;
        } catch (WechatMiniStableTokenClient.TokenFailure invalid) { return null; }
    }
    private static Preparation preparationBlocked(Reason reason) { return new Preparation(blocked(reason),null); }
    private static Result blocked(Reason reason) { return new Result(Status.BLOCKED,reason,null); }
    private static Result unknown() { return new Result(Status.OUTCOME_UNKNOWN,Reason.SEND_UNKNOWN,null); }
    private static boolean blank(String value) { return value==null || value.isBlank(); }
}
