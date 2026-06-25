package com.auraboot.framework.user.controller;

import com.auraboot.framework.auth.service.PasswordManagementService;
import com.auraboot.framework.auth.service.PasswordPolicyService;
import com.auraboot.framework.user.service.EmployeeAccountProvisioningService;
import com.auraboot.framework.user.service.EmployeeAccountWorkbookParser;
import com.auraboot.framework.user.service.UserProvisioningService;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AdminUserControllerPasswordResetTest {

    @Mock
    private PasswordManagementService passwordManagementService;
    @Mock
    private PasswordPolicyService passwordPolicyService;
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
        org.mockito.Mockito.when(passwordPolicyService.isValid(any(String.class))).thenReturn(true);

        var response = controller.resetPassword("u-pid");

        String tempPassword = response.getData().get("tempPassword");
        assertThat(tempPassword).hasSize(12);
        verify(passwordManagementService).resetPasswordByAdmin(eq("u-pid"), any(String.class));
    }
}
