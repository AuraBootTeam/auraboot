package com.auraboot.framework.meta.handler;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.service.CommandHandlerContext;
import com.auraboot.framework.organization.dto.EmployeeAccountProvisionResponse;
import com.auraboot.framework.organization.service.OrgEmployeeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrgEmployeeCommandHandlerTest {

    @Mock
    private OrgEmployeeService orgEmployeeService;

    @InjectMocks
    private OrgEmployeeCommandHandler handler;

    @Test
    void getHandlerName_returnsOrgEmployeeCommandHandler() {
        assertThat(handler.getHandlerName()).isEqualTo("orgEmployeeCommandHandler");
    }

    @Test
    void execute_openEmployeeAccount_returnsTemporaryPasswordAndLinkData() {
        when(orgEmployeeService.openAccount("emp-001")).thenReturn(EmployeeAccountProvisionResponse.builder()
                .employeePid("emp-001")
                .userPid("usr-001")
                .memberPid("mem-001")
                .email("alice@example.com")
                .userName("emp_emp001")
                .displayName("Alice")
                .createdUser(true)
                .createdMember(true)
                .adminManaged(true)
                .temporaryPassword("TempPass1!")
                .assignedRoles(List.of("member"))
                .build());

        Map<String, Object> result = handler.execute(CommandHandlerContext.builder()
                .commandCode("org:open_employee_account")
                .targetRecordId("emp-001")
                .userId(100L)
                .build());

        assertThat(result.get("handlerExecuted")).isEqualTo(true);
        assertThat(result.get("action")).isEqualTo("open_employee_account");
        assertThat(result.get("employeePid")).isEqualTo("emp-001");
        assertThat(result.get("userPid")).isEqualTo("usr-001");
        assertThat(result.get("memberPid")).isEqualTo("mem-001");
        assertThat(result.get("tempPassword")).isEqualTo("TempPass1!");
        assertThat(result.get("createdUser")).isEqualTo(true);
        assertThat(result.get("createdMember")).isEqualTo(true);
        assertThat(result.get("assignedRoles")).isEqualTo(List.of("member"));
        verify(orgEmployeeService).openAccount("emp-001");
    }

    @Test
    void execute_openEmployeeAccount_usesPayloadPidFallback() {
        when(orgEmployeeService.openAccount("emp-from-payload"))
                .thenReturn(EmployeeAccountProvisionResponse.builder()
                        .employeePid("emp-from-payload")
                        .userPid("usr-001")
                        .memberPid("mem-001")
                        .adminManaged(true)
                        .assignedRoles(List.of())
                        .build());

        handler.execute(CommandHandlerContext.builder()
                .commandCode("org:open_employee_account")
                .targetRecordId(" ")
                .payload(Map.of("employeePid", "emp-from-payload"))
                .userId(100L)
                .build());

        verify(orgEmployeeService).openAccount("emp-from-payload");
    }

    @Test
    void execute_unknownCommand_returnsHandlerExecutedFalse() {
        Map<String, Object> result = handler.execute(CommandHandlerContext.builder()
                .commandCode("org:unknown")
                .targetRecordId("emp-001")
                .userId(100L)
                .build());

        assertThat(result.get("handlerExecuted")).isEqualTo(false);
        verifyNoInteractions(orgEmployeeService);
    }

    @Test
    void execute_missingEmployeePid_throwsBusinessException() {
        assertThatThrownBy(() -> handler.execute(CommandHandlerContext.builder()
                .commandCode("org:open_employee_account")
                .userId(100L)
                .build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("employeePid");
    }
}
