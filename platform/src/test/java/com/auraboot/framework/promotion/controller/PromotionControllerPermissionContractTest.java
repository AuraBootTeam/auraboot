package com.auraboot.framework.promotion.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pins the least-privilege boundary for production promotion operations. */
class PromotionControllerPermissionContractTest {

    @Test
    @DisplayName("promotion endpoints keep read, manage, and admin duties separated")
    void endpointsDeclareLeastPrivilegePermissions() {
        Map<String, String> expected = Map.of(
                "list", MetaPermission.PAGE_PUBLISH_READ,
                "getByPid", MetaPermission.PAGE_PUBLISH_READ,
                "create", MetaPermission.PAGE_PUBLISH_MANAGE,
                "validate", MetaPermission.PAGE_PUBLISH_MANAGE,
                "resolveDrift", MetaPermission.PAGE_PUBLISH_MANAGE,
                "apply", MetaPermission.PAGE_PUBLISH_ADMIN);

        expected.forEach((methodName, permission) -> {
            Method method = Arrays.stream(PromotionController.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(methodName))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "PromotionController has no method " + methodName));
            RequirePermission annotation = method.getAnnotation(RequirePermission.class);
            assertThat(annotation)
                    .as("%s must remain explicitly permission guarded", methodName)
                    .isNotNull();
            assertThat(annotation.value())
                    .as("%s must retain its least-privilege contract", methodName)
                    .isEqualTo(permission);
        });
    }
}
