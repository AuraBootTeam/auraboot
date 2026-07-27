package com.auraboot.framework.observability;

import com.auraboot.framework.im.controller.ImUnreadController;
import com.auraboot.framework.observability.clienterror.WebClientErrorController;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class OperationalEndpointAuthorizationTest {

    @Test
    void operationalReadsMatchSystemManagementMenuContract() throws Exception {
        assertSystemManagement(ObservabilityController.class.getMethod("getMetricsSnapshot"));
        assertSystemManagement(CorrelationController.class.getMethod("byTrace", String.class));
        assertSystemManagement(WebClientErrorController.class.getMethod("list", int.class, int.class));
    }

    @Test
    void unreadSummaryExplicitlyDeclaresItsSelfScopedContract() {
        AuthenticatedAccess access =
                ImUnreadController.class.getAnnotation(AuthenticatedAccess.class);

        assertThat(access).isNotNull();
        assertThat(access.value()).contains("self-scoped", "MetaContext");
    }

    private static void assertSystemManagement(Method method) {
        RequirePermission permission = method.getAnnotation(RequirePermission.class);
        assertThat(permission).isNotNull();
        assertThat(permission.value()).isEqualTo(MetaPermission.SYSTEM_MANAGEMENT);
    }
}
