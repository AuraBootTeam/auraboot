package com.auraboot.framework.iot.broker;

import com.auraboot.framework.iot.broker.EmqxAclSyncService.DevicePrincipal;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmqxAclSyncServiceTest {

    private EmqxAclProperties enabledProps() {
        EmqxAclProperties p = new EmqxAclProperties();
        p.setEnabled(true);
        p.setBaseUrl("http://emqx-fake:18083");
        p.setApiKey("u");
        p.setApiSecret("p");
        p.setAuthenticatorId("password_based:built_in_database");
        p.setTimeoutMs(2000);
        return p;
    }

    private EmqxAclSyncService build(EmqxAclProperties props, ExchangeFunction exchange) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(exchange);
        return new EmqxAclSyncService(props, new ObjectMapper(), builder);
    }

    @Test
    void disabled_isNoOp_andDoesNotInvokeExchange() {
        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction xf = req -> {
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
        EmqxAclProperties p = new EmqxAclProperties();
        p.setEnabled(false);
        EmqxAclSyncService svc = build(p, xf);
        svc.syncDeviceUser(1L, "dev-1", "pw", List.of("/sys/p/dev-1/#"));
        svc.revokeDeviceUser(1L, "dev-1");
        svc.syncTenantAcl(1L, List.of(new DevicePrincipal("dev-2", "pw", List.of())));
        assertThat(calls.get()).isZero();
    }

    @Test
    void syncDeviceUser_postsCreateUser_thenPushesAclRules_when2xx() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<URI> firstUri = new AtomicReference<>();
        AtomicReference<String> firstMethod = new AtomicReference<>();
        AtomicReference<URI> secondUri = new AtomicReference<>();
        ExchangeFunction xf = req -> {
            int n = calls.incrementAndGet();
            if (n == 1) { firstUri.set(req.url()); firstMethod.set(req.method().name()); }
            else if (n == 2) secondUri.set(req.url());
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
        EmqxAclSyncService svc = build(enabledProps(), xf);
        svc.syncDeviceUser(7L, "dev-7", "secret", List.of("/sys/p/dev-7/#"));
        assertThat(calls.get()).isEqualTo(2);
        // Regression guard for the baseUrl bug: the absolute request MUST target the
        // configured broker host, not localhost (uri(URI) used to ignore baseUrl).
        assertThat(firstUri.get().getHost()).isEqualTo("emqx-fake");
        assertThat(firstUri.get().getPort()).isEqualTo(18083);
        // Create is a POST to /users (user_id is in the body, NOT the path).
        assertThat(firstMethod.get()).isEqualTo("POST");
        assertThat(firstUri.get().getPath())
                .contains("/api/v5/authentication/")
                .endsWith("/users");
        assertThat(secondUri.get().getPath())
                .contains("/api/v5/authorization/sources/built_in_database/rules/users");
    }

    @Test
    void syncDeviceUser_putsUpdate_whenPostConflicts409() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<URI> putUri = new AtomicReference<>();
        AtomicReference<String> putMethod = new AtomicReference<>();
        ExchangeFunction xf = req -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                // POST create → user already exists.
                return Mono.just(ClientResponse.create(HttpStatus.CONFLICT)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"message\":\"ALREADY_EXISTS\"}").build());
            }
            if (n == 2) { putUri.set(req.url()); putMethod.set(req.method().name()); }
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
        EmqxAclSyncService svc = build(enabledProps(), xf);
        svc.syncDeviceUser(7L, "dev-7", "secret", List.of("/sys/p/dev-7/#"));
        // POST (409) → PUT update → ACL push POST = 3 calls.
        assertThat(calls.get()).isEqualTo(3);
        assertThat(putMethod.get()).isEqualTo("PUT");
        // Update targets the specific user (user_id in the path).
        assertThat(putUri.get().getPath()).endsWith("/users/dev-7");
        assertThat(putUri.get().getHost()).isEqualTo("emqx-fake");
    }

    @Test
    void syncDeviceUser_skipsAclPush_whenPatternsEmpty() {
        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction xf = req -> {
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
        EmqxAclSyncService svc = build(enabledProps(), xf);
        svc.syncDeviceUser(7L, "dev-7", "secret", List.of());
        assertThat(calls.get()).isEqualTo(1); // only the POST create-user call
    }

    @Test
    void syncDeviceUser_throwsImmediatelyOn4xx_noRetry() {
        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction xf = req -> {
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.BAD_REQUEST)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"message\":\"bad shape\"}")
                    .build());
        };
        EmqxAclSyncService svc = build(enabledProps(), xf);
        assertThatThrownBy(() -> svc.syncDeviceUser(7L, "dev-7", "s", List.of("/t/#")))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("status=400");
        assertThat(calls.get()).isEqualTo(1); // no retry on 4xx
    }

    @Test
    void syncDeviceUser_retriesOn5xx_thenSucceedsOnLastAttempt() {
        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction xf = req -> {
            int n = calls.incrementAndGet();
            if (n < EmqxAclSyncService.MAX_RETRIES) {
                return Mono.just(ClientResponse.create(HttpStatus.BAD_GATEWAY).build());
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
        EmqxAclSyncService svc = build(enabledProps(), xf);
        // First call (POST create-user) succeeds on last retry → 3 attempts.
        // Then ACL push (POST) runs once more and succeeds (call 4).
        svc.syncDeviceUser(7L, "dev-7", "s", List.of("/t/#"));
        assertThat(calls.get()).isEqualTo(EmqxAclSyncService.MAX_RETRIES + 1);
    }

    @Test
    void syncDeviceUser_throwsExhaustedRetries_onPersistent5xx() {
        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction xf = req -> {
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.BAD_GATEWAY).build());
        };
        EmqxAclSyncService svc = build(enabledProps(), xf);
        assertThatThrownBy(() -> svc.syncDeviceUser(7L, "dev-7", "s", List.of("/t/#")))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("exhausted retries");
        assertThat(calls.get()).isEqualTo(EmqxAclSyncService.MAX_RETRIES);
    }

    @Test
    void revokeDeviceUser_swallows404_treatsAsSuccess() {
        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction xf = req -> {
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND).build());
        };
        EmqxAclSyncService svc = build(enabledProps(), xf);
        // Should not throw.
        svc.revokeDeviceUser(7L, "ghost");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void revokeDeviceUser_throwsOnOther4xx() {
        ExchangeFunction xf = req -> Mono.just(ClientResponse.create(HttpStatus.FORBIDDEN).build());
        EmqxAclSyncService svc = build(enabledProps(), xf);
        assertThatThrownBy(() -> svc.revokeDeviceUser(7L, "x"))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("status=403");
    }

    @Test
    void validateBasics_rejectsZeroTenantAndBlankUser() {
        ExchangeFunction xf = req -> Mono.just(ClientResponse.create(HttpStatus.OK).build());
        EmqxAclSyncService svc = build(enabledProps(), xf);
        assertThatThrownBy(() -> svc.syncDeviceUser(0L, "x", "s", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.syncDeviceUser(1L, "  ", "s", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.revokeDeviceUser(-1L, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
