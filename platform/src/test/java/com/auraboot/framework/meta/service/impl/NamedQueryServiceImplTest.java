package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.connector.service.ApiConnectorService;
import com.auraboot.framework.decision.service.DecisionUsageIndexService;
import com.auraboot.framework.meta.dto.NamedQueryBatchStatusRequest;
import com.auraboot.framework.meta.dto.NamedQueryCreateRequest;
import com.auraboot.framework.meta.dto.NamedQueryFieldRequest;
import com.auraboot.framework.meta.dto.NamedQueryUpdateRequest;
import com.auraboot.framework.meta.entity.NamedQuery;
import com.auraboot.framework.meta.entity.NamedQueryField;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.NamedQueryFieldMapper;
import com.auraboot.framework.meta.mapper.NamedQueryMapper;
import com.auraboot.framework.meta.mapper.NamedQueryVersionMapper;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NamedQueryServiceImplTest {

    private final NamedQueryMapper namedQueryMapper = mock(NamedQueryMapper.class);
    private final NamedQueryFieldMapper namedQueryFieldMapper = mock(NamedQueryFieldMapper.class);
    private final NamedQueryVersionMapper namedQueryVersionMapper = mock(NamedQueryVersionMapper.class);
    private final DynamicDataMapper dynamicDataMapper = mock(DynamicDataMapper.class);
    private final NamedQueryRateLimiter rateLimiter = mock(NamedQueryRateLimiter.class);
    private final ApiConnectorService apiConnectorService = mock(ApiConnectorService.class);
    private final DecisionUsageIndexService usageIndexService = mock(DecisionUsageIndexService.class);
    private final DataPermissionEngine dataPermissionEngine = mock(DataPermissionEngine.class);

    private final NamedQueryServiceImpl service = new NamedQueryServiceImpl(
            namedQueryMapper,
            namedQueryFieldMapper,
            namedQueryVersionMapper,
            dynamicDataMapper,
            rateLimiter,
            apiConnectorService,
            usageIndexService,
            dataPermissionEngine);

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    void createRefreshesDecisionUsageIndexForConnectorNamedQuery() {
        MetaContext.setContext(10L, 20L, "tester", "Tester");
        when(namedQueryMapper.countByCode("customer_lookup", null)).thenReturn(0);

        NamedQueryCreateRequest request = new NamedQueryCreateRequest();
        request.setCode("customer_lookup");
        request.setTitle("Customer Lookup");
        request.setConnectorPid("api-1");
        request.setConnectorEndpointCode("lookup");
        request.setResourceCode("customer");
        request.setActionCode("read");

        service.create(request);

        ArgumentCaptor<NamedQuery> captor = ArgumentCaptor.forClass(NamedQuery.class);
        verify(namedQueryMapper).insert(captor.capture());
        assertThat(captor.getValue().getPid()).isNotBlank();
        assertThat(captor.getValue().getResourceCode()).isEqualTo("customer");
        assertThat(captor.getValue().getActionCode()).isEqualTo("read");
        verify(usageIndexService).refreshSource("NAMED_QUERY", captor.getValue().getPid());
    }

    @Test
    void updateRefreshesDecisionUsageIndexForExistingNamedQuery() {
        NamedQuery query = connectorQuery();
        when(namedQueryMapper.findByPid("nq-1")).thenReturn(query);

        NamedQueryUpdateRequest request = new NamedQueryUpdateRequest();
        request.setDescription("Updated");
        request.setResourceCode("customer");
        request.setActionCode("read");

        service.update("nq-1", request);

        assertThat(query.getDescription()).isEqualTo("Updated");
        assertThat(query.getResourceCode()).isEqualTo("customer");
        assertThat(query.getActionCode()).isEqualTo("read");
        verify(namedQueryMapper).updateById(query);
        verify(usageIndexService).refreshSource("NAMED_QUERY", "nq-1");
    }

    @Test
    void executeQueryAppliesDeclaredDataScopeRowFilter() {
        MetaContext.setContext(10L, 20L, "tester", "Tester");
        NamedQuery query = sqlQuery();
        query.setResourceCode("e2et_order");
        query.setActionCode("read");
        when(namedQueryMapper.findByCode("order_summary")).thenReturn(query);
        when(namedQueryFieldMapper.findByQueryCode(10L, "order_summary")).thenReturn(List.of());
        when(rateLimiter.tryAcquire(10L, "order_summary", 60)).thenReturn(true);
        when(dataPermissionEngine.buildRowFilter(10L, "e2et_order", "read", 20L))
                .thenReturn("AND created_by = 20");
        when(dynamicDataMapper.countByQueryWithoutTenant(anyString(), anyMap())).thenReturn(1L);
        when(dynamicDataMapper.selectByQueryWithoutTenant(anyString(), anyMap()))
                .thenReturn(List.of(Map.of("pid", "order-1", "created_by", 20L)));

        service.executeQuery("order_summary", new com.auraboot.framework.meta.dto.NamedQueryTestRequest());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(dynamicDataMapper).selectByQueryWithoutTenant(sqlCaptor.capture(), anyMap());
        assertThat(sqlCaptor.getValue()).contains("created_by = 20");
        verify(dataPermissionEngine).buildRowFilter(10L, "e2et_order", "read", 20L);
    }

    @Test
    void executeQueryDoesNotClaimDataScopeWhenDeclarationMissing() {
        MetaContext.setContext(10L, 20L, "tester", "Tester");
        NamedQuery query = sqlQuery();
        when(namedQueryMapper.findByCode("order_summary")).thenReturn(query);
        when(namedQueryFieldMapper.findByQueryCode(10L, "order_summary")).thenReturn(List.of());
        when(rateLimiter.tryAcquire(10L, "order_summary", 60)).thenReturn(true);
        when(dynamicDataMapper.countByQueryWithoutTenant(anyString(), anyMap())).thenReturn(1L);
        when(dynamicDataMapper.selectByQueryWithoutTenant(anyString(), anyMap()))
                .thenReturn(List.of(Map.of("pid", "order-1")));

        service.executeQuery("order_summary", new com.auraboot.framework.meta.dto.NamedQueryTestRequest());

        verify(dataPermissionEngine, org.mockito.Mockito.never())
                .buildRowFilter(any(), any(), any(), any());
    }

    @Test
    void updateConnectorBindingRefreshesDecisionUsageIndex() {
        NamedQuery query = new NamedQuery();
        query.setPid("nq-1");
        query.setTenantId(10L);
        query.setCode("customer_lookup");
        query.setTitle("Customer Lookup");
        query.setFromSql("SELECT id FROM customer");
        query.setStatus("draft");
        when(namedQueryMapper.findByPid("nq-1")).thenReturn(query);

        NamedQueryUpdateRequest request = new NamedQueryUpdateRequest();
        request.setConnectorPid("api-2");
        request.setConnectorEndpointCode("lookup");

        service.update("nq-1", request);

        assertThat(query.getFromSql()).isNull();
        assertThat(query.getConnectorPid()).isEqualTo("api-2");
        assertThat(query.getConnectorEndpointCode()).isEqualTo("lookup");
        verify(namedQueryMapper).updateById(query);
        verify(usageIndexService).refreshSource("NAMED_QUERY", "nq-1");
    }

    @Test
    void statusUpdateRefreshesDecisionUsageIndexForExistingNamedQuery() {
        MetaContext.setContext(10L, 20L, "tester", "Tester");
        NamedQuery query = connectorQuery();
        query.setStatus("testing");
        when(namedQueryMapper.findByPid("nq-1")).thenReturn(query);

        service.updateStatus("nq-1", "published");

        verify(namedQueryMapper).updateById(query);
        verify(usageIndexService).refreshSource("NAMED_QUERY", "nq-1");
    }

    @Test
    void deleteRemovesDecisionUsageIndexSourceForNamedQuery() {
        MetaContext.setContext(10L, 20L, "tester", "Tester");
        NamedQuery query = connectorQuery();
        query.setId(99L);
        query.setCode("customer_lookup");
        when(namedQueryMapper.findByPid("nq-1")).thenReturn(query);

        service.delete("nq-1");

        verify(namedQueryFieldMapper).deleteByQuery(10L, "customer_lookup");
        verify(namedQueryMapper).deleteById(99L);
        verify(usageIndexService).deleteSource("NAMED_QUERY", "nq-1");
    }

    @Test
    void batchStatusUpdateRefreshesDecisionUsageIndexForEachSuccessfulNamedQuery() {
        when(namedQueryMapper.updateStatusByPid("nq-1", "archived")).thenReturn(1);
        when(namedQueryMapper.updateStatusByPid("missing", "archived")).thenReturn(0);
        NamedQueryBatchStatusRequest request = new NamedQueryBatchStatusRequest();
        request.setPids(List.of("nq-1", "missing"));
        request.setTargetStatus("archived");

        service.batchUpdateStatus(request);

        verify(usageIndexService).refreshSource("NAMED_QUERY", "nq-1");
    }

    @Test
    void addFieldRejectsInternalPublicOutputAlias() {
        MetaContext.setContext(10L, 20L, "tester", "Tester");
        NamedQueryFieldRequest request = new NamedQueryFieldRequest();
        request.setFieldCode("tenant_id");
        request.setColumnExpr("tenant_id");
        request.setDataType("number");

        assertThatThrownBy(() -> service.addField("public_query", request))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("reserved for internal identity")
                .hasMessageContaining("tenant_id");
        verify(namedQueryFieldMapper, never()).insert(any(NamedQueryField.class));
    }

    private NamedQuery connectorQuery() {
        NamedQuery query = new NamedQuery();
        query.setPid("nq-1");
        query.setTenantId(10L);
        query.setCode("customer_lookup");
        query.setTitle("Customer Lookup");
        query.setConnectorPid("api-1");
        query.setConnectorEndpointCode("lookup");
        query.setStatus("draft");
        return query;
    }

    private NamedQuery sqlQuery() {
        NamedQuery query = new NamedQuery();
        query.setPid("nq-1");
        query.setTenantId(10L);
        query.setCode("order_summary");
        query.setTitle("Order Summary");
        query.setFromSql("mt_e2et_order WHERE tenant_id = #{params.tenantId}");
        query.setStatus("draft");
        return query;
    }
}
