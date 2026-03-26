package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RollUpFieldRegistry.
 * Tests cache building, lookup, and invalidation.
 */
@ExtendWith(MockitoExtension.class)
class RollUpFieldRegistryTest {

    @Mock
    private MetaFieldMapper metaFieldMapper;
    @Mock
    private MetaModelFieldBindingMapper modelFieldBindingMapper;

    private RollUpFieldRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new RollUpFieldRegistry(metaFieldMapper, modelFieldBindingMapper);
    }

    private Field createFieldWithRollUp(Long id, String code, String childModel,
                                         String childField, String childFk, String function) {
        Field field = new Field();
        field.setId(id);
        field.setCode(code);

        FieldFeatureBean feature = new FieldFeatureBean();
        FieldFeatureBean.RollUpConfig rollUp = new FieldFeatureBean.RollUpConfig();
        rollUp.setChildModel(childModel);
        rollUp.setChildField(childField);
        rollUp.setChildFk(childFk);
        rollUp.setFunction(function);
        feature.setRollUp(rollUp);
        field.setFeature(feature);

        return field;
    }

    private Field createFieldWithoutRollUp(Long id, String code) {
        Field field = new Field();
        field.setId(id);
        field.setCode(code);
        field.setFeature(new FieldFeatureBean());
        return field;
    }

    @Test
    @DisplayName("getTargets returns matching roll-up targets for a child model")
    void getTargets_found() {
        Field rollUpField = createFieldWithRollUp(1L, "or_total_amount",
                "order_line", "ol_amount", "ol_order_id", "sum");

        when(metaFieldMapper.findCurrentByTenant()).thenReturn(List.of(rollUpField));
        when(modelFieldBindingMapper.findModelCodeByFieldId(1L)).thenReturn("sales_order");

        List<RollUpFieldRegistry.RollUpTarget> targets = registry.getTargets("order_line");

        assertThat(targets).hasSize(1);
        RollUpFieldRegistry.RollUpTarget target = targets.get(0);
        assertThat(target.getParentModelCode()).isEqualTo("sales_order");
        assertThat(target.getParentFieldCode()).isEqualTo("or_total_amount");
        assertThat(target.getChildField()).isEqualTo("ol_amount");
        assertThat(target.getChildFk()).isEqualTo("ol_order_id");
        assertThat(target.getFunction()).isEqualTo("sum");
    }

    @Test
    @DisplayName("getTargets returns empty list when no roll-up fields match")
    void getTargets_noMatch() {
        Field regularField = createFieldWithoutRollUp(1L, "or_name");

        when(metaFieldMapper.findCurrentByTenant()).thenReturn(List.of(regularField));

        List<RollUpFieldRegistry.RollUpTarget> targets = registry.getTargets("order_line");

        assertThat(targets).isEmpty();
    }

    @Test
    @DisplayName("getTargets caches results after first scan")
    void getTargets_cached() {
        when(metaFieldMapper.findCurrentByTenant()).thenReturn(List.of());

        registry.getTargets("model_a");
        registry.getTargets("model_b");

        // Only one scan should have happened
        verify(metaFieldMapper, times(1)).findCurrentByTenant();
    }

    @Test
    @DisplayName("invalidate clears cache and forces rescan")
    void invalidate_clearsCache() {
        when(metaFieldMapper.findCurrentByTenant()).thenReturn(List.of());

        registry.getTargets("model_a");
        registry.invalidate();
        registry.getTargets("model_a");

        // Two scans after invalidation
        verify(metaFieldMapper, times(2)).findCurrentByTenant();
    }

    @Test
    @DisplayName("multiple roll-up fields on same child model")
    void multipleTargetsSameChild() {
        Field sumField = createFieldWithRollUp(1L, "or_total_amount",
                "order_line", "ol_amount", "ol_order_id", "sum");
        Field countField = createFieldWithRollUp(2L, "or_line_count",
                "order_line", "ol_amount", "ol_order_id", "count");

        when(metaFieldMapper.findCurrentByTenant()).thenReturn(List.of(sumField, countField));
        when(modelFieldBindingMapper.findModelCodeByFieldId(1L)).thenReturn("sales_order");
        when(modelFieldBindingMapper.findModelCodeByFieldId(2L)).thenReturn("sales_order");

        List<RollUpFieldRegistry.RollUpTarget> targets = registry.getTargets("order_line");

        assertThat(targets).hasSize(2);
        assertThat(targets).extracting(RollUpFieldRegistry.RollUpTarget::getFunction)
                .containsExactlyInAnyOrder("sum", "count");
    }

    @Test
    @DisplayName("skips fields with incomplete rollUp config")
    void skipsIncompleteConfig() {
        Field incompleteField = new Field();
        incompleteField.setId(1L);
        incompleteField.setCode("or_total");
        FieldFeatureBean feature = new FieldFeatureBean();
        FieldFeatureBean.RollUpConfig rollUp = new FieldFeatureBean.RollUpConfig();
        rollUp.setChildModel("order_line");
        // missing childFk
        feature.setRollUp(rollUp);
        incompleteField.setFeature(feature);

        when(metaFieldMapper.findCurrentByTenant()).thenReturn(List.of(incompleteField));

        List<RollUpFieldRegistry.RollUpTarget> targets = registry.getTargets("order_line");

        assertThat(targets).isEmpty();
    }

    @Test
    @DisplayName("skips fields with no model binding")
    void skipsNoBinding() {
        Field rollUpField = createFieldWithRollUp(1L, "or_total_amount",
                "order_line", "ol_amount", "ol_order_id", "sum");

        when(metaFieldMapper.findCurrentByTenant()).thenReturn(List.of(rollUpField));
        when(modelFieldBindingMapper.findModelCodeByFieldId(1L)).thenReturn(null);

        List<RollUpFieldRegistry.RollUpTarget> targets = registry.getTargets("order_line");

        assertThat(targets).isEmpty();
    }

    @Test
    @DisplayName("function defaults to SUM when null")
    void defaultFunction() {
        Field field = createFieldWithRollUp(1L, "or_total",
                "order_line", "ol_amount", "ol_order_id", null);

        when(metaFieldMapper.findCurrentByTenant()).thenReturn(List.of(field));
        when(modelFieldBindingMapper.findModelCodeByFieldId(1L)).thenReturn("sales_order");

        List<RollUpFieldRegistry.RollUpTarget> targets = registry.getTargets("order_line");

        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).getFunction()).isEqualTo("sum");
    }
}
