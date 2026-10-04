package com.auraboot.framework.i18n.service;

import com.auraboot.framework.i18n.entity.I18nResource;
import com.auraboot.framework.i18n.mapper.I18nResourceMapper;
import com.auraboot.framework.i18n.service.impl.I18nResourceServiceImpl;
import com.auraboot.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import com.auraboot.framework.application.tenant.MetaContext;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class I18nResourcePatchTest {
    @BeforeEach
    void context() { MetaContext.setContext(100L, 1L, "test-user", "test-user"); }

    @AfterEach
    void clearContext() { MetaContext.clear(); }
    @Test
    void editingValuePreservesStatusAndSourceMetadata() {
        I18nResourceMapper mapper = mock(I18nResourceMapper.class);
        I18nResourceServiceImpl service = new I18nResourceServiceImpl(mapper);
        I18nResource existing = I18nResource.builder().pid("p1").value("Old").status("review")
                .source("import").refType("model").refId(42L).build();
        when(mapper.selectByPid("p1")).thenReturn(existing);
        I18nResource updated = service.update("p1", I18nResource.builder().value("New").build());
        assertThat(updated.getValue()).isEqualTo("New");
        assertThat(updated.getStatus()).isEqualTo("review");
        assertThat(updated.getSource()).isEqualTo("import");
        assertThat(updated.getRefType()).isEqualTo("model");
        assertThat(updated.getRefId()).isEqualTo(42L);
        verify(mapper).updateById(existing);
    }

    @Test
    void invalidStatusCannotMutateResource() {
        I18nResourceMapper mapper = mock(I18nResourceMapper.class);
        I18nResourceServiceImpl service = new I18nResourceServiceImpl(mapper);
        I18nResource existing = I18nResource.builder().pid("p1").value("Translation").status("review").build();
        when(mapper.selectByPid("p1")).thenReturn(existing);
        assertThatThrownBy(() -> service.update("p1", I18nResource.builder().value("Must not replace").status("rejected").build()))
                .isInstanceOf(BusinessException.class);
        assertThat(existing.getStatus()).isEqualTo("review");
        assertThat(existing.getValue()).isEqualTo("Translation");
        verify(mapper, never()).updateById(any(I18nResource.class));
    }

    @Test
    void explicitEmptyValueIsAnEditRatherThanAnOmittedField() {
        I18nResourceMapper mapper = mock(I18nResourceMapper.class);
        I18nResourceServiceImpl service = new I18nResourceServiceImpl(mapper);
        I18nResource existing = I18nResource.builder().pid("p1").value("Old").status("draft").build();
        when(mapper.selectByPid("p1")).thenReturn(existing);
        assertThat(service.update("p1", I18nResource.builder().value("").build()).getValue()).isEmpty();
        assertThat(existing.getStatus()).isEqualTo("draft");
    }
}
