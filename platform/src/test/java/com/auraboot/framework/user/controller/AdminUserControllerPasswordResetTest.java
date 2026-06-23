package com.auraboot.framework.user.controller;

import com.auraboot.framework.auth.service.PasswordManagementService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.mapper.UserMapper;
import com.auraboot.framework.user.service.EmployeeAccountProvisioningService;
import com.auraboot.framework.user.service.EmployeeAccountWorkbookParser;
import com.auraboot.framework.user.service.UserProvisioningService;
import com.auraboot.framework.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserControllerPasswordResetTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private PasswordManagementService passwordManagementService;
    @Mock
    private UserProvisioningService userProvisioningService;
    @Mock
    private EmployeeAccountProvisioningService employeeAccountProvisioningService;
    @Mock
    private EmployeeAccountWorkbookParser employeeAccountWorkbookParser;
    @Mock
    private UserService userService;

    @InjectMocks
    private AdminUserController controller;

    @Test
    void resetPassword_keepsPasswordAdminManaged() {
        User user = new User();
        user.setPid("u-pid");
        user.setSecurityVersion(2);
        when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(user);
        when(passwordEncoder.encode(any())).thenReturn("encoded");

        controller.resetPassword("u-pid");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(captor.getValue().getPassword()).isEqualTo("encoded");
        assertThat(captor.getValue().getMustChangePassword()).isFalse();
        assertThat(captor.getValue().getSecurityVersion()).isEqualTo(3);
    }
}
