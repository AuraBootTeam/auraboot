package com.auraboot.framework.auth.strategy;
import com.auraboot.framework.auth.wechat.*;
import com.auraboot.framework.auth.dto.AuthStrategyRequest;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class WechatMiniAuthStrategyTest {
    @Test void configuredEnrollmentCannotBeBypassedByOmittingPhoneFields(){
        var identities=mock(WechatMiniIdentityService.class);var completion=mock(LoginCompletionHelper.class);var enrollment=mock(WechatMiniPhoneEnrollmentService.class);
        when(enrollment.isConfigured()).thenReturn(true);var request=new AuthStrategyRequest();request.setCode("login-only");
        when(enrollment.login(request)).thenThrow(new IllegalArgumentException("Missing phone authorization"));
        assertThrows(IllegalArgumentException.class,()->new WechatMiniAuthStrategy(identities,completion,enrollment).authenticate(request));
        verify(enrollment).login(request);verifyNoInteractions(identities,completion);
    }
    @Test void otherMiniApplicationsKeepTheirOwnLoginPolicy(){
        var identities=mock(WechatMiniIdentityService.class);var completion=mock(LoginCompletionHelper.class);var enrollment=mock(WechatMiniPhoneEnrollmentService.class);
        var request=new AuthStrategyRequest();request.setCode("login-only");
        new WechatMiniAuthStrategy(identities,completion,enrollment).authenticate(request);
        verify(identities).selfProvision("login-only");verify(enrollment,never()).login(any());
    }
}
