package com.auraboot.framework.permission.interceptor;

import com.auraboot.framework.agent.controller.ApsSchedulingController;
import com.auraboot.framework.agent.controller.PlatformAiController;
import com.auraboot.framework.bpm.controller.OrchestrationController;
import com.auraboot.framework.bpm.controller.SagaController;
import com.auraboot.framework.agent.nlmodeling.controller.NlModelingController;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.organization.controller.OrgController;
import com.auraboot.framework.organization.controller.TeamController;
import com.auraboot.framework.versioning.controller.VersionHistoryController;
import com.auraboot.framework.view.controller.ViewShareController;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.controller.SubjectPermissionController;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.plugin.controller.PluginPackageController;
import com.auraboot.framework.plugin.controller.PluginTransactionalImportController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Guards added by the 2026-06-18 deep review (security findings DR-20260618-D1-perm-*).
 *
 * <p>These controllers previously had no {@code @RequirePermission} annotation, so the
 * {@link PermissionInterceptor} fail-open default ({@code annotation == null → return true})
 * let any authenticated user reach destructive / privilege-affecting endpoints:
 * plugin install/uninstall ({@link PluginPackageController}, {@link PluginTransactionalImportController}),
 * permission-declaration writes ({@link SubjectPermissionController#addPermission}), and NL
 * modeling apply ({@link NlModelingController}).
 *
 * <p>This test drives the real interceptor with a {@link HandlerMethod} built from the real
 * controller methods, so it fails if anyone removes the annotation again — and proves a user
 * lacking the permission is denied while a holder is allowed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeepReviewControllerGuardTest {

    @Mock
    private UserPermissionService userPermissionService;
    @Mock
    private MenuMapper menuMapper;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;

    private PermissionInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new PermissionInterceptor(userPermissionService, menuMapper);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        MetaContext.clear();
    }

    private void assertGuard(Class<?> controller, String methodName, String expectedCode) throws Exception {
        HandlerMethod hm = handlerFor(controller, methodName);
        authenticate(7L);

        // Denied when the user lacks the code.
        when(userPermissionService.hasPermission(7L, expectedCode)).thenReturn(false);
        assertThatThrownBy(() -> interceptor.preHandle(request, response, hm))
                .as("%s#%s must be denied without %s", controller.getSimpleName(), methodName, expectedCode)
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("permissionCode: " + expectedCode);

        // Allowed when the user holds the code (tenant_admin holds it via the `*` template grant).
        when(userPermissionService.hasPermission(7L, expectedCode)).thenReturn(true);
        assertThat(interceptor.preHandle(request, response, hm)).isTrue();
    }

    @Test
    @DisplayName("PluginPackageController is guarded by plugin.plugin.manage")
    void pluginPackageControllerGuarded() throws Exception {
        assertGuard(PluginPackageController.class, "uninstall", "plugin.plugin.manage");
    }

    @Test
    @DisplayName("PluginTransactionalImportController is guarded by plugin.plugin.manage")
    void pluginTransactionalImportControllerGuarded() throws Exception {
        assertGuard(PluginTransactionalImportController.class, "importPlugin", "plugin.plugin.manage");
    }

    @Test
    @DisplayName("SubjectPermissionController.addPermission is guarded by meta.permission.update")
    void subjectPermissionAddGuarded() throws Exception {
        assertGuard(SubjectPermissionController.class, "addPermission", MetaPermission.PERMISSION_MANAGE);
    }

    @Test
    @DisplayName("SubjectPermissionController.removeAllPermissions is guarded by meta.permission.update")
    void subjectPermissionRemoveAllGuarded() throws Exception {
        assertGuard(SubjectPermissionController.class, "removeAllPermissions", MetaPermission.PERMISSION_MANAGE);
    }

    @Test
    @DisplayName("NlModelingController is guarded by meta.model.update")
    void nlModelingControllerGuarded() throws Exception {
        assertGuard(NlModelingController.class, "apply", MetaPermission.MODEL_MANAGE);
    }

    @Test
    @DisplayName("ApsSchedulingController is guarded by meta.manufacturing.aps")
    void apsSchedulingControllerGuarded() throws Exception {
        assertGuard(ApsSchedulingController.class, "runSchedule", MetaPermission.MANUFACTURING_APS);
    }

    @Test
    @DisplayName("PlatformAiController.scoreRecords is guarded by ai.scoring.run")
    void platformAiScoreRecordsGuarded() throws Exception {
        assertGuard(PlatformAiController.class, "scoreRecords", MetaPermission.AI_SCORING_RUN);
    }

    @Test
    @DisplayName("OrgController.createDepartment is guarded by org.team.manage")
    void orgControllerWriteGuarded() throws Exception {
        assertGuard(OrgController.class, "createDepartment", "org.team.manage");
    }

    @Test
    @DisplayName("TeamController.createTeam is guarded by org.team.manage")
    void teamControllerWriteGuarded() throws Exception {
        assertGuard(TeamController.class, "createTeam", "org.team.manage");
    }

    @Test
    @DisplayName("VersionHistoryController.rollback is guarded by dashboard.manage")
    void versionHistoryRollbackGuarded() throws Exception {
        assertGuard(VersionHistoryController.class, "rollback", "dashboard.manage");
    }

    @Test
    @DisplayName("ViewShareController.shareView is guarded by dashboard.manage")
    void viewShareGuarded() throws Exception {
        assertGuard(ViewShareController.class, "shareView", "dashboard.manage");
    }

    @Test
    @DisplayName("OrchestrationController.startExecution is guarded by bpm.process.execute")
    void orchestrationGuarded() throws Exception {
        assertGuard(OrchestrationController.class, "startExecution", "bpm.process.execute");
    }

    @Test
    @DisplayName("SagaController.retrySaga is guarded by bpm.process.execute")
    void sagaRetryGuarded() throws Exception {
        assertGuard(SagaController.class, "retrySaga", "bpm.process.execute");
    }

    // ---- helpers ----

    private HandlerMethod handlerFor(Class<?> controller, String methodName) {
        Object bean = instantiateWithNulls(controller);
        Method method = Arrays.stream(controller.getMethods())
                .filter(m -> m.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "method not found: " + controller.getSimpleName() + "#" + methodName));
        return new HandlerMethod(bean, method);
    }

    /** Annotation inspection only — dependencies are never invoked, so nulls are safe. */
    private Object instantiateWithNulls(Class<?> c) {
        try {
            Constructor<?> ctor = c.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            Object[] args = new Object[ctor.getParameterCount()];
            return ctor.newInstance(args);
        } catch (Exception e) {
            throw new RuntimeException("cannot instantiate " + c.getName(), e);
        }
    }

    private void authenticate(Long userId) {
        CustomUserDetails details = new CustomUserDetails(
                "u", "p", userId, "pid", null, true, true, true, true);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(details, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
