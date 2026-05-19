package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.QueryBuilderService;
import com.auraboot.framework.permission.service.AutoPermissionAssignmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetaModelServiceImplSearchTest {

    @Mock
    private MetaModelMapper metaModelMapper;
    @Mock
    private MetaFieldMapper metaFieldMapper;
    @Mock
    private QueryBuilderService queryBuilderService;
    @Mock
    private MetaModelFieldBindingMapper fieldBindingMapper;
    @Mock
    private AutoPermissionAssignmentService autoPermissionAssignmentService;

    @Test
    void searchModelsLoadsFieldCountsInBatch() {
        Model first = model(11L, "batch_count_one");
        Model second = model(22L, "batch_count_two");
        when(metaModelMapper.countByKeyword(null, null, null, null, true)).thenReturn(2L);
        when(metaModelMapper.searchByKeyword(null, null, null, null, null, null, true, 0L, 20))
                .thenReturn(List.of(first, second));
        when(fieldBindingMapper.countUserFieldsByModelIds(List.of(11L, 22L)))
                .thenReturn(List.of(new MetaModelFieldBindingMapper.ModelFieldCount(11L, 3)));

        MetaModelServiceImpl service = new MetaModelServiceImpl(
                metaModelMapper,
                metaFieldMapper,
                queryBuilderService,
                fieldBindingMapper,
                autoPermissionAssignmentService
        );

        PageResult<MetaModelDTO> result = service.searchModels(
                1, 20, null, null, null, null, null, null, null, null, true);

        assertThat(result.getRecords())
                .extracting(MetaModelDTO::getFieldCount)
                .containsExactly(3, 0);
        verify(fieldBindingMapper).countUserFieldsByModelIds(List.of(11L, 22L));
        verify(fieldBindingMapper, never()).countUserFieldsByModelId(11L);
        verify(fieldBindingMapper, never()).countUserFieldsByModelId(22L);
    }

    private static Model model(Long id, String code) {
        Model model = new Model();
        model.setId(id);
        model.setPid("pid-" + code);
        model.setCode(code);
        model.setVersion(1);
        model.setIsCurrent(true);
        model.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        model.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return model;
    }
}
