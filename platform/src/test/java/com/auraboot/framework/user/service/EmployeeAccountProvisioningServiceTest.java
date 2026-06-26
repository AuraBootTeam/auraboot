package com.auraboot.framework.user.service;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.user.dto.EmployeeAccountProvisionRequest;
import com.auraboot.framework.user.dto.EmployeeAccountProvisionResponse;
import com.auraboot.framework.user.dto.EmployeeAccountRow;
import com.auraboot.framework.user.dto.UserProvisionRequest;
import com.auraboot.framework.user.dto.UserProvisionResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmployeeAccountProvisioningServiceTest {

    @Mock
    private UserProvisioningService userProvisioningService;
    @Mock
    private UserService userService;
    @Mock
    private RoleService roleService;

    @InjectMocks
    private EmployeeAccountProvisioningService service;

    @Test
    void provision_mapsEmployeeTypesAndGeneratesCustomerPasswords() {
        when(roleService.findByTenantId(7L)).thenReturn(List.of(
                role(1L, "tenant_admin"),
                role(2L, "bom_operator"),
                role(3L, "qo_quoter")
        ));
        when(userProvisioningService.provision(any(), eq(7L), eq(100L)))
                .thenAnswer(invocation -> response(invocation.getArgument(0)));

        EmployeeAccountProvisionResponse result = service.provision(request(List.of(
                row("吴书生", "管理员"),
                row("袁称磊", "销售"),
                row("刘星梅", "采购"),
                row("邓康铭", "工程")
        )), 7L, 100L);

        assertThat(result.getAccounts()).hasSize(4);
        assertThat(result.getAccounts())
                .extracting(EmployeeAccountProvisionResponse.Account::getUserName)
                .containsExactly("吴书生", "袁称磊", "刘星梅", "邓康铭");
        assertThat(result.getAccounts())
                .extracting(EmployeeAccountProvisionResponse.Account::getInitialPassword)
                .allMatch(password -> password.matches("jjzz@\\d{4}"));
        assertThat(result.getAccounts())
                .extracting(EmployeeAccountProvisionResponse.Account::isMustChangePassword)
                .containsOnly(false);

        ArgumentCaptor<UserProvisionRequest> captor = ArgumentCaptor.forClass(UserProvisionRequest.class);
        verify(userProvisioningService, org.mockito.Mockito.times(4)).provision(captor.capture(), eq(7L), eq(100L));
        assertThat(captor.getAllValues())
                .extracting(UserProvisionRequest::getRoleCodes)
                .containsExactly(
                        List.of("tenant_admin"),
                        List.of("bom_operator", "qo_quoter"),
                        List.of("bom_operator", "qo_quoter"),
                        List.of("bom_operator")
                );
    }

    @Test
    void provision_missingMappedRoleFailsBeforeCreatingUsers() {
        when(roleService.findByTenantId(7L)).thenReturn(List.of(role(1L, "qo_quoter")));

        assertThatThrownBy(() -> service.provision(request(List.of(row("袁称磊", "销售"))), 7L, 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("bom_operator");
        verify(userProvisioningService, never()).provision(any(), any(), any());
    }

    @Test
    void provision_customRoleMappingOverridesDefaultTypeRoles() {
        when(roleService.findByTenantId(7L)).thenReturn(List.of(role(9L, "custom_sales")));
        when(userProvisioningService.provision(any(), eq(7L), eq(100L)))
                .thenAnswer(invocation -> response(invocation.getArgument(0)));
        EmployeeAccountProvisionRequest request = request(List.of(row("袁称磊", "销售")));
        request.setRoleMapping(Map.of("销售", List.of("custom_sales")));

        service.provision(request, 7L, 100L);

        ArgumentCaptor<UserProvisionRequest> captor = ArgumentCaptor.forClass(UserProvisionRequest.class);
        verify(userProvisioningService).provision(captor.capture(), eq(7L), eq(100L));
        assertThat(captor.getValue().getRoleCodes()).containsExactly("custom_sales");
    }

    @Test
    void provision_duplicateNamesInSameBatchAreRejected() {
        assertThatThrownBy(() -> service.provision(request(List.of(
                row("吴书生", "管理员"),
                row(" 吴书生 ", "管理员")
        )), 7L, 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Duplicate employee name");
        verify(userProvisioningService, never()).provision(any(), any(), any());
    }

    @Test
    void provision_existingUserNameIsRejectedBeforeCreatingUsers() {
        when(userService.findByUserName("吴书生")).thenReturn(new com.auraboot.framework.user.dao.entity.User());

        assertThatThrownBy(() -> service.provision(request(List.of(row("吴书生", "管理员"))), 7L, 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("User already exists");
        verify(userProvisioningService, never()).provision(any(), any(), any());
    }

    @Test
    void provision_rejectsTooLongPasswordPrefixBeforeCreatingUsers() {
        EmployeeAccountProvisionRequest request = request(List.of(row("吴书生", "管理员")));
        request.setPasswordPrefix("x".repeat(33));

        assertThatThrownBy(() -> service.provision(request, 7L, 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("passwordPrefix");
        verify(userProvisioningService, never()).provision(any(), any(), any());
    }

    private EmployeeAccountProvisionRequest request(List<EmployeeAccountRow> rows) {
        EmployeeAccountProvisionRequest request = new EmployeeAccountProvisionRequest();
        request.setEmployees(rows);
        return request;
    }

    private EmployeeAccountRow row(String name, String type) {
        EmployeeAccountRow row = new EmployeeAccountRow();
        row.setName(name);
        row.setType(type);
        return row;
    }

    private Role role(Long id, String code) {
        Role role = new Role();
        role.setId(id);
        role.setCode(code);
        return role;
    }

    private UserProvisionResponse response(UserProvisionRequest request) {
        return UserProvisionResponse.builder()
                .userId(1L)
                .userPid("u-pid")
                .displayName(request.getDisplayName())
                .assignedRoles(request.getRoleCodes())
                .mustChangePassword(false)
                .temporaryPassword(null)
                .build();
    }
}
