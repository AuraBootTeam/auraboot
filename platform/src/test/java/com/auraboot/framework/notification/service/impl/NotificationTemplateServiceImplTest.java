package com.auraboot.framework.notification.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.notification.dto.NotificationTemplateCreateRequest;
import com.auraboot.framework.notification.entity.NotificationTemplate;
import com.auraboot.framework.notification.mapper.NotificationTemplateMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationTemplateServiceImpl")
class NotificationTemplateServiceImplTest {

    @Mock private NotificationTemplateMapper templateMapper;

    @InjectMocks
    private NotificationTemplateServiceImpl service;

    private MockedStatic<MetaContext> metaContextMock;

    @BeforeEach
    void setUp() {
        metaContextMock = Mockito.mockStatic(MetaContext.class);
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
    }

    @AfterEach
    void tearDown() {
        if (metaContextMock != null) metaContextMock.close();
    }

    private NotificationTemplateCreateRequest req() {
        NotificationTemplateCreateRequest r = new NotificationTemplateCreateRequest();
        r.setCode("welcome");
        r.setName("Welcome");
        r.setChannel("EMAIL");
        r.setSubjectTemplate("Hi ${name}");
        r.setBodyTemplate("Hello ${name}, welcome to ${tenant}");
        r.setVariables("name,tenant");
        r.setEnabled(true);
        return r;
    }

    @Test
    @DisplayName("create populates entity from request and inserts")
    void createPersists() {
        NotificationTemplate result = service.create(req());
        assertNotNull(result);
        assertEquals(99L, result.getTenantId());
        assertEquals("welcome", result.getCode());
        assertNotNull(result.getPid());
        verify(templateMapper).insert(result);
    }

    @Test
    @DisplayName("getByCode delegates to mapper with tenant scope")
    void getByCode() {
        NotificationTemplate t = new NotificationTemplate();
        when(templateMapper.findByCode(99L, "welcome")).thenReturn(t);
        assertEquals(t, service.getByCode("welcome"));
    }

    @Test
    @DisplayName("listByChannel and listAll delegate")
    void listByChannelAndAll() {
        when(templateMapper.findByChannel(99L, "EMAIL")).thenReturn(List.of(new NotificationTemplate()));
        when(templateMapper.findAll(99L)).thenReturn(List.of(new NotificationTemplate(), new NotificationTemplate()));
        assertEquals(1, service.listByChannel("EMAIL").size());
        assertEquals(2, service.listAll().size());
    }

    @Test
    @DisplayName("update overwrites existing fields and persists")
    void updateOk() {
        NotificationTemplate existing = new NotificationTemplate();
        existing.setPid("p1");
        when(templateMapper.findByPid(99L, "p1")).thenReturn(existing);

        NotificationTemplate updated = service.update("p1", req());
        assertEquals("welcome", updated.getCode());
        verify(templateMapper).updateById(existing);
    }

    @Test
    @DisplayName("update throws when template missing")
    void updateMissing() {
        when(templateMapper.findByPid(99L, "p1")).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.update("p1", req()));
    }

    @Test
    @DisplayName("delete delegates by pid")
    void delete() {
        service.delete("p1");
        verify(templateMapper).deleteByPid(99L, "p1");
    }

    @Test
    @DisplayName("renderPreview throws when template missing")
    void renderPreviewMissing() {
        when(templateMapper.findByCode(99L, "x")).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.renderPreview("x", Map.of()));
    }

    @Test
    @DisplayName("renderPreview substitutes ${var} with values")
    void renderPreviewSubstitutes() {
        NotificationTemplate t = new NotificationTemplate();
        t.setBodyTemplate("Hello ${name} at ${tenant}, missing=${missing}");
        when(templateMapper.findByCode(99L, "x")).thenReturn(t);

        String rendered = service.renderPreview("x", Map.of("name", "Alice", "tenant", "Acme"));
        assertEquals("Hello Alice at Acme, missing=", rendered);
    }

    @Test
    @DisplayName("renderTemplate returns input untouched for null/empty inputs")
    void renderTemplateEdgeCases() {
        assertNull(service.renderTemplate(null, Map.of("a", 1)));
        assertEquals("abc", service.renderTemplate("abc", null));
        assertEquals("abc", service.renderTemplate("abc", Map.of()));
    }

    @Test
    @DisplayName("renderTemplate quotes replacement to handle $ and \\ in values")
    void renderTemplateQuotedReplacement() {
        String out = service.renderTemplate("X=${v}", Map.of("v", "$1\\foo"));
        assertEquals("X=$1\\foo", out);
    }
}
