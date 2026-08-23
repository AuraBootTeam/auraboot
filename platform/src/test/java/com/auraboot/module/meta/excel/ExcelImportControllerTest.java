package com.auraboot.module.meta.excel;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.i18n.util.I18nLocaleResolver;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExcelImportControllerTest {

    private final ExcelImportService importService = mock(ExcelImportService.class);
    private final ExcelImportController controller = new ExcelImportController(
            importService,
            mock(ExcelValidationEngine.class),
            mock(ExcelImportPolicyResolver.class),
            mock(ExcelImportErrorReportService.class),
            mock(I18nLocaleResolver.class));

    @Test
    void cancelImport_delegatesModelAndPublicTaskIdentifier() {
        ExcelImportService.AsyncImportStatus status = new ExcelImportService.AsyncImportStatus();
        status.setTaskId("01KCANCEL");
        status.setModelCode("crm_account_common");
        status.setStatus("running");
        when(importService.cancelImport("crm_account_common", "01KCANCEL")).thenReturn(status);

        ApiResponse<ExcelImportService.AsyncImportStatus> response =
                controller.cancelImport("crm_account_common", "01KCANCEL");

        assertEquals(status, response.getData());
        verify(importService).cancelImport("crm_account_common", "01KCANCEL");
    }

    @Test
    void cancelImport_returnsFailClosedEnvelopeForUnknownOwnedTask() {
        when(importService.cancelImport("crm_account_common", "01KUNKNOWN")).thenReturn(null);

        ApiResponse<ExcelImportService.AsyncImportStatus> response =
                controller.cancelImport("crm_account_common", "01KUNKNOWN");

        assertNull(response.getData());
    }

    @Test
    void cancelImport_contractKeepsDynamicImportPermission() throws Exception {
        Method method = ExcelImportController.class.getMethod(
                "cancelImport", String.class, String.class);

        assertEquals("/import/{modelCode}/cancel/{taskId}",
                method.getAnnotation(PostMapping.class).value()[0]);
        assertEquals("model.{modelCode}.import",
                method.getAnnotation(RequirePermission.class).value());
    }
}
