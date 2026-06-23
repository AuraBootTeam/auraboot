package com.auraboot.framework.user.service.impl;

import com.auraboot.framework.auth.service.PasswordManagementService;
import com.auraboot.framework.auth.service.PasswordPolicyService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private UserMapper userMapper;
    @Mock
    private PasswordManagementService passwordManagementService;
    @Mock
    private PasswordPolicyService passwordPolicyService;

    @InjectMocks
    private UserServiceImpl service;

    @Test
    void signUp_userNameOnlyCreatesAccountWithoutEmail() throws Exception {
        when(userMapper.selectOne(any())).thenReturn(null);
        when(passwordPolicyService.validate("jjzz@1234")).thenReturn(List.of());
        when(passwordEncoder.encode("jjzz@1234")).thenReturn("encoded");
        when(userMapper.insert(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(10L);
            return 1;
        });
        User persisted = new User();
        persisted.setId(10L);
        persisted.setPid("u-pid");
        persisted.setUserName("吴书生");
        when(userMapper.selectById(10L)).thenReturn(persisted);

        User result = service.signUp(null, "jjzz@1234", "吴书生", "吴书生");

        assertThat(result).isSameAs(persisted);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        User inserted = captor.getValue();
        assertThat(inserted.getEmail()).isNull();
        assertThat(inserted.getUserName()).isEqualTo("吴书生");
        assertThat(inserted.getNickName()).isEqualTo("吴书生");
        assertThat(inserted.getPassword()).isEqualTo("encoded");
    }

    @Test
    void signUp_missingEmailAndUserNameThrowsBusinessException() {
        assertThatThrownBy(() -> service.signUp(" ", "jjzz@1234", "Display", " "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Email or user name is required");
    }
}
