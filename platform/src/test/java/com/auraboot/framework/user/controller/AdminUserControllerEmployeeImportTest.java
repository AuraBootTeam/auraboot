package com.auraboot.framework.user.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.service.PasswordManagementService;
import com.auraboot.framework.auth.service.PasswordPolicyService;
import com.auraboot.framework.user.dto.EmployeeAccountImportPreviewResponse;
import com.auraboot.framework.user.dto.EmployeeAccountRow;
import com.auraboot.framework.user.service.EmployeeAccountProvisioningService;
import com.auraboot.framework.user.service.EmployeeAccountWorkbookParser;
import com.auraboot.framework.user.service.UserProvisioningService;
import com.auraboot.framework.user.service.UserService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminUserControllerEmployeeImportTest {

    private final EmployeeAccountProvisioningService provisioningService =
            mock(EmployeeAccountProvisioningService.class);
    private final EmployeeAccountWorkbookParser workbookParser = new EmployeeAccountWorkbookParser();
    private AdminUserController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminUserController(
                mock(PasswordManagementService.class),
                mock(PasswordPolicyService.class),
                mock(UserProvisioningService.class),
                provisioningService,
                workbookParser,
                mock(UserService.class));
        MetaContext.setContext(7L, 42L, "usr_admin", "admin");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void templateEndpointReturnsAnOpenableSixColumnXlsx() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.downloadEmployeeAccountTemplate(response);

        assertThat(response.getContentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(response.getHeader("Content-Disposition"))
                .contains("attachment")
                .contains("user-import-template.xlsx")
                .contains("filename*=UTF-8''");
        assertThat(response.getContentAsByteArray()).startsWith((byte) 0x50, (byte) 0x4b);
        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(response.getContentAsByteArray()))) {
            var header = workbook.getSheet("账号导入").getRow(1);
            assertThat(List.of(
                    header.getCell(0).getStringCellValue(),
                    header.getCell(1).getStringCellValue(),
                    header.getCell(2).getStringCellValue(),
                    header.getCell(3).getStringCellValue(),
                    header.getCell(4).getStringCellValue(),
                    header.getCell(5).getStringCellValue()))
                    .containsExactly("姓名*", "登录名", "手机号", "工号", "部门编码", "岗位编码");
        }
    }

    @Test
    void previewEndpointParsesTheSameTemplateOnTheServerBeforeValidation() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        workbookParser.writeTemplate(output);
        EmployeeAccountImportPreviewResponse preview = EmployeeAccountImportPreviewResponse.builder()
                .totalRows(0)
                .validCount(0)
                .errorCount(0)
                .rows(List.of())
                .build();
        when(provisioningService.preview(anyList(), eq(7L))).thenReturn(preview);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "用户导入模板.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                output.toByteArray());

        var response = controller.previewEmployeeAccounts(file);

        assertThat(response.getData()).isSameAs(preview);
        verify(provisioningService).preview(anyList(), eq(7L));
    }
}
