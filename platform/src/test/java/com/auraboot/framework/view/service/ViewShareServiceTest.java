package com.auraboot.framework.view.service;

import com.auraboot.framework.view.entity.SavedView;
import com.auraboot.framework.view.mapper.SavedViewMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    @InjectMocks private ViewShareService service;

    private void mockExists(String pid) {
        SavedView v = new SavedView();
        v.setPid(pid);
        when(savedViewMapper.selectOne(any(QueryWrapper.class))).thenReturn(v);
    }

    @Test
    void createShareLink_generatesTokenAndUrl() {
        mockExists("v1");
        when(savedViewMapper.selectRawViewConfigJson("v1")).thenReturn("{}");

        Map<String, Object> result = service.createShareLink("v1", null, 24);

        assertNotNull(result.get("token"));
        assertTrue(((String) result.get("shareUrl")).startsWith("/api/views/shared/"));
        assertNotNull(result.get("expiresAt"));
        assertEquals(false, result.get("passwordProtected"));
        verify(savedViewMapper).updateViewConfigJson(eq("v1"), contains("__share"));
    }

    @Test
    void createShareLink_withPasswordSetsHash() {
        mockExists("v1");
        when(savedViewMapper.selectRawViewConfigJson("v1")).thenReturn("{}");

        Map<String, Object> result = service.createShareLink("v1", "secret", null);

        assertEquals(true, result.get("passwordProtected"));
        assertNull(result.get("expiresAt"));
        verify(savedViewMapper).updateViewConfigJson(eq("v1"), contains("passwordHash"));
    }

    @Test
    void createShareLink_blankPasswordTreatedAsNoPassword() {
        mockExists("v1");
        when(savedViewMapper.selectRawViewConfigJson("v1")).thenReturn("{}");
        Map<String, Object> result = service.createShareLink("v1", "  ", null);
        assertEquals(false, result.get("passwordProtected"));
    }

    @Test
    void createShareLink_throwsWhenViewMissing() {
        when(savedViewMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        assertThrows(RuntimeException.class, () -> service.createShareLink("missing", null, 1));
    }

    @Test
    void revokeShareLink_removesShareKey() {
        mockExists("v1");
        when(savedViewMapper.selectRawViewConfigJson("v1"))
                .thenReturn("{\"__share\":{\"token\":\"t\",\"active\":true},\"keep\":1}");
        service.revokeShareLink("v1");
        verify(savedViewMapper).updateViewConfigJson(eq("v1"), argThat(json -> !json.contains("__share")));
    }

    @Test
    void getShareStatus_returnsSharedFalseWhenAbsent() {
        when(savedViewMapper.selectRawViewConfigJson("v1")).thenReturn("{}");
        Map<String, Object> r = service.getShareStatus("v1");
        assertEquals(false, r.get("shared"));
        assertNull(r.get("token"));
    }

    @Test
    void getShareStatus_returnsSharedTrueWithMeta() {
        when(savedViewMapper.selectRawViewConfigJson("v1"))
                .thenReturn("{\"__share\":{\"token\":\"abc\",\"active\":true,\"passwordHash\":\"h\",\"expiresAt\":\"2099-01-01T00:00:00Z\"}}");
        Map<String, Object> r = service.getShareStatus("v1");
        assertEquals(true, r.get("shared"));
        assertEquals("abc", r.get("token"));
        assertEquals(true, r.get("passwordProtected"));
    }

    @Test
    void getShareStatus_handlesInvalidJsonGracefully() {
        when(savedViewMapper.selectRawViewConfigJson("v1")).thenReturn("not-json");
        Map<String, Object> r = service.getShareStatus("v1");
        assertEquals(false, r.get("shared"));
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

        Map<String, Object> r = service.accessSharedView("t", null);
        assertEquals("Saved", r.get("name"));
        assertEquals("User", r.get("modelCode"));
        assertEquals("list", r.get("viewType"));
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = (Map<String, Object>) r.get("viewConfig");
        assertFalse(cfg.containsKey("__share"));
        assertEquals("bar", cfg.get("foo"));
    }

    @Test
    void accessSharedView_correctPasswordSucceeds() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("viewconfigraw", "{\"__share\":{\"active\":true,\"passwordHash\":\"" + Integer.toHexString("pw".hashCode()) + "\"}}");
        when(savedViewMapper.findRawViewByShareToken("t")).thenReturn(meta);
        Map<String, Object> r = service.accessSharedView("t", "pw");
        assertNotNull(r);
    }

    @Test
    void accessSharedView_emptyMetaTreatedAsNotFound() {
        when(savedViewMapper.findRawViewByShareToken("t")).thenReturn(new HashMap<>());
        assertThrows(RuntimeException.class, () -> service.accessSharedView("t", null));
    }
}
