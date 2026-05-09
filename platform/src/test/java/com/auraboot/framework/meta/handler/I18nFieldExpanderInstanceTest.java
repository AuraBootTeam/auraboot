package com.auraboot.framework.meta.handler;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito tests for {@link I18nFieldExpander#expandI18nFields(Model)}.
 * Static helpers covered separately by {@link I18nFieldExpanderTest}.
 */
@ExtendWith(MockitoExtension.class)
class I18nFieldExpanderInstanceTest {

    @Mock private MetaFieldMapper metaFieldMapper;
    @Mock private MetaModelFieldBindingMapper fieldBindingMapper;

    @InjectMocks private I18nFieldExpander expander;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(100L, 1L, "u-pid", "alice");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    private Model model(Long id, String code) {
        Model m = new Model();
        m.setId(id);
        m.setCode(code);
        m.setPid("model-pid");
        return m;
    }

    private Field field(Long id, String code, boolean i18nEnabled) {
        Field f = new Field();
        f.setId(id);
        f.setCode(code);
        f.setDataType("STRING");
        if (i18nEnabled) {
            FieldFeatureBean feature = new FieldFeatureBean();
            feature.setI18nEnabled(true);
            f.setFeature(feature);
        }
        return f;
    }

    private ModelFieldBinding binding(Long modelId, Long fieldId) {
        ModelFieldBinding b = new ModelFieldBinding();
        b.setModelId(modelId);
        b.setFieldId(fieldId);
        return b;
    }

    @Test
    void no_bindings_returns_empty_list() {
        Model m = model(10L, "user");
        when(fieldBindingMapper.findByModelId(10L)).thenReturn(Collections.emptyList());

        List<String> created = expander.expandI18nFields(m);

        assertThat(created).isEmpty();
        verify(metaFieldMapper, never()).insertIdempotent(any());
    }

    @Test
    void null_bindings_returns_empty_list() {
        Model m = model(11L, "user");
        when(fieldBindingMapper.findByModelId(11L)).thenReturn(null);

        List<String> created = expander.expandI18nFields(m);

        assertThat(created).isEmpty();
        verify(metaFieldMapper, never()).findByIds(any());
    }

    @Test
    void no_i18n_enabled_fields_returns_empty() {
        Model m = model(12L, "user");
        when(fieldBindingMapper.findByModelId(12L))
                .thenReturn(List.of(binding(12L, 100L)));
        when(metaFieldMapper.findByIds(List.of(100L)))
                .thenReturn(List.of(field(100L, "name", false)));

        List<String> created = expander.expandI18nFields(m);

        assertThat(created).isEmpty();
        verify(metaFieldMapper, never()).insertIdempotent(any());
    }

    @Test
    void creates_three_companion_fields_for_single_i18n_field() {
        Model m = model(13L, "product");
        Field source = field(200L, "title", true);
        when(fieldBindingMapper.findByModelId(13L))
                .thenReturn(List.of(binding(13L, 200L)));
        when(metaFieldMapper.findByIds(List.of(200L))).thenReturn(List.of(source));
        // simulate insert assigning id back via callback would be MyBatis-Plus default;
        // here we rely on the post-insert "if id == null" branch finding via findCurrentByCode.
        when(metaFieldMapper.insertIdempotent(any())).thenAnswer(inv -> {
            Field f = inv.getArgument(0);
            f.setId(null); // force re-fetch path
            return 0;
        });
        when(metaFieldMapper.findCurrentByCode(any())).thenAnswer(inv -> {
            Field reused = new Field();
            reused.setId(System.nanoTime()); // unique
            reused.setPid("ref-pid");
            return reused;
        });
        when(fieldBindingMapper.countByModelAndField(eq(13L), anyLong())).thenReturn(0);
        when(fieldBindingMapper.getMaxFieldOrder(13L)).thenReturn(5);

        List<String> created = expander.expandI18nFields(m);

        assertThat(created).containsExactly(
                "title_en_us", "title_ja_jp", "title_ko_kr");
        verify(metaFieldMapper, times(3)).insertIdempotent(any());
        verify(fieldBindingMapper, times(3)).insert(any(ModelFieldBinding.class));
    }

    @Test
    void skips_existing_companion_codes() {
        Model m = model(14L, "product");
        Field source = field(300L, "title", true);
        Field existingEn = field(301L, "title_en_us", false);
        when(fieldBindingMapper.findByModelId(14L)).thenReturn(List.of(
                binding(14L, 300L),
                binding(14L, 301L)
        ));
        when(metaFieldMapper.findByIds(List.of(300L, 301L)))
                .thenReturn(List.of(source, existingEn));
        when(metaFieldMapper.insertIdempotent(any())).thenAnswer(inv -> {
            Field f = inv.getArgument(0);
            f.setId(System.nanoTime());
            return 1;
        });
        when(fieldBindingMapper.countByModelAndField(eq(14L), anyLong())).thenReturn(0);
        when(fieldBindingMapper.getMaxFieldOrder(14L)).thenReturn(null);

        List<String> created = expander.expandI18nFields(m);

        // en_us already present, so only ja_jp + ko_kr created
        assertThat(created).containsExactly("title_ja_jp", "title_ko_kr");
    }

    @Test
    void skips_binding_when_field_already_bound() {
        Model m = model(15L, "product");
        Field source = field(400L, "title", true);
        when(fieldBindingMapper.findByModelId(15L))
                .thenReturn(List.of(binding(15L, 400L)));
        when(metaFieldMapper.findByIds(List.of(400L))).thenReturn(List.of(source));
        when(metaFieldMapper.insertIdempotent(any())).thenAnswer(inv -> {
            Field f = inv.getArgument(0);
            f.setId(500L);
            return 1;
        });
        // All companions report already-bound
        when(fieldBindingMapper.countByModelAndField(eq(15L), eq(500L))).thenReturn(1);

        List<String> created = expander.expandI18nFields(m);

        assertThat(created).hasSize(3);
        verify(fieldBindingMapper, never()).insert(any(ModelFieldBinding.class));
        verify(fieldBindingMapper, atLeastOnce()).countByModelAndField(eq(15L), eq(500L));
    }

    @Test
    void uses_max_order_plus_one_when_present() {
        Model m = model(16L, "doc");
        Field source = field(600L, "name", true);
        when(fieldBindingMapper.findByModelId(16L))
                .thenReturn(List.of(binding(16L, 600L)));
        when(metaFieldMapper.findByIds(List.of(600L))).thenReturn(List.of(source));
        when(metaFieldMapper.insertIdempotent(any())).thenAnswer(inv -> {
            Field f = inv.getArgument(0);
            f.setId(700L + System.nanoTime() % 1000);
            return 1;
        });
        when(fieldBindingMapper.countByModelAndField(eq(16L), anyLong())).thenReturn(0);
        when(fieldBindingMapper.getMaxFieldOrder(16L)).thenReturn(7);

        expander.expandI18nFields(m);

        ArgumentCaptor<ModelFieldBinding> captor = ArgumentCaptor.forClass(ModelFieldBinding.class);
        verify(fieldBindingMapper, times(3)).insert(captor.capture());
        // First inserted should be order 8
        assertThat(captor.getAllValues().get(0).getFieldOrder()).isEqualTo(8);
    }
}
