package com.auraboot.framework.plugin.extension;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestRouteTest {

    @Test
    void of_buildsAuthenticatedRouteWithDefaults() {
        RestRoute r = RestRoute.of("GET", "/whoami", "probe.probe.read");
        assertThat(r.method()).isEqualTo("GET");
        assertThat(r.pathPattern()).isEqualTo("/whoami");
        assertThat(r.permissionCode()).isEqualTo("probe.probe.read");
        assertThat(r.authPolicy()).isEqualTo(AuthPolicy.AUTHENTICATED);
        assertThat(r.idempotent()).isFalse();
        assertThat(r.readOnlyTx()).isFalse();
        assertThat(r.requestJsonSchema()).isNull();
    }

    @Test
    void nullAuthPolicy_isNormalizedToAuthenticated() {
        RestRoute r = new RestRoute("POST", "/echo", "probe.probe.write", null, false, false, null);
        assertThat(r.authPolicy()).isEqualTo(AuthPolicy.AUTHENTICATED);
    }

    @Test
    void blankMethodOrPath_isRejected() {
        assertThatThrownBy(() -> new RestRoute(" ", "/x", "p", AuthPolicy.AUTHENTICATED, false, false, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RestRoute("GET", "  ", "p", AuthPolicy.AUTHENTICATED, false, false, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
