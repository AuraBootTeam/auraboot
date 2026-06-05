package com.auraboot.framework.iot.broker;

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmqxRuleTestServiceTest {

    private EmqxAclProperties enabledProps() {
        EmqxAclProperties p = new EmqxAclProperties();
        p.setEnabled(true);
        p.setBaseUrl("http://emqx-fake:18083");
        p.setApiKey("u");
        p.setApiSecret("p");
        p.setTimeoutMs(2000);
        return p;
    }

    private EmqxRuleTestService build(EmqxAclProperties props, ExchangeFunction exchange) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(exchange);
        return new EmqxRuleTestService(props, new ObjectMapper(), builder);
    }

    @Test
    void matches_returnsTrue_on200_andPostsRuleTestWithMessagePublishContext() {
        AtomicReference<URI> uri = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        ExchangeFunction xf = req -> {
            uri.set(req.url());
            method.set(req.method().name());
            // EMQX rule_test returns 200 + the SELECT projection on a match.
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"t\":90}").build());
        };
        EmqxRuleTestService svc = build(enabledProps(), xf);

        boolean matched = svc.matches("SELECT payload.t as t FROM \"t/+/p/+/d/+/telemetry\" WHERE payload.t > 80",
                "t/1/p/pk/d/dev/telemetry", "{\"t\":90}");

        assertThat(matched).isTrue();
        assertThat(method.get()).isEqualTo("POST");
        // Regression guard: the absolute request must target the configured broker host.
        assertThat(uri.get().getHost()).isEqualTo("emqx-fake");
        assertThat(uri.get().getPath()).endsWith("/api/v5/rule_test");
    }

    @Test
    void matches_returnsFalse_on412NotMatch() {
        ExchangeFunction xf = req -> Mono.just(ClientResponse.create(HttpStatus.PRECONDITION_FAILED)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("{\"code\":\"NOT_MATCH\",\"message\":\"SQL Not Match\"}").build());
        EmqxRuleTestService svc = build(enabledProps(), xf);

        boolean matched = svc.matches("SELECT 1 FROM \"x\" WHERE payload.t > 80", "x", "{\"t\":10}");

        assertThat(matched).isFalse();
    }

    @Test
    void matches_throwsRuleSqlInvalid_on400() {
        ExchangeFunction xf = req -> Mono.just(ClientResponse.create(HttpStatus.BAD_REQUEST)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("{\"code\":\"BAD_REQUEST\",\"message\":\"bad sql\"}").build());
        EmqxRuleTestService svc = build(enabledProps(), xf);

        assertThatThrownBy(() -> svc.matches("NOT SQL", "x", "{}"))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("rule_sql_invalid");
    }

    @Test
    void matches_throwsEmqxRuleTest_on5xx() {
        ExchangeFunction xf = req -> Mono.just(ClientResponse.create(HttpStatus.BAD_GATEWAY).build());
        EmqxRuleTestService svc = build(enabledProps(), xf);

        assertThatThrownBy(() -> svc.matches("SELECT 1 FROM \"x\"", "x", "{}"))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("emqx_rule_test");
    }

    @Test
    void matches_throwsWhenDisabled_noBrokerCall() {
        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction xf = req -> {
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
        EmqxAclProperties p = new EmqxAclProperties();
        p.setEnabled(false);
        EmqxRuleTestService svc = build(p, xf);

        assertThatThrownBy(() -> svc.matches("SELECT 1 FROM \"x\"", "x", "{}"))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("emqx_disabled");
        assertThat(calls.get()).isZero();
    }
}
