package com.auraboot.framework.view.service;

import com.auraboot.framework.view.mapper.SavedViewMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.DataNotFoundException;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.view.controller.ViewShareController;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ViewShareServiceTest {

    @Mock private SavedViewMapper savedViewMapper;
    @Mock private SavedViewService savedViewService;
    @Mock private PasswordEncoder passwordEncoder;
    @InjectMocks private ViewShareService service;

    @Test
    void createShareLink_generatesTokenAndUrl() {
        when(savedViewMapper.selectRawViewConfigJson("v1")).thenReturn("{}");

        var result = service.createShareLink("v1", null, 24);

        assertNotNull(result.token());
        assertTrue(result.shareUrl().startsWith("/api/views/shared/"));
        assertNotNull(result.expiresAt());
        assertEquals(false, result.passwordProtected());
        var ordered = inOrder(savedViewService, savedViewMapper);
        ordered.verify(savedViewService).checkPublicShareAccess("v1");
        ordered.verify(savedViewMapper).selectRawViewConfigJson("v1");
        ordered.verify(savedViewMapper).updateViewConfigJson(eq("v1"), contains("__share"));
    }

    @Test
    void createShareLink_withPasswordStoresBcryptHash() {
        when(savedViewMapper.selectRawViewConfigJson("v1")).thenReturn("{}");
        when(passwordEncoder.encode("secret")).thenReturn("$2a$10$storedhashvalue");

        var result = service.createShareLink("v1", "secret", null);

        assertEquals(true, result.passwordProtected());
        assertNull(result.expiresAt());
        verify(savedViewMapper).updateViewConfigJson(eq("v1"), contains("$2a$10$storedhashvalue"));
    }

    @Test
    void createShareLink_blankPasswordTreatedAsNoPassword() {
        when(savedViewMapper.selectRawViewConfigJson("v1")).thenReturn("{}");
        var result = service.createShareLink("v1", "  ", null);
        assertEquals(false, result.passwordProtected());
    }

    @Test
    void createShareLink_throwsWhenViewMissing() {
        DataNotFoundException missing = new DataNotFoundException(ResponseCode.NOT_FOUND, "Saved view not found");
        doThrow(missing).when(savedViewService).checkPublicShareAccess("missing");
        assertSame(missing, assertThrows(DataNotFoundException.class,
                () -> service.createShareLink("missing", null, 1)));
        verifyNoInteractions(savedViewMapper);
    }

    @Test
    void revokeShareLink_removesShareKey() {
        when(savedViewMapper.selectRawViewConfigJson("v1"))
                .thenReturn("{\"__share\":{\"token\":\"t\",\"active\":true},\"keep\":1}");
        service.revokeShareLink("v1");
        var ordered = inOrder(savedViewService, savedViewMapper);
        ordered.verify(savedViewService).checkPublicShareAccess("v1");
        ordered.verify(savedViewMapper).selectRawViewConfigJson("v1");
        ordered.verify(savedViewMapper).updateViewConfigJson(eq("v1"), argThat(json -> !json.contains("__share") && json.contains("keep")));
    }

    @Test
    void getShareStatus_returnsSharedFalseWhenAbsent() {
        when(savedViewMapper.selectRawViewConfigJson("v1")).thenReturn("{}");
        var r = service.getShareStatus("v1");
        assertEquals(false, r.shared());
        assertNull(r.token());
    }

    @Test
    void getShareStatus_returnsSharedTrueWithMeta() {
        when(savedViewMapper.selectRawViewConfigJson("v1"))
                .thenReturn("{\"__share\":{\"token\":\"abc\",\"active\":true,\"passwordHash\":\"h\",\"expiresAt\":\"2099-01-01T00:00:00Z\"}}");
        var r = service.getShareStatus("v1");
        assertEquals(true, r.shared());
        assertEquals("abc", r.token());
        var ordered = inOrder(savedViewService, savedViewMapper);
        ordered.verify(savedViewService).checkPublicShareAccess("v1");
        ordered.verify(savedViewMapper).selectRawViewConfigJson("v1");
        assertEquals(true, r.passwordProtected());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "[]", "\"not-an-object\"", "null"})
    void getShareStatus_rejectsCorruptConfigurationWithoutWritingAnEmptyReplacement(String raw) {
        when(savedViewMapper.selectRawViewConfigJson("v1")).thenReturn(raw);
        assertThrows(IllegalStateException.class, () -> service.getShareStatus("v1"));
        verify(savedViewMapper, never()).updateViewConfigJson(anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "[]", "\"not-an-object\"", "null"})
    void corruptConfigurationCannotBeOverwrittenByShareOrRevoke(String raw) {
        when(savedViewMapper.selectRawViewConfigJson("v1")).thenReturn(raw);
        assertThrows(IllegalStateException.class, () -> service.createShareLink("v1", null, null));
        assertThrows(IllegalStateException.class, () -> service.revokeShareLink("v1"));
        verify(savedViewMapper, never()).updateViewConfigJson(anyString(), anyString());
    }

    @Test
    void accessSharedView_throwsWhenTokenNotFound() {
        when(savedViewMapper.findRawViewByShareToken("t")).thenReturn(null);
        assertThrows(RuntimeException.class, () -> service.accessSharedView("t", null));
    }

    @Test
    void accessSharedView_throwsWhenInactive() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("name", "v");
        meta.put("modelcode", "M");
        meta.put("viewtype", "list");
        meta.put("viewconfigraw", "{\"__share\":{\"active\":false}}");
        when(savedViewMapper.findRawViewByShareToken("t")).thenReturn(meta);
        RuntimeException e = assertThrows(RuntimeException.class, () -> service.accessSharedView("t", null));
        assertTrue(e.getMessage().contains("revoked"));
    }

    @Test
    void accessSharedView_throwsWhenExpired() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("name", "v");
        meta.put("viewconfigraw",
                "{\"__share\":{\"active\":true,\"expiresAt\":\"" + Instant.now().minus(1, ChronoUnit.HOURS) + "\"}}");
        when(savedViewMapper.findRawViewByShareToken("t")).thenReturn(meta);
        RuntimeException e = assertThrows(RuntimeException.class, () -> service.accessSharedView("t", null));
        assertTrue(e.getMessage().contains("expired"));
    }

    @Test
    void accessSharedView_passwordMismatchThrows() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("name", "v");
        meta.put("viewconfigraw", "{\"__share\":{\"active\":true,\"passwordHash\":\"" + Integer.toHexString("right".hashCode()) + "\"}}");
        when(savedViewMapper.findRawViewByShareToken("t")).thenReturn(meta);
        RuntimeException e = assertThrows(RuntimeException.class, () -> service.accessSharedView("t", "wrong"));
        assertTrue(e.getMessage().contains("Invalid password"));
    }

    @Test
    void accessSharedView_passwordMissingThrows() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("viewconfigraw", "{\"__share\":{\"active\":true,\"passwordHash\":\"x\"}}");
        when(savedViewMapper.findRawViewByShareToken("t")).thenReturn(meta);
        assertThrows(RuntimeException.class, () -> service.accessSharedView("t", null));
    }

    @Test
    void accessSharedView_returnsViewWithoutShareMeta() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("name", "Saved");
        meta.put("modelcode", "User");
        meta.put("viewtype", "list");
        meta.put("viewconfigraw", "{\"__share\":{\"active\":true},\"foo\":\"bar\"}");
        when(savedViewMapper.findRawViewByShareToken("t")).thenReturn(meta);

        var r = service.accessSharedView("t", null);
        assertEquals("Saved", r.name());
        verifyNoInteractions(savedViewService);
        assertEquals("User", r.modelCode());
        assertEquals("list", r.viewType());
        Map<String, Object> cfg = r.viewConfig();
        assertFalse(cfg.containsKey("__share"));
        assertEquals("bar", cfg.get("foo"));
    }

    @Test
    void accessSharedView_bcryptPasswordVerified() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("viewconfigraw", "{\"__share\":{\"active\":true,\"passwordHash\":\"$2a$10$stored\"}}");
        when(savedViewMapper.findRawViewByShareToken("t")).thenReturn(meta);
        when(passwordEncoder.matches("pw", "$2a$10$stored")).thenReturn(true);

        assertNotNull(service.accessSharedView("t", "pw"));
    }

    @Test
    void accessSharedView_bcryptPasswordMismatchThrows() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("viewconfigraw", "{\"__share\":{\"active\":true,\"passwordHash\":\"$2a$10$stored\"}}");
        when(savedViewMapper.findRawViewByShareToken("t")).thenReturn(meta);
        when(passwordEncoder.matches("wrong", "$2a$10$stored")).thenReturn(false);

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.accessSharedView("t", "wrong"));
        assertTrue(e.getMessage().contains("Invalid password"));
    }

    @Test
    void accessSharedView_legacyHexHashStillAccepted() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("viewconfigraw", "{\"__share\":{\"active\":true,\"passwordHash\":\"" + Integer.toHexString("pw".hashCode()) + "\"}}");
        when(savedViewMapper.findRawViewByShareToken("t")).thenReturn(meta);

        assertNotNull(service.accessSharedView("t", "pw"));
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void accessSharedView_emptyMetaTreatedAsNotFound() {
        when(savedViewMapper.findRawViewByShareToken("t")).thenReturn(new HashMap<>());
        assertThrows(RuntimeException.class, () -> service.accessSharedView("t", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "revoke", "status"})
    void authenticatedShareOperations_denyBeforeReadingOrChangingToken(String operation) {
        ValidationException denied = new ValidationException(ResponseCode.FORBIDDEN);
        doThrow(denied).when(savedViewService).checkPublicShareAccess("v1");
        ValidationException actual = assertThrows(ValidationException.class, () -> {
            switch (operation) {
                case "create" -> service.createShareLink("v1", null, null);
                case "revoke" -> service.revokeShareLink("v1");
                case "status" -> service.getShareStatus("v1");
                default -> throw new AssertionError(operation);
            }
        });
        assertSame(denied, actual);
        verifyNoInteractions(savedViewMapper);
    }

    @Test
    void authenticatedEndpointsRequirePublicSharingButTokenAccessRemainsPublic() throws Exception {
        for (String name : new String[]{"shareView", "revokeShare", "getShareStatus"}) {
            var method = java.util.Arrays.stream(ViewShareController.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(name)).findFirst().orElseThrow();
            RequirePermission guard = method.getAnnotation(RequirePermission.class);
            assertNotNull(guard, name);
            assertEquals(MetaPermission.VIEW_PUBLIC_SHARE, guard.value(), name);
        }
        assertNull(ViewShareController.class.getDeclaredMethod("accessSharedView", String.class, String.class)
                .getAnnotation(RequirePermission.class));
    }

}
