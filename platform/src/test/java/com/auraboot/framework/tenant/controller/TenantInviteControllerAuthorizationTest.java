package com.auraboot.framework.tenant.controller;

import com.auraboot.framework.permission.annotation.RequirePermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TenantInviteControllerAuthorizationTest {

    @Test
    @DisplayName("current invite code requires tenant invite management permission")
    void currentInviteCodeIsNotVisibleToOrdinaryMembers() throws Exception {
        Method method = TenantInviteController.class.getMethod("getCurrentInviteCode", Long.class);
        RequirePermission permission = method.getAnnotation(RequirePermission.class);

        assertEquals("org.tenant.invite.manage", permission.value());
    }
}
