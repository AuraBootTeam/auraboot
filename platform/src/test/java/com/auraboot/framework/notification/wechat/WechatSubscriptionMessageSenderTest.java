package com.auraboot.framework.notification.wechat;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dao.entity.AuthIdentity;
import com.auraboot.framework.auth.mapper.AuthIdentityMapper;
import com.auraboot.framework.auth.wechat.WechatMiniProperties;
import com.auraboot.framework.auth.wechat.WechatMiniStableTokenClient;
import com.auraboot.framework.notification.mapper.NotificationMapper;
import com.auraboot.framework.notification.wechat.WechatSubscriptionMessageSender.*;
import com.auraboot.framework.notification.wechat.WechatSubscriptionGrantResolver.Grant;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real loopback HTTP; synthetic explicitly claimed grant, no WeChat provider or human receiver. */
class WechatSubscriptionMessageSenderTest {
    private HttpServer server;
    private ExecutorService serverThreads;
    private final List<String> requests=new CopyOnWriteArrayList<>();
    private final NotificationMapper recipients=mock(NotificationMapper.class);
    private final AuthIdentityMapper identities=mock(AuthIdentityMapper.class);
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"),ZoneOffset.UTC);
    private WechatMiniProperties mini;
    private WechatSubscriptionProperties configuration;
    private Grant grant;
    private WechatSubscriptionGrantResolver authority;
    private final AtomicBoolean claimed=new AtomicBoolean();
    private String tokenResponse="{\"access_token\":\"synthetic-token\",\"expires_in\":7200}";
    private String sendResponse="{\"errcode\":0,\"errmsg\":\"ok\"}";
    private int sendStatus=200;
    private long sendDelay;
    @BeforeEach void fixture() throws Exception {
        MetaContext.setContext(42L,12L,"synthetic-actor","Synthetic S06");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        serverThreads=Executors.newCachedThreadPool();server.setExecutor(serverThreads);
        server.createContext("/cgi-bin/stable_token",exchange->{
            requests.add(exchange.getRequestMethod()+" "+exchange.getRequestURI()+" "+new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] body=tokenResponse.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.createContext("/cgi-bin/message/subscribe/send",exchange->{
            requests.add(exchange.getRequestMethod()+" "+exchange.getRequestURI()+" "+new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            try {
                if(sendDelay>0)Thread.sleep(sendDelay);
                byte[] body=sendResponse.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(sendStatus,body.length);exchange.getResponseBody().write(body);
            } catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            catch(java.io.IOException disconnected){ /* Deliberate timeout fault: server may have already accepted the request. */ }
            finally{exchange.close();}
        });server.start();
        mini=new WechatMiniProperties();mini.setAppId("synthetic-app");mini.setAppSecret("synthetic-secret");
        mini.setApiBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
        configuration=new WechatSubscriptionProperties();configuration.setAllowLoopback(true);
        configuration.setTemplateId("synthetic-template");configuration.setMiniprogramState("developer");
        configuration.setFieldMaxLengths(Map.of("thing4",20,"time5",20));configuration.setReadTimeoutMs(1000);
        grant=new Grant(42,"synthetic-ref","synthetic-user","synthetic-app","synthetic-identity","synthetic-openid",
                "synthetic-template","synthetic-grant","synthetic-reservation",clock.instant().plusSeconds(60),
                Map.of("thing4","报价提醒","time5","2026-10-13 08:00"));
        // Fixture-only one-use authority. No claim is made about production persistence or subscription authorization.
        authority=(tenant,ref)->claimed.compareAndSet(false,true)?Optional.of(grant):Optional.empty();
        when(recipients.findActiveRecipient(42,"synthetic-user")).thenReturn(99L);
        AuthIdentity identity=new AuthIdentity();identity.setPid("synthetic-identity");identity.setUserId(99L);
        identity.setAppId("synthetic-app");identity.setProvider("wechat_mini");identity.setOpenid("synthetic-openid");
        when(identities.selectList(any(Wrapper.class))).thenReturn(List.of(identity));
    }
    @AfterEach void cleanup() throws Exception {
        MetaContext.clear();if(server!=null)server.stop(0);
        if(serverThreads!=null){serverThreads.shutdownNow();assertThat(serverThreads.awaitTermination(2,TimeUnit.SECONDS)).isTrue();}
    }
    private WechatSubscriptionMessageSender sender(){return new WechatSubscriptionMessageSender(configuration,mini,
            new WechatMiniStableTokenClient(mini),List.of(authority),recipients,identities,clock);}
    private Request request(){return new Request(42,"synthetic-ref");}
    @Test void distinctTemplateContractsSendOnlyTheirOwnExactFields() throws Exception {
        configuration.setEnabled(true); configuration.setTemplateId(null); configuration.setFieldMaxLengths(Map.of());
        new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(Map.of(
                    "aura.notification.wechat-subscription.templates.quote-template.field-max-lengths.thing4", "20",
                    "aura.notification.wechat-subscription.templates.order-template.field-max-lengths.thing9", "40")))
                .bind("aura.notification.wechat-subscription", org.springframework.boot.context.properties.bind.Bindable.ofInstance(configuration));
        grant=new Grant(42,"quote-ref",grant.userPid(),grant.appId(),grant.identityPid(),grant.openid(),
                "quote-template","quote-grant","quote-reservation",clock.instant().plusSeconds(60),Map.of("thing4","报价提醒"));
        authority=(tenant,ref)->Optional.of(grant);
        var sender=sender();
        assertThat(sender.sendOnce(new Request(42,"quote-ref")).status()).isEqualTo(Status.PROVIDER_ACCEPTED);
        grant=new Grant(42,"order-ref",grant.userPid(),grant.appId(),grant.identityPid(),grant.openid(),
                "order-template","order-grant","order-reservation",clock.instant().plusSeconds(60),Map.of("thing9","订单发货提醒"));
        assertThat(sender.sendOnce(new Request(42,"order-ref")).status()).isEqualTo(Status.PROVIDER_ACCEPTED);
        var json=new ObjectMapper();
        var sent=requests.stream().filter(value->value.startsWith("POST /cgi-bin/message/subscribe/send")).toList();
        assertThat(sent).hasSize(2);
        var quote=json.readTree(sent.get(0).substring(sent.get(0).indexOf('{')));
        var order=json.readTree(sent.get(1).substring(sent.get(1).indexOf('{')));
        assertThat(quote.path("template_id").asText()).isEqualTo("quote-template");
        assertThat(quote.path("data").properties()).extracting(Map.Entry::getKey).containsExactly("thing4");
        assertThat(order.path("template_id").asText()).isEqualTo("order-template");
        assertThat(order.path("data").properties()).extracting(Map.Entry::getKey).containsExactly("thing9");
    }
    private void namedContracts() {
        configuration.setEnabled(true);configuration.setTemplateId(null);configuration.setFieldMaxLengths(Map.of());
        var quote=new WechatSubscriptionProperties.Template();quote.setFieldMaxLengths(Map.of("thing4",20));
        var order=new WechatSubscriptionProperties.Template();order.setFieldMaxLengths(Map.of("thing9",40));
        configuration.setTemplates(Map.of("quote-template",quote,"order-template",order));
        grant=new Grant(42,"synthetic-ref",grant.userPid(),grant.appId(),grant.identityPid(),grant.openid(),
                "quote-template",grant.grantReference(),grant.reservationReference(),grant.expiresAt(),Map.of("thing4","报价提醒"));
    }
    @Test void namedContractsRejectLegacyMixUnknownTemplateAndForeignFieldsWithoutNetwork() {
        namedContracts();configuration.setTemplateId("legacy-template");
        assertThat(sender().sendOnce(request()).reason()).isEqualTo(Reason.CONFIGURATION_INCOMPLETE);
        assertThat(claimed).isFalse();configuration.setTemplateId(null);
        grant=new Grant(42,"synthetic-ref",grant.userPid(),grant.appId(),grant.identityPid(),grant.openid(),
                "unknown-template",grant.grantReference(),grant.reservationReference(),grant.expiresAt(),grant.values());
        assertThat(sender().sendOnce(request()).reason()).isEqualTo(Reason.GRANT_INVALID);
        claimed.set(false);namedContracts();
        grant=new Grant(42,"synthetic-ref",grant.userPid(),grant.appId(),grant.identityPid(),grant.openid(),
                grant.templateId(),grant.grantReference(),grant.reservationReference(),grant.expiresAt(),Map.of("thing9","订单字段"));
        assertThat(sender().sendOnce(request()).reason()).isEqualTo(Reason.FIELDS_INVALID);
        assertThat(requests).isEmpty();
    }
    @Test void removingOrChangingSelectedTemplateAfterPrepareCannotReachTransport() {
        namedContracts();var sender=sender();var prepared=sender.prepare(request()).prepared();assertThat(prepared).isNotNull();
        configuration.getTemplates().get("quote-template").setFieldMaxLengths(Map.of("thing4",19));
        assertThat(sender.sendPrepared(prepared).reason()).isEqualTo(Reason.CONFIGURATION_INCOMPLETE);
        configuration.setTemplates(Map.of("order-template",configuration.getTemplates().get("order-template")));
        assertThat(sender.sendPrepared(prepared).reason()).isEqualTo(Reason.CONFIGURATION_INCOMPLETE);
        assertThat(requests).isEmpty();
    }
    @Test void malformedNamedTemplateCannotSilentlyFallBackToAnotherContract() {
        namedContracts();configuration.getTemplates().get("order-template").setFieldMaxLengths(Map.of());
        assertThat(sender().sendOnce(request()).reason()).isEqualTo(Reason.CONFIGURATION_INCOMPLETE);
        assertThat(claimed).isFalse();assertThat(requests).isEmpty();
    }
    @Test void disabledOrIncompleteConfigurationNeverCallsGrantTokenOrSend() {
        assertThat(sender().sendOnce(request()).reason()).isEqualTo(Reason.DISABLED);
        configuration.setEnabled(true);configuration.setTemplateId("");
        assertThat(sender().sendOnce(request()).reason()).isEqualTo(Reason.CONFIGURATION_INCOMPLETE);
        assertThat(claimed).isFalse();assertThat(requests).isEmpty();verifyNoInteractions(recipients,identities);
    }
    @Test void absentGrantAndForeignTenantNeverCallTokenOrSend() {
        configuration.setEnabled(true);
        assertThat(sender().sendOnce(new Request(43,"synthetic-ref")).reason()).isEqualTo(Reason.TENANT_MISMATCH);
        authority=(tenant,ref)->Optional.empty();
        assertThat(sender().sendOnce(request()).reason()).isEqualTo(Reason.GRANT_UNAVAILABLE);
        assertThat(requests).isEmpty();verifyNoInteractions(recipients,identities);
    }
    @Test void expiredWrongAppAndWrongTemplateCannotSend() {
        configuration.setEnabled(true);
        grant=new Grant(42,grant.resolutionReference(),grant.userPid(),"other-app",grant.identityPid(),grant.openid(),
                grant.templateId(),grant.grantReference(),grant.reservationReference(),grant.expiresAt(),grant.values());
        assertThat(sender().sendOnce(request()).reason()).isEqualTo(Reason.GRANT_INVALID);
        assertThat(requests).isEmpty();
        claimed.set(false);grant=new Grant(42,grant.resolutionReference(),grant.userPid(),mini.getAppId(),grant.identityPid(),grant.openid(),
                grant.templateId(),grant.grantReference(),grant.reservationReference(),clock.instant(),grant.values());
        assertThat(sender().sendOnce(request()).reason()).isEqualTo(Reason.GRANT_INVALID);assertThat(requests).isEmpty();
        claimed.set(false);grant=new Grant(42,grant.resolutionReference(),grant.userPid(),mini.getAppId(),grant.identityPid(),grant.openid(),
                "other-template",grant.grantReference(),grant.reservationReference(),clock.instant().plusSeconds(60),grant.values());
        assertThat(sender().sendOnce(request()).reason()).isEqualTo(Reason.GRANT_INVALID);assertThat(requests).isEmpty();
    }
    @Test void receiverMustBeActiveAndIdentityMustMatchExactConfiguredMiniApp() {
        configuration.setEnabled(true);when(recipients.findActiveRecipient(42,"synthetic-user")).thenReturn(null);
        assertThat(sender().sendOnce(request()).reason()).isEqualTo(Reason.RECIPIENT_UNAVAILABLE);assertThat(requests).isEmpty();
        claimed.set(false);when(recipients.findActiveRecipient(42,"synthetic-user")).thenReturn(99L);
        when(identities.selectList(any(Wrapper.class))).thenReturn(List.of());
        assertThat(sender().sendOnce(request()).reason()).isEqualTo(Reason.IDENTITY_UNAVAILABLE);assertThat(requests).isEmpty();
    }
    @Test void missingExtraOrTooLongFieldIsRejectedWithoutTruncationOrNetwork() {
        configuration.setEnabled(true);
        grant=new Grant(42,grant.resolutionReference(),grant.userPid(),grant.appId(),grant.identityPid(),grant.openid(),
                grant.templateId(),grant.grantReference(),grant.reservationReference(),grant.expiresAt(),Map.of("thing4","unmapped"));
        assertThat(sender().sendOnce(request()).reason()).isEqualTo(Reason.FIELDS_INVALID);assertThat(requests).isEmpty();
        claimed.set(false);grant=new Grant(42,grant.resolutionReference(),grant.userPid(),grant.appId(),grant.identityPid(),grant.openid(),
                grant.templateId(),grant.grantReference(),grant.reservationReference(),grant.expiresAt(),Map.of("thing4","x".repeat(21),"time5","time"));
        assertThat(sender().sendOnce(request()).reason()).isEqualTo(Reason.FIELDS_INVALID);assertThat(requests).isEmpty();
    }
    @Test void actualTokenAndSendRequestUseOfficialFieldsAndAcceptedDoesNotMeanDelivered() throws Exception {
        configuration.setEnabled(true);var result=sender().sendOnce(request());
        assertThat(result.status()).isEqualTo(Status.PROVIDER_ACCEPTED);assertThat(result.providerCode()).isZero();
        assertThat(requests).hasSize(2);ObjectMapper json=new ObjectMapper();
        assertThat(requests.getFirst()).startsWith("POST /cgi-bin/stable_token ");
        var token=json.readTree(requests.getFirst().substring(requests.getFirst().indexOf('{')));
        assertThat(token.path("force_refresh").asBoolean()).isFalse();assertThat(token.path("appid").asText()).isEqualTo("synthetic-app");
        assertThat(requests.get(1)).startsWith("POST /cgi-bin/message/subscribe/send?access_token=synthetic-token ");
        var payload=json.readTree(requests.get(1).substring(requests.get(1).indexOf('{')));
        assertThat(payload.size()).isEqualTo(4);assertThat(payload.path("touser").asText()).isEqualTo("synthetic-openid");
        assertThat(payload.path("template_id").asText()).isEqualTo("synthetic-template");
        assertThat(payload.path("miniprogram_state").asText()).isEqualTo("developer");
        assertThat(payload.path("data").path("thing4").path("value").asText()).isEqualTo("报价提醒");
        assertThat(payload.path("data").path("time5").path("value").asText()).isEqualTo("2026-10-13 08:00");
        assertThat(sender().sendOnce(request()).reason()).isEqualTo(Reason.GRANT_UNAVAILABLE);assertThat(requests).hasSize(2);
        assertThat(grant.toString()).doesNotContain("synthetic-openid","报价提醒");
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"errcode\":1}","{\"access_token\":\"synthetic-token\",\"expires_in\":0}"})
    void tokenFailureIsNotAttemptedAndNeverCallsMessageEndpoint(String response) {
        configuration.setEnabled(true);tokenResponse=response;
        assertThat(sender().sendOnce(request()).status()).isEqualTo(Status.NOT_ATTEMPTED);
        assertThat(requests).hasSize(1);assertThat(requests.getFirst()).startsWith("POST /cgi-bin/stable_token ");
    }
    @Test void observedNonzeroProviderCodeIsRejectedWithoutGuessingRetryPolicy() {
        configuration.setEnabled(true);sendResponse="{\"errcode\":123456,\"errmsg\":\"private-provider-detail\"}";
        var result=sender().sendOnce(request());assertThat(result.status()).isEqualTo(Status.PROVIDER_REJECTED);
        assertThat(result.providerCode()).isEqualTo(123456);assertThat(result.toString()).doesNotContain("private-provider-detail","synthetic-token");
        assertThat(requests).hasSize(2);
    }
    @ParameterizedTest @ValueSource(strings={"{}","[]","null","not-json","{\"errcode\":\"0\"}","{\"errcode\":18446744073709551616}"})
    void malformedMessageReplyIsUnknownAndCannotTriggerAutoRetry(String response) {
        configuration.setEnabled(true);sendResponse=response;var sender=sender();
        assertThat(sender.sendOnce(request()).status()).isEqualTo(Status.OUTCOME_UNKNOWN);
        assertThat(sender.sendOnce(request()).reason()).isEqualTo(Reason.GRANT_UNAVAILABLE);assertThat(requests).hasSize(2);
    }
    @Test void realReadTimeoutAndHttpErrorAreUnknownWithoutTokenRefreshOrRetry() {
        configuration.setEnabled(true);configuration.setReadTimeoutMs(100);sendDelay=500;
        var sender=sender();assertThat(sender.sendOnce(request()).status()).isEqualTo(Status.OUTCOME_UNKNOWN);
        assertThat(sender.sendOnce(request()).reason()).isEqualTo(Reason.GRANT_UNAVAILABLE);assertThat(requests).hasSize(2);
    }
    @Test void pluginClassloaderCannotSupplySubscriptionAuthority() throws Exception {
        configuration.setEnabled(true);
        try(var loader=new java.net.URLClassLoader(new java.net.URL[0],getClass().getClassLoader())) {
            authority=(WechatSubscriptionGrantResolver)java.lang.reflect.Proxy.newProxyInstance(loader,
                    new Class<?>[]{WechatSubscriptionGrantResolver.class},(proxy,method,args)->{throw new AssertionError("Untrusted authority called");});
            assertThat(sender().sendOnce(request()).reason()).isEqualTo(Reason.GRANT_UNAVAILABLE);assertThat(requests).isEmpty();
        }
    }
    @Test void wrongOrAmbiguousBoundIdentityCannotReceiveCredentials() {
        configuration.setEnabled(true);AuthIdentity wrong=new AuthIdentity();wrong.setPid("synthetic-identity");wrong.setUserId(100L);
        wrong.setAppId("other-app");wrong.setProvider("wechat_web");wrong.setOpenid("other-openid");
        when(identities.selectList(any(Wrapper.class))).thenReturn(List.of(wrong));
        assertThat(sender().sendOnce(request()).reason()).isEqualTo(Reason.IDENTITY_UNAVAILABLE);assertThat(requests).isEmpty();
        claimed.set(false);when(identities.selectList(any(Wrapper.class))).thenReturn(List.of(wrong,wrong));
        assertThat(sender().sendOnce(request()).reason()).isEqualTo(Reason.IDENTITY_UNAVAILABLE);assertThat(requests).isEmpty();
    }
    @Test void httpErrorIsUnknownAndRemoteOrUnapprovedLoopbackCannotReceiveCredentials() {
        configuration.setEnabled(true);sendStatus=503;sendResponse="private-provider-detail";
        assertThat(sender().sendOnce(request()).status()).isEqualTo(Status.OUTCOME_UNKNOWN);assertThat(requests).hasSize(2);
        requests.clear();claimed.set(false);mini.setApiBaseUrl("https://unapproved.example.invalid");
        assertThat(sender().sendOnce(request()).reason()).isEqualTo(Reason.CONFIGURATION_INCOMPLETE);
        assertThat(requests).isEmpty();mini.setApiBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());configuration.setAllowLoopback(false);
        assertThat(sender().sendOnce(request()).reason()).isEqualTo(Reason.CONFIGURATION_INCOMPLETE);assertThat(requests).isEmpty();
    }
}
