package com.auraboot.framework.im.service;

import com.auraboot.framework.im.mapper.ImNotificationPreferenceMapper;
import com.auraboot.framework.im.model.ImNotificationPreference;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ImNotificationPreferenceService}.
 */
@ExtendWith(MockitoExtension.class)
class ImNotificationPreferenceServiceTest {

    @Mock private ImNotificationPreferenceMapper mapper;

    @InjectMocks private ImNotificationPreferenceService service;

    @Test
    void isEnabled_returns_true_when_no_preference() {
        when(mapper.findMostSpecific(1L, 10L, "crm_lead", "state_transition")).thenReturn(null);
        assertThat(service.isEnabled(10L, 1L, "crm_lead", "state_transition")).isTrue();
    }

    @Test
    void isEnabled_returns_preference_value_when_disabled() {
        ImNotificationPreference pref = new ImNotificationPreference();
        pref.setEnabled(false);
        when(mapper.findMostSpecific(1L, 10L, "crm_lead", "state_transition")).thenReturn(pref);
        assertThat(service.isEnabled(10L, 1L, "crm_lead", "state_transition")).isFalse();
    }

    @Test
    void isEnabled_returns_preference_value_when_enabled() {
        ImNotificationPreference pref = new ImNotificationPreference();
        pref.setEnabled(true);
        when(mapper.findMostSpecific(1L, 10L, null, null)).thenReturn(pref);
        assertThat(service.isEnabled(10L, 1L, null, null)).isTrue();
    }

    @Test
    void listByUser_delegates_to_mapper() {
        ImNotificationPreference p = new ImNotificationPreference();
        when(mapper.findByUser(1L, 10L)).thenReturn(List.of(p));
        assertThat(service.listByUser(10L, 1L)).hasSize(1);
    }

    @Test
    void setPreference_updates_existing() {
        ImNotificationPreference existing = new ImNotificationPreference();
        existing.setId(99L);
        existing.setEnabled(true);
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);

        ImNotificationPreference saved = service.setPreference(10L, 1L, "crm_lead", "state_transition", false);

        assertThat(saved).isSameAs(existing);
        assertThat(saved.getEnabled()).isFalse();
        verify(mapper).updateById(existing);
        verify(mapper, never()).insert(any(ImNotificationPreference.class));
    }

    @Test
    void setPreference_inserts_new_when_no_existing() {
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        ImNotificationPreference saved = service.setPreference(10L, 1L, "crm_lead", "custom", true);

        assertThat(saved.getUserId()).isEqualTo(10L);
        assertThat(saved.getTenantId()).isEqualTo(1L);
        assertThat(saved.getModelCode()).isEqualTo("crm_lead");
        assertThat(saved.getOperationType()).isEqualTo("custom");
        assertThat(saved.getEnabled()).isTrue();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        verify(mapper).insert(saved);
    }

    @Test
    void setPreference_handles_null_modelCode_and_operationType_for_global_default() {
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        ImNotificationPreference saved = service.setPreference(10L, 1L, null, null, false);

        assertThat(saved.getModelCode()).isNull();
        assertThat(saved.getOperationType()).isNull();
        assertThat(saved.getEnabled()).isFalse();
        verify(mapper).insert(saved);
    }

    @Test
    void deletePreference_calls_mapper_delete() {
        service.deletePreference(99L, 10L, 1L);
        verify(mapper).delete(any(QueryWrapper.class));
    }
}
