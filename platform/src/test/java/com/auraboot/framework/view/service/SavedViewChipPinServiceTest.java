package com.auraboot.framework.view.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.view.dto.ChipPinDTO;
import com.auraboot.framework.view.entity.SavedViewChipPin;
import com.auraboot.framework.view.mapper.SavedViewChipPinMapper;
import com.auraboot.framework.view.service.impl.SavedViewChipPinServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SavedViewChipPinServiceTest {

    @Mock
    private SavedViewChipPinMapper chipPinMapper;

    @InjectMocks
    private SavedViewChipPinServiceImpl service;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(1L, 100L, "user-pid-1", "tester");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("pinPersonal inserts a personal pin when none exists")
    void pinPersonalInsertsWhenAbsent() {
        when(chipPinMapper.selectOne(any())).thenReturn(null);

        service.pinPersonal("view-1", "e2et_order", "e2et_order_list", 3);

        ArgumentCaptor<SavedViewChipPin> captor = ArgumentCaptor.forClass(SavedViewChipPin.class);
        verify(chipPinMapper).insert(captor.capture());
        SavedViewChipPin pin = captor.getValue();
        assertThat(pin.getScope()).isEqualTo("personal");
        assertThat(pin.getUserId()).isEqualTo("user-pid-1");
        assertThat(pin.getTenantId()).isEqualTo(1L);
        assertThat(pin.getViewPid()).isEqualTo("view-1");
        assertThat(pin.getModelCode()).isEqualTo("e2et_order");
        assertThat(pin.getSortOrder()).isEqualTo(3);
        assertThat(pin.getPid()).isNotBlank();
        assertThat(pin.getCreatedBy()).isEqualTo("user-pid-1");
    }

    @Test
    @DisplayName("pinPersonal is idempotent — updates order instead of inserting a duplicate")
    void pinPersonalIsIdempotent() {
        SavedViewChipPin existing = new SavedViewChipPin();
        existing.setId(5L);
        existing.setSortOrder(1);
        when(chipPinMapper.selectOne(any())).thenReturn(existing);

        service.pinPersonal("view-1", "e2et_order", "e2et_order_list", 9);

        verify(chipPinMapper, never()).insert(any(SavedViewChipPin.class));
        ArgumentCaptor<SavedViewChipPin> updated = ArgumentCaptor.forClass(SavedViewChipPin.class);
        verify(chipPinMapper).updateById(updated.capture());
        assertThat(updated.getValue().getSortOrder()).isEqualTo(9);
    }

    @Test
    @DisplayName("unpinPersonal deletes the current user's pin")
    void unpinPersonalDeletes() {
        service.unpinPersonal("view-1");
        verify(chipPinMapper).delete(any());
    }

    @Test
    @DisplayName("listEffectivePins maps rows to {viewPid, order}")
    void listEffectivePinsMapsRows() {
        SavedViewChipPin p = new SavedViewChipPin();
        p.setViewPid("view-1");
        p.setSortOrder(2);
        when(chipPinMapper.selectList(any())).thenReturn(List.of(p));

        List<ChipPinDTO> pins = service.listEffectivePins("e2et_order", "e2et_order_list");

        assertThat(pins).hasSize(1);
        assertThat(pins.get(0).viewPid()).isEqualTo("view-1");
        assertThat(pins.get(0).order()).isEqualTo(2);
    }

    @Test
    @DisplayName("pinPersonal fails fast without a user in context")
    void pinPersonalRequiresUser() {
        MetaContext.clear();
        assertThatThrownBy(() -> service.pinPersonal("view-1", "e2et_order", "e2et_order_list", 1))
                .isInstanceOf(IllegalStateException.class);
    }
}
