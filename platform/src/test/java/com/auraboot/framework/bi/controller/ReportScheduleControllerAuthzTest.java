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
 * B6 / Q13 authz guard test — every report-schedule REST endpoint must declare a
 * permission check. Discovery found ReportScheduleController exposed 6 unguarded
 * endpoints (no {@code @RequirePermission}); this reflection test pins that every
 * mapped endpoint now requires {@link MetaPermission#REPORT_GENERATE}, so the gap
 * cannot silently regress. (Enforcement of the annotation is covered by the
 * platform PermissionInterceptor; this test guards that the annotation is applied.)
 */
class ReportScheduleControllerAuthzTest {

    private static final List<Class<? extends Annotation>> MAPPING_ANNOTATIONS = List.of(
            GetMapping.class, PostMapping.class, PutMapping.class,
            DeleteMapping.class, PatchMapping.class, RequestMapping.class);

    private static boolean isEndpoint(Method method) {
        return MAPPING_ANNOTATIONS.stream().anyMatch(method::isAnnotationPresent);
    }

    private static List<Method> endpoints() {
        return Arrays.stream(ReportScheduleController.class.getDeclaredMethods())
                .filter(ReportScheduleControllerAuthzTest::isEndpoint)
                .toList();
    }

    @Test
    void exposesTheSixCrudEndpoints() {
        assertEquals(6, endpoints().size(),
                "ReportScheduleController should expose exactly 6 mapped endpoints");
    }

    @Test
    void everyEndpointRequiresReportGeneratePermission() {
        boolean classLevel = ReportScheduleController.class.isAnnotationPresent(RequirePermission.class);
        List<Method> endpoints = endpoints();
        assertFalse(endpoints.isEmpty(), "expected report-schedule endpoints");
        for (Method method : endpoints) {
            RequirePermission rp = method.getAnnotation(RequirePermission.class);
            if (rp == null && classLevel) {
                rp = ReportScheduleController.class.getAnnotation(RequirePermission.class);
            }
            assertNotNull(rp,
                    "endpoint '" + method.getName()
                            + "' must declare @RequirePermission (Q13 security gap — schedule CRUD was unguarded)");
            assertEquals(MetaPermission.REPORT_GENERATE, rp.value(),
                    "endpoint '" + method.getName() + "' should require REPORT_GENERATE");
        }
    }
}
