package com.auraboot.framework.auth.wechat;

import com.auraboot.framework.auth.dao.entity.AuthIdentity;
import com.auraboot.framework.auth.mapper.AuthIdentityMapper;
import com.auraboot.framework.auth.wechat.WechatMiniClient.WxSession;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.mapper.UserMapper;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the WeChat identity resolution and binding rules: openid-first
 * resolution, unionid cross-app attachment, unbound-null (login rejects with a
 * bind-first hint), and bind conflict/idempotency.
 */
@ExtendWith(MockitoExtension.class)
class WechatMiniIdentityServiceTest {

    private static final Long USER_A = 101L;
    private static final Long USER_B = 202L;

    @Mock
    private AuthIdentityMapper authIdentityMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private WechatMiniClient wechatMiniClient;

    @Mock
    private UserService userService;

    private WechatMiniIdentityService service;

    @BeforeEach
    void setUp() {
        var properties = new WechatMiniProperties();
        properties.setAppId("wx-test-appid");
        properties.setAppSecret("test-secret");
        service = new WechatMiniIdentityService(authIdentityMapper, userMapper, wechatMiniClient, properties, userService);
    }

    private void stubSession(String openid, String unionid) {
        when(wechatMiniClient.code2Session(anyString()))
                .thenReturn(new WxSession(openid, unionid));
    }

    private AuthIdentity identity(Long userId, String openid, String unionid) {
        AuthIdentity i = new AuthIdentity();
        i.setPid("PID-" + openid);
        i.setUserId(userId);
        i.setProvider("wechat_mini");
        i.setOpenid(openid);
        i.setUnionid(unionid);
        return i;
    }

    private void stubUser(Long id) {
        User u = new User();
        u.setId(id);
        when(userMapper.selectById(id)).thenReturn(u);
    }

    @Test
    void openidHitLogsThatUserIn() {
        stubSession("OPEN-A", null);
        when(authIdentityMapper.selectOne(any())).thenReturn(identity(USER_A, "OPEN-A", null));
        stubUser(USER_A);

        User user = service.resolveLoginUser("jscode");

        assertThat(user.getId()).isEqualTo(USER_A);
        verify(authIdentityMapper, never()).insert(any(AuthIdentity.class));
    }

    @Test
    void unionidAttachesNewOpenidToSameUser() {
        stubSession("OPEN-B", "UNION-1");
        // no openid hit (first arg null); unionid query returns user A's identity.
        // Stage-2b: the unionid match resolves to the SAME user AND materializes the new
        // openid (createIdentity, mirroring WechatPcIdentityService) so future logins
        // resolve directly without depending on the unionid claim.
        when(authIdentityMapper.selectOne(any()))
                .thenReturn(null)                    // openid lookup
                .thenReturn(identity(USER_A, "OPEN-A", "UNION-1")); // unionid lookup
        stubUser(USER_A);

        User user = service.resolveLoginUser("jscode");

        assertThat(user.getId()).isEqualTo(USER_A);
        ArgumentCaptor<AuthIdentity> captor = ArgumentCaptor.forClass(AuthIdentity.class);
        verify(authIdentityMapper).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(USER_A);
        assertThat(captor.getValue().getOpenid()).isEqualTo("OPEN-B");
        assertThat(captor.getValue().getUnionid()).isEqualTo("UNION-1");
    }

    @Test
    void unboundWechatResolvesToNull() {
        stubSession("OPEN-NEW", null);
        when(authIdentityMapper.selectOne(any())).thenReturn(null);

        assertThat(service.resolveLoginUser("jscode")).isNull();
        verify(authIdentityMapper, never()).insert(any(AuthIdentity.class));
    }

    @Test
    void bindConflictRejected() {
        stubSession("OPEN-B", null);
        when(authIdentityMapper.selectOne(any()))
                .thenReturn(identity(USER_A, "OPEN-B", null));

        assertThatThrownBy(() -> service.bindToUser("jscode", USER_B))
                .isInstanceOf(RootUnCheckedException.class)
                .hasMessageContaining("already bound");
        verify(authIdentityMapper, never()).insert(any(AuthIdentity.class));
    }

    @Test
    void bindToOwnUserIsIdempotent() {
        stubSession("OPEN-A", null);
        when(authIdentityMapper.selectOne(any()))
                .thenReturn(identity(USER_A, "OPEN-A", null));

        service.bindToUser("jscode", USER_A);

        verify(authIdentityMapper, never()).insert(any(AuthIdentity.class));
    }

    @Test
    void bindCreatesIdentityForUser() throws Exception {
        stubSession("OPEN-C", "UNION-2");
        when(authIdentityMapper.selectOne(any())).thenReturn(null);

        service.bindToUser("jscode", USER_B);

        ArgumentCaptor<AuthIdentity> captor = ArgumentCaptor.forClass(AuthIdentity.class);
        verify(authIdentityMapper).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(USER_B);
        assertThat(captor.getValue().getOpenid()).isEqualTo("OPEN-C");
        assertThat(java.util.Optional.ofNullable(captor.getValue().getUnionid())).contains("UNION-2");
    }
}
