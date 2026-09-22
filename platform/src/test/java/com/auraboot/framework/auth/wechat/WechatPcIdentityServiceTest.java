package com.auraboot.framework.auth.wechat;

import com.auraboot.framework.auth.dao.entity.AuthIdentity;
import com.auraboot.framework.auth.mapper.AuthIdentityMapper;
import com.auraboot.framework.auth.wechat.WechatPcClient.WxWebUser;
import com.auraboot.framework.saas.config.service.SystemModeService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.mapper.UserMapper;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WechatPcIdentityServiceTest {

    @Mock private AuthIdentityMapper authIdentityMapper;
    @Mock private UserMapper userMapper;
    @Mock private WechatPcClient wechatPcClient;
    @Mock private UserService userService;
    @Mock private SystemModeService systemModeService;

    private WechatPcIdentityService service;

    @BeforeEach
    void setUp() {
        WechatPcProperties properties = new WechatPcProperties();
        properties.setAppId("wx-web-test");
        service = new WechatPcIdentityService(authIdentityMapper, userMapper, wechatPcClient,
                properties, userService, systemModeService);
    }

    @Test
    void existingWebIdentityLogsInWithoutCreatingAccount() {
        when(wechatPcClient.exchange("code")).thenReturn(new WxWebUser("OPEN-1", null, null, null));
        AuthIdentity identity = identity(101L, "OPEN-1", null);
        when(authIdentityMapper.selectOne(any())).thenReturn(identity);
        User user = user(101L);
        when(userMapper.selectById(101L)).thenReturn(user);

        assertThat(service.resolveLoginUser("code")).isSameAs(user);
        verify(authIdentityMapper).updateById(identity);
        verifyNoInteractions(userService, systemModeService);
    }

    @Test
    void unionidAttachesWebOpenidToExistingMiniAccount() {
        when(wechatPcClient.exchange("code")).thenReturn(new WxWebUser("OPEN-WEB", "UNION-1", null, null));
        when(authIdentityMapper.selectOne(any())).thenReturn(null, identity(101L, "OPEN-MINI", "UNION-1"));
        User user = user(101L);
        when(userMapper.selectById(101L)).thenReturn(user);

        assertThat(service.resolveLoginUser("code")).isSameAs(user);
        ArgumentCaptor<AuthIdentity> saved = ArgumentCaptor.forClass(AuthIdentity.class);
        verify(authIdentityMapper).insert(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(101L);
        assertThat(saved.getValue().getProvider()).isEqualTo("wechat_web");
        assertThat(saved.getValue().getOpenid()).isEqualTo("OPEN-WEB");
        assertThat(saved.getValue().getUnionid()).isEqualTo("UNION-1");
        verifyNoInteractions(userService, systemModeService);
    }

    @Test
    void unknownWechatCreatesTenantlessAccountWhenSchoolCreationIsSelfService() {
        when(wechatPcClient.exchange("code")).thenReturn(new WxWebUser("OPEN-NEW", "UNION-NEW", null, null));
        when(systemModeService.isTenantSelfProvisioningAllowed()).thenReturn(true);
        User user = user(202L);
        when(userService.signUp(anyString(), anyString(), eq("微信用户"), isNull())).thenReturn(user);

        assertThat(service.resolveLoginUser("code")).isSameAs(user);
        ArgumentCaptor<String> email = ArgumentCaptor.forClass(String.class);
        verify(userService).signUp(email.capture(), anyString(), eq("微信用户"), isNull());
        assertThat(email.getValue()).startsWith("wx-web-").endsWith("@wx.wechat");
        ArgumentCaptor<AuthIdentity> saved = ArgumentCaptor.forClass(AuthIdentity.class);
        verify(authIdentityMapper).insert(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(202L);
        assertThat(saved.getValue().getOpenid()).isEqualTo("OPEN-NEW");
        assertThat(saved.getValue().getUnionid()).isEqualTo("UNION-NEW");
        verifyNoInteractions(userMapper);
    }

    @Test
    void unknownWechatIsRejectedWhenSchoolCreationIsNotSelfService() {
        when(wechatPcClient.exchange("code")).thenReturn(new WxWebUser("OPEN-NEW", null, null, null));

        assertThat(service.resolveLoginUser("code")).isNull();
        verify(systemModeService).isTenantSelfProvisioningAllowed();
        verifyNoInteractions(userService, userMapper);
        verify(authIdentityMapper, never()).insert(any(AuthIdentity.class));
    }

    private static AuthIdentity identity(Long userId, String openid, String unionid) {
        AuthIdentity identity = new AuthIdentity();
        identity.setUserId(userId);
        identity.setOpenid(openid);
        identity.setUnionid(unionid);
        return identity;
    }

    private static User user(Long id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
