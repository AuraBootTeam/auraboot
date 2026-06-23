package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.AggregateQueryRequest;
import com.auraboot.framework.meta.dto.AggregateQueryResponse;
import com.auraboot.framework.meta.dto.MetricConfig;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.NamedQueryFieldMapper;
import com.auraboot.framework.meta.mapper.NamedQueryMapper;
import com.auraboot.framework.meta.service.DataDomainService;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.MetaModelService;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AggregateQueryServiceImplDataScopeTest {

    private static final Long TENANT_ID = 10L;
    private static final Long USER_ID = 20L;
    private static final Long MEMBER_ID = 30L;
    private static final String MODEL_CODE = "phase_one_model";

    @Mock private DynamicDataMapper dynamicDataMapper;
    @Mock private NamedQueryMapper namedQueryMapper;
    @Mock private NamedQueryFieldMapper namedQueryFieldMapper;
    @Mock private MetaModelService metaModelService;
    @Mock private DataPermissionEngine dataPermissionEngine;
    @Mock private DataDomainService dataDomainService;

    @InjectMocks
    private AggregateQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, USER_ID, "user-pid", "tester");
        MetaContext.setMemberId(MEMBER_ID);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("dynamic chart aggregate must include DataScope row filter")
    void dynamicAggregate_appliesDataScopeRowFilter() {
        when(metaModelService.getTableName(MODEL_CODE)).thenReturn("mt_phase_one_model");
        when(dataPermissionEngine.buildRowFilter(TENANT_ID, MODEL_CODE, USER_ID))
                .thenReturn("AND created_by = 20");
        when(dataDomainService.buildDomainFilter(MODEL_CODE, USER_ID)).thenReturn("");
        when(dynamicDataMapper.selectByQuery(anyString(), anyMap()))
                .thenReturn(List.of(Map.of("total", 1L)));

        AggregateQueryResponse response = service.execute(countRequest());

        assertThat(response.getRows()).hasSize(1);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(dynamicDataMapper).selectByQuery(sqlCaptor.capture(), anyMap());
        assertThat(sqlCaptor.getValue()).contains("created_by = 20");
        verify(dataPermissionEngine).buildRowFilter(TENANT_ID, MODEL_CODE, USER_ID);
    }

    @Test
    @DisplayName("dynamic chart aggregate fails closed when DataScope evaluation fails")
    void dynamicAggregate_failsClosedWhenDataScopeFails() {
        when(metaModelService.getTableName(MODEL_CODE)).thenReturn("mt_phase_one_model");
        when(dataPermissionEngine.buildRowFilter(TENANT_ID, MODEL_CODE, USER_ID))
                .thenThrow(new RuntimeException("scope unavailable"));

        assertThatThrownBy(() -> service.execute(countRequest()))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("Data permission evaluation failed");
    }

    private AggregateQueryRequest countRequest() {
        MetricConfig metric = new MetricConfig();
        metric.setField("id");
        metric.setAggregation("count");
        metric.setAlias("total");

        AggregateQueryRequest request = new AggregateQueryRequest();
        request.setModelCode(MODEL_CODE);
        request.setMetrics(List.of(metric));
        return request;
    }
}
