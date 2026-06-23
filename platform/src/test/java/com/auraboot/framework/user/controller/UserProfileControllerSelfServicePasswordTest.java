package com.auraboot.framework.user.controller;

import com.auraboot.framework.auth.service.PasswordManagementService;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.user.dto.ChangePasswordRequest;
import com.auraboot.framework.user.service.UserProfileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.auraboot.framework.common.constant.ResponseCode.FORBIDDEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserProfileControllerSelfServicePasswordTest {

    @Mock
    private UserProfileService userProfileService;
    @Mock
    private FileService fileService;
    @Mock
    private PasswordManagementService passwordManagementService;

    @InjectMocks
    private UserProfileController controller;

    @Test
    void changePassword_throwsForbiddenWhenSelfServiceDisabled() {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setCurrentPassword("old");
        request.setNewPassword("jjzz@1234");
        request.setConfirmPassword("jjzz@1234");

        assertThatThrownBy(() -> controller.changePassword(1L, request))
                .isInstanceOf(RootUnCheckedException.class)
                .satisfies(error -> assertThat(((RootUnCheckedException) error).getResponseCode()).isEqualTo(FORBIDDEN));
        verify(passwordManagementService, never()).changePassword(1L, "old", "jjzz@1234");
    }
}
