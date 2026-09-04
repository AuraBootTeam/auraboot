package com.auraboot.framework.user.controller;

import com.auraboot.framework.auth.service.PasswordManagementService;
import com.auraboot.framework.auth.service.PasswordPolicyService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.user.dto.BatchPasswordResetItem;
import com.auraboot.framework.user.dto.BatchPasswordResetRequest;
import com.auraboot.framework.user.service.EmployeeAccountProvisioningService;
import com.auraboot.framework.user.service.EmployeeAccountWorkbookParser;
import com.auraboot.framework.user.service.UserProvisioningService;
import com.auraboot.framework.user.service.UserService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserControllerBatchPasswordResetTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

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
    void batchReset_returnsOneTempPasswordPerUser() {
        when(passwordPolicyService.isValid(any(String.class))).thenReturn(true);

        var response = controller.batchResetPassword(
            new BatchPasswordResetRequest(List.of("u-1", "u-2", "u-3")));

        List<BatchPasswordResetItem> items = response.getData();
        assertThat(items).hasSize(3);
        assertThat(items).extracting(BatchPasswordResetItem::userPid)
            .containsExactly("u-1", "u-2", "u-3");
        assertThat(items).allSatisfy(item -> assertThat(item.tempPassword()).hasSize(12));
        verify(passwordManagementService, times(3)).resetPasswordByAdmin(anyString(), anyString());
        verify(passwordManagementService).resetPasswordByAdmin(eq("u-2"), anyString());
    }

    @Test
    void batchReset_generatesDistinctPasswords() {
        when(passwordPolicyService.isValid(any(String.class))).thenReturn(true);

        var response = controller.batchResetPassword(
            new BatchPasswordResetRequest(List.of("u-1", "u-2", "u-3", "u-4", "u-5")));

        List<String> passwords = response.getData().stream()
            .map(BatchPasswordResetItem::tempPassword).toList();
        assertThat(passwords).doesNotHaveDuplicates();
    }

    @Test
    void batchResetRequest_rejectsEmptyAndOversizedLists() {
        assertThat(validator.validate(new BatchPasswordResetRequest(List.of()))).isNotEmpty();
        List<String> oversize = IntStream.rangeClosed(1, 101)
            .mapToObj(i -> "u-" + i).toList();
        assertThat(validator.validate(new BatchPasswordResetRequest(oversize))).isNotEmpty();
        List<String> atLimit = IntStream.rangeClosed(1, 100)
            .mapToObj(i -> "u-" + i).toList();
        assertThat(validator.validate(new BatchPasswordResetRequest(atLimit))).isEmpty();
    }

    @Test
    void batchReset_propagatesServiceFailure() {
        when(passwordPolicyService.isValid(any(String.class))).thenReturn(true);
        doNothing().when(passwordManagementService).resetPasswordByAdmin(eq("u-1"), anyString());
        doThrow(new BusinessException("user not found"))
            .when(passwordManagementService).resetPasswordByAdmin(eq("u-bad"), anyString());

        assertThatThrownBy(() -> controller.batchResetPassword(
            new BatchPasswordResetRequest(List.of("u-1", "u-bad"))))
            .isInstanceOf(BusinessException.class);
        verify(passwordManagementService).resetPasswordByAdmin(eq("u-1"), anyString());
    }
}
