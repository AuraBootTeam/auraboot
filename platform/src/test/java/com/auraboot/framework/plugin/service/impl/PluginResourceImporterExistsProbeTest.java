package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The import exists-probes must resolve through the same DB-current query the
 * update branch uses. The runtime definition cache and the application-release
 * catalog can both report a model an import cannot load (draft row cached by a
 * rolled-back attempt, catalog-only model) — that mismatch used to deadlock
 * importModel into "Model exists but cannot be loaded for update" on retries.
 */
class PluginResourceImporterExistsProbeTest {

    private final PluginResourceImporterImpl importer =
            mock(PluginResourceImporterImpl.class, CALLS_REAL_METHODS);
    private final MetaModelMapper models = mock(MetaModelMapper.class);
    private final MetaFieldMapper fields = mock(MetaFieldMapper.class);
    private final MetaModelService modelService = mock(MetaModelService.class);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(importer, "metaModelMapper", models);
        ReflectionTestUtils.setField(importer, "metaFieldMapper", fields);
        ReflectionTestUtils.setField(importer, "metaModelService", modelService);
    }

    @Test
    void modelProbeResolvesThroughTheDbCurrentRowForTheTenant() {
        when(models.findCurrentByCodeForTenant(42L, "data_permission")).thenReturn(new Model());

        Boolean present = ReflectionTestUtils.invokeMethod(importer, "checkModelExists", 42L, "data_permission");
        assertThat(present).isTrue();

        verifyNoInteractions(modelService);
    }

    @Test
    void modelProbeReportsAbsentWhenNoDbRowExistsEvenIfRuntimeWouldKnowBetter() {
        when(models.findCurrentByCodeForTenant(42L, "data_permission")).thenReturn(null);

        Boolean absent = ReflectionTestUtils.invokeMethod(importer, "checkModelExists", 42L, "data_permission");
        assertThat(absent).isFalse();

        verifyNoInteractions(modelService);
    }

    @Test
    void fieldProbeResolvesThroughTheSameQueryTheUpdateBranchUses() {
        when(fields.findCurrentByCode("unit_price")).thenReturn(new Field());
        when(fields.findCurrentByCode("missing_field")).thenReturn(null);

        Boolean fieldPresent = ReflectionTestUtils.invokeMethod(importer, "checkFieldExists", 42L, "unit_price");
        assertThat(fieldPresent).isTrue();
        Boolean fieldAbsent = ReflectionTestUtils.invokeMethod(importer, "checkFieldExists", 42L, "missing_field");
        assertThat(fieldAbsent).isFalse();
    }
}
