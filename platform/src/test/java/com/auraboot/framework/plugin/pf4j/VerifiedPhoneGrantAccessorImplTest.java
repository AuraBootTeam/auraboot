package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.wechat.WechatMiniClient;
import com.auraboot.framework.auth.wechat.WechatMiniIdentityService;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.plugin.extension.VerifiedPhoneGrantAccessor;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Hermetic request-capability tests; provider and identity persistence require real-stack evidence. */
class VerifiedPhoneGrantAccessorImplTest {
    @AfterEach void clear() { MetaContext.clear(); }
    private Map<String,Object> settings() {
        return new HashMap<>(Map.of(VerifiedPhoneGrantAccessor.OPT_IN_HANDLER_PARAM, true));
    }
    private VerifiedPhoneGrantAccessor accessor(WechatMiniIdentityService service) {
        MetaContext.setContext(1L,2L,"actor-a","Actor");
        var settings=settings();
        VerifiedPhoneGrantAccessorImpl.install(settings,1L,2L,false,service);
        return (VerifiedPhoneGrantAccessor)settings.get(VerifiedPhoneGrantAccessor.SETTINGS_KEY);
    }
    private WechatMiniClient.VerifiedPhone phone(String country,String national) {
        return new WechatMiniClient.VerifiedPhone("app","openid",country,national,1791730000L);
    }
    @Test void onlyLiteralOptInInstallsAndForgedSettingsAreAlwaysRemoved() {
        var service=mock(WechatMiniIdentityService.class);
        for(Object opt:Arrays.asList(null,false,"true",1)) {
            var settings=settings(); settings.put(VerifiedPhoneGrantAccessor.OPT_IN_HANDLER_PARAM,opt);
            settings.put(VerifiedPhoneGrantAccessor.SETTINGS_KEY,new Object());
            VerifiedPhoneGrantAccessorImpl.install(settings,1L,2L,false,service);
            assertFalse(settings.containsKey(VerifiedPhoneGrantAccessor.SETTINGS_KEY));
        }
        for(boolean dry:List.of(true,false)) {
            var settings=settings(); settings.put(VerifiedPhoneGrantAccessor.SETTINGS_KEY,new Object());
            VerifiedPhoneGrantAccessorImpl.install(settings,1L,2L,dry,dry?service:null);
            assertFalse(settings.containsKey(VerifiedPhoneGrantAccessor.SETTINGS_KEY));
        }
        verifyNoInteractions(service);
    }
    @Test void absentOrMismatchedAuthenticationCannotInstall() {
        var service=mock(WechatMiniIdentityService.class);
        assertThrows(SecurityException.class,()->VerifiedPhoneGrantAccessorImpl.install(settings(),1L,2L,false,service));
        MetaContext.setContext(1L,2L,"actor-a","Actor");
        for(Long tenant:Arrays.asList(null,3L))
            assertThrows(SecurityException.class,()->VerifiedPhoneGrantAccessorImpl.install(settings(),tenant,2L,false,service));
        for(Long user:Arrays.asList(null,0L,3L))
            assertThrows(SecurityException.class,()->VerifiedPhoneGrantAccessorImpl.install(settings(),1L,user,false,service));
        MetaContext.setContext(1L,2L,null,"Actor");
        assertThrows(SecurityException.class,()->VerifiedPhoneGrantAccessorImpl.install(settings(),1L,2L,false,service));
        verifyNoInteractions(service);
    }
    @Test void anyActorTenantPidOrImpersonationChangeRejectsBeforeProvider() {
        var service=mock(WechatMiniIdentityService.class); var cap=accessor(service);
        MetaContext.setContext(3L,2L,"actor-a","Actor");
        assertThrows(SecurityException.class,()->cap.verifyCurrentPhone("login","phone"));
        MetaContext.setContext(1L,3L,"actor-a","Actor");
        assertThrows(SecurityException.class,()->cap.verifyCurrentPhone("login","phone"));
        MetaContext.setContext(1L,2L,"actor-b","Actor");
        assertThrows(SecurityException.class,()->cap.verifyCurrentPhone("login","phone"));
        MetaContext.setContext(1L,2L,"actor-a","Actor");
        MetaContext.setSessionContext(null,null,"tenant",null,null,"active",1L,true,9L,null);
        assertThrows(SecurityException.class,()->cap.verifyCurrentPhone("login","phone"));
        assertThrows(SecurityException.class,()->VerifiedPhoneGrantAccessorImpl.install(settings(),1L,2L,false,service));
        MetaContext.clear();
        assertThrows(SecurityException.class,()->cap.verifyCurrentPhone("login","phone"));
        verifyNoInteractions(service);
    }
    @Test void verifiesExactCodesReturnsE164AndConsumesOnce() {
        var service=mock(WechatMiniIdentityService.class); var cap=accessor(service);
        when(service.verifyCurrentUserPhoneGrant("fresh-login","fresh-phone")).thenReturn(phone("86","13800138000"));
        assertEquals("+8613800138000",cap.verifyCurrentPhone("fresh-login","fresh-phone"));
        assertEquals("PHONE_GRANT_ALREADY_CONSUMED",assertThrows(SecurityException.class,
                ()->cap.verifyCurrentPhone("another-login","another-phone")).getMessage());
        verify(service).verifyCurrentUserPhoneGrant("fresh-login","fresh-phone"); verifyNoMoreInteractions(service);
    }
    @Test void missingCodesDoNotConsumeCapability() {
        var service=mock(WechatMiniIdentityService.class); var cap=accessor(service);
        for(String missing:Arrays.asList(null,""," ")) {
            assertThrows(IllegalArgumentException.class,()->cap.verifyCurrentPhone(missing,"phone"));
            assertThrows(IllegalArgumentException.class,()->cap.verifyCurrentPhone("login",missing));
        }
        verifyNoInteractions(service);
        when(service.verifyCurrentUserPhoneGrant("login","phone")).thenReturn(phone("1","2025550100"));
        assertEquals("+12025550100",cap.verifyCurrentPhone("login","phone"));
    }
    @Test void rejectedProviderIsMaskedAndStillConsumesCapability() {
        var service=mock(WechatMiniIdentityService.class); var cap=accessor(service);
        when(service.verifyCurrentUserPhoneGrant("login","phone")).thenThrow(
                new RootUnCheckedException(ResponseCode.values()[0],"sensitive-provider-fragment"));
        var failure=assertThrows(SecurityException.class,()->cap.verifyCurrentPhone("login","phone"));
        assertEquals("PHONE_GRANT_REJECTED",failure.getMessage()); assertNull(failure.getCause());
        assertFalse(failure.toString().contains("sensitive-provider-fragment"));
        assertEquals("PHONE_GRANT_ALREADY_CONSUMED",assertThrows(SecurityException.class,
                ()->cap.verifyCurrentPhone("login","phone")).getMessage());
        verify(service,times(1)).verifyCurrentUserPhoneGrant("login","phone");
    }
    @Test void malformedVerifiedPhoneCannotEscape() {
        for(var invalid:Arrays.asList(null,phone(null,"13800138000"),phone("86",null),phone("0","13800138000"),
                phone("+86","13800138000"),phone("86","12"),phone("86","13800x38000"),phone("1234","12345678901234"))) {
            var service=mock(WechatMiniIdentityService.class); var cap=accessor(service);
            when(service.verifyCurrentUserPhoneGrant("login","phone")).thenReturn(invalid);
            assertEquals("PHONE_GRANT_REJECTED",assertThrows(SecurityException.class,
                    ()->cap.verifyCurrentPhone("login","phone")).getMessage());
            verify(service).verifyCurrentUserPhoneGrant("login","phone");
        }
    }
}
