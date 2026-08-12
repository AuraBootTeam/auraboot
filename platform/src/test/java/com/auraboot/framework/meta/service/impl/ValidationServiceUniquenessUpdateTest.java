package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.ValidationContext;
import com.auraboot.framework.meta.dto.ValidationResult;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ValidationServiceUniquenessUpdateTest {

    @Mock
    private DynamicDataMapper mapper;

    private ValidationServiceImpl validation;
    private ModelDefinition model;

    @BeforeEach
    void setUp() {
        validation = new ValidationServiceImpl(mapper);
        model = ModelDefinition.builder()
                .code("inv_kitting_result")
                .tableName("mt_inv_kitting_result")
                .fields(List.of(FieldDefinition.builder()
                        .code("inv_kr_work_order_id")
                        .name("Work Order")
                        .columnName("inv_kr_work_order_id")
                        .dataType("string")
                        .unique(true)
                        .build()))
                .build();
        MetaContext.setContext(17L, 23L, null, "test");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void updateExcludesCurrentPublicPidFromUniqueCheck() {
        when(mapper.selectByQuery(
                argThat(sql -> sql.contains("pid != #{params.excludePid}")),
                argThat(params -> "KIT-1".equals(params.get("excludePid")))))
                .thenReturn(List.of(Map.of("cnt", 0)));

        ValidationResult result = validation.validateUniqueness(model, Map.of(
                "pid", "KIT-1",
                "inv_kr_work_order_id", "WO-1"
        ), ValidationContext.UPDATE);

        assertTrue(result.getValid(), result.getErrors().toString());
        verify(mapper).selectByQuery(
                argThat(sql -> sql.contains("pid != #{params.excludePid}")),
                argThat(params -> Long.valueOf(17L).equals(params.get("tenantId"))
                        && "KIT-1".equals(params.get("excludePid"))));
    }

    @Test
    void createStillRejectsAnotherRecordWithTheSameUniqueValue() {
        when(mapper.selectByQuery(
                argThat(sql -> !sql.contains("exclude")),
                eq(Map.of("value", "WO-1", "tenantId", 17L))))
                .thenReturn(List.of(Map.of("cnt", 1)));

        ValidationResult result = validation.validateUniqueness(model, Map.of(
                "inv_kr_work_order_id", "WO-1"
        ), ValidationContext.CREATE);

        assertFalse(result.getValid());
        assertTrue(result.getErrors().getFirst().contains("already exists"));
    }
}
