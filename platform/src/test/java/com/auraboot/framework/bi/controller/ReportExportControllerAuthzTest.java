package com.auraboot.framework.bi.controller;

import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * B6 authz guard test — every report export REST endpoint must require
 * {@link MetaPermission#REPORT_EXPORT_EXECUTE}, so the guard cannot silently
 * regress. Mirrors {@code ReportScheduleControllerAuthzTest}; the B5 coverage
 * matrix (2026-06-21) found {@code ReportExportController} was the only report
 * controller without an authz test (definition + schedule already had one), even
 * though export emits files and is audited. Enforcement of the annotation is
 * covered by the platform PermissionInterceptor; this test guards that the
 * annotation is applied to every endpoint.
 */
class ReportExportControllerAuthzTest {

    private static final List<Class<? extends Annotation>> MAPPING_ANNOTATIONS = List.of(
            GetMapping.class, PostMapping.class, PutMapping.class,
            DeleteMapping.class, PatchMapping.class, RequestMapping.class);

    private static boolean isEndpoint(Method method) {
        return MAPPING_ANNOTATIONS.stream().anyMatch(method::isAnnotationPresent);
    }

    private static List<Method> endpoints() {
        return Arrays.stream(ReportExportController.class.getDeclaredMethods())
                .filter(ReportExportControllerAuthzTest::isEndpoint)
                .toList();
    }

    @Test
    void exposesTheThreeExportEndpoints() {
        assertEquals(3, endpoints().size(),
                "ReportExportController should expose exactly 3 mapped endpoints (excel/pdf/json)");
    }

    @Test
    void everyEndpointRequiresReportExportExecutePermission() {
        boolean classLevel = ReportExportController.class.isAnnotationPresent(RequirePermission.class);
        List<Method> endpoints = endpoints();
        assertFalse(endpoints.isEmpty(), "expected report export endpoints");
        for (Method method : endpoints) {
            RequirePermission rp = method.getAnnotation(RequirePermission.class);
            if (rp == null && classLevel) {
                rp = ReportExportController.class.getAnnotation(RequirePermission.class);
            }
            assertNotNull(rp,
                    "endpoint '" + method.getName()
                            + "' must declare @RequirePermission (report export must be guarded)");
            assertEquals(MetaPermission.REPORT_EXPORT_EXECUTE, rp.value(),
                    "endpoint '" + method.getName() + "' should require REPORT_EXPORT_EXECUTE");
        }
    }
}
